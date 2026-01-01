package com.ruoyi.salesforce.service.impl;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDiffVo;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.service.ISysDictTypeService;
import com.sforce.soap.metadata.*;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SfMetadataServiceImpl implements ISfMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SfMetadataServiceImpl.class);

    @Autowired
    private ISfOrgService sfOrgService;

    @Autowired
    private ISysDictTypeService dictTypeService;

    @Autowired
    private ISysDictDataService dictDataService;

    @Autowired
    private RedisCache redisCache;

    private static final String REDIS_META_KEY_PREFIX = "sf:meta:";
    private static final long CACHE_TTL_MINUTES = 30;
    private static final String DICT_TYPE_KEY = "sys_salesforce_metadata_type";

    // 【调试】添加启动日志，确保新代码被加载
    @PostConstruct
    public void init() {
        log.info("=================================================================");
        log.info(">>> SfMetadataServiceImpl (FastJson2 安全版) 已加载 <<<");
        log.info("=================================================================");
    }

    @Override
    public MetadataConnection getMetadataConnection(Long orgId) throws ConnectionException {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if (org == null) throw new ConnectionException("未找到ID为 " + orgId + " 的Salesforce环境配置！");
        if (StringUtils.isEmpty(org.getAccessToken()) || StringUtils.isEmpty(org.getInstanceUrl())) {
            throw new ConnectionException("环境 [" + org.getName() + "] 尚未授权，请先前往环境管理进行授权。");
        }
        verifyAndRefreshSession(org);
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(org.getAccessToken());
        config.setConnectionTimeout(60000);
        config.setReadTimeout(600000);
        String metadataEndpoint = org.getInstanceUrl() + "/services/Soap/m/58.0";
        config.setServiceEndpoint(metadataEndpoint);
        return new MetadataConnection(config);
    }

    private void verifyAndRefreshSession(SfOrg org) throws ConnectionException {
        try {
            ConnectorConfig testConfig = new ConnectorConfig();
            testConfig.setSessionId(org.getAccessToken());
            testConfig.setServiceEndpoint(org.getInstanceUrl() + "/services/Soap/m/58.0");
            MetadataConnection testConn = new MetadataConnection(testConfig);
            testConn.describeMetadata(58.0);
        } catch (ConnectionException e) {
            if (isSessionExpired(e)) {
                log.info("检测到 Org [{}] Session 已过期，正在执行自动续期...", org.getName());
                try {
                    refreshAccessToken(org);
                    log.info("Org [{}] 自动续期成功！", org.getName());
                } catch (Exception refreshEx) {
                    log.error("自动续期失败", refreshEx);
                    throw new ConnectionException("Salesforce授权已过期且自动续期失败：" + refreshEx.getMessage());
                }
            } else {
                log.error("Salesforce 连接验证异常", e);
                throw e;
            }
        }
    }

    private boolean isSessionExpired(ConnectionException e) {
        String msg = e.getMessage();
        if (StringUtils.isEmpty(msg)) return false;
        return msg.contains("INVALID_SESSION_ID") || msg.contains("Session expired") ||
                msg.contains("Session not found") || msg.contains("Full authentication is required");
    }

    private void refreshAccessToken(SfOrg org) {
        if (StringUtils.isEmpty(org.getRefreshToken()) || StringUtils.isEmpty(org.getClientId()) || StringUtils.isEmpty(org.getClientSecret())) {
            throw new ServiceException("无法自动续期：缺少 Refresh Token、Client ID 或 Client Secret。");
        }
        String instance = "Sandbox".equalsIgnoreCase(org.getOrgType()) ? "https://test.salesforce.com" : "https://login.salesforce.com";
        String tokenUrl = instance + "/services/oauth2/token";
        String result = HttpRequest.post(tokenUrl)
                .form("grant_type", "refresh_token")
                .form("client_id", org.getClientId())
                .form("client_secret", org.getClientSecret())
                .form("refresh_token", org.getRefreshToken())
                .execute().body();
        JSONObject json = JSON.parseObject(result); // FastJson2
        if (json.getString("access_token") != null) {
            org.setAccessToken(json.getString("access_token"));
            if (json.getString("instance_url") != null) org.setInstanceUrl(json.getString("instance_url"));
            sfOrgService.updateSfOrg(org);
        } else {
            throw new ServiceException("刷新失败: " + json.getString("error_description"));
        }
    }

    @Override
    public List<String> testConnection(Long orgId) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        ListMetadataQuery query = new ListMetadataQuery();
        query.setType("ApexClass");
        FileProperties[] results = connection.listMetadata(new ListMetadataQuery[]{query}, 58.0);
        List<String> classNames = new ArrayList<>();
        if (results != null) {
            for (FileProperties file : results)
                if (file.getFullName() != null) classNames.add(file.getFullName());
        }
        return classNames;
    }

    @Override
    public String retrieveMetadata(Long orgId, String type, String memberName) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        RetrieveRequest retrieveRequest = new RetrieveRequest();
        retrieveRequest.setApiVersion(58.0);
        com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
        PackageTypeMembers typeMember = new PackageTypeMembers();
        typeMember.setName(type);
        typeMember.setMembers(new String[]{memberName});
        manifest.setTypes(new PackageTypeMembers[]{typeMember});
        manifest.setVersion("58.0");
        retrieveRequest.setUnpackaged(manifest);
        AsyncResult asyncResult = connection.retrieve(retrieveRequest);
        RetrieveResult result = waitForRetrieve(connection, asyncResult.getId());
        if (result.getStatus() != RetrieveStatus.Succeeded)
            throw new Exception("Retrieve failed: " + result.getErrorMessage());
        return smartExtract(result.getZipFile(), type, memberName);
    }

    private String smartExtract(byte[] zipData, String type, String memberName) throws Exception {
        if (zipData == null || zipData.length == 0) return "No content retrieved.";
        StringBuilder contentBuilder = new StringBuilder();
        boolean found = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entry.isDirectory() || entryName.endsWith("package.xml")) continue;
                boolean isMatch = false;
                if (isObjectChild(type) && memberName.contains(".")) {
                    String objName = memberName.split("\\.")[0];
                    if (entryName.endsWith("objects/" + objName + ".object")) isMatch = true;
                } else if (isWorkflowChild(type) && memberName.contains(".")) {
                    String objName = memberName.split("\\.")[0];
                    if (entryName.endsWith("workflows/" + objName + ".workflow")) isMatch = true;
                } else if (isBundleType(type) && entryName.contains(memberName)) isMatch = true;
                else if (entryName.contains(memberName)) isMatch = true;

                if (isMatch) {
                    found = true;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                    String fileContent = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    if (isBundleType(type)) {
                        contentBuilder.append("/* --- File: ").append(entryName).append(" --- */\n").append(fileContent).append("\n\n");
                    } else {
                        if (!entryName.endsWith("-meta.xml") || entryName.endsWith(".object") || entryName.endsWith(".workflow"))
                            return fileContent;
                        if (contentBuilder.length() == 0) contentBuilder.append(fileContent);
                    }
                }
            }
        }
        if (!found) return "Error: File not found in retrieved package.";
        return contentBuilder.toString();
    }

    private boolean isObjectChild(String type) {
        return Arrays.asList("CustomField", "ValidationRule", "RecordType", "WebLink", "ListView", "FieldSet", "CompactLayout", "BusinessProcess", "Index", "SharingReason").contains(type);
    }

    private boolean isWorkflowChild(String type) {
        return Arrays.asList("WorkflowRule", "WorkflowAlert", "WorkflowFieldUpdate", "WorkflowOutboundMessage", "WorkflowTask").contains(type);
    }

    private boolean isBundleType(String type) {
        return "LightningComponentBundle".equals(type) || "AuraDefinitionBundle".equals(type);
    }

    @Override
    public List<FileProperties> listMetadata(Long orgId, String type) throws Exception {
        String cacheKey = REDIS_META_KEY_PREFIX + orgId + ":" + type;
        List<FileProperties> cacheList = redisCache.getCacheList(cacheKey);
        if (cacheList != null && !cacheList.isEmpty()) return cacheList;
        return refreshMetadataCache(orgId, type);
    }

    @Override
    public List<FileProperties> refreshMetadataCache(Long orgId, String type) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        ListMetadataQuery query = new ListMetadataQuery();
        query.setType(type);
        FileProperties[] results = connection.listMetadata(new ListMetadataQuery[]{query}, 58.0);
        List<FileProperties> list = new ArrayList<>();
        if (results != null) for (FileProperties f : results) if (f.getFullName() != null) list.add(f);
        list.sort((a, b) -> b.getLastModifiedDate().compareTo(a.getLastModifiedDate()));
        String cacheKey = REDIS_META_KEY_PREFIX + orgId + ":" + type;
        redisCache.setCacheList(cacheKey, !list.isEmpty() ? list : new ArrayList<>());
        redisCache.expire(cacheKey, !list.isEmpty() ? CACHE_TTL_MINUTES : 5, TimeUnit.MINUTES);
        return list;
    }

    @Override
    public SfDiffVo compareMetadata(Long sourceOrgId, Long targetOrgId, String type, String memberName) throws Exception {
        CompletableFuture<String> sourceFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadata(sourceOrgId, type, memberName);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage());
            }
        });
        CompletableFuture<String> targetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadata(targetOrgId, type, memberName);
            } catch (Exception e) {
                return "";
            }
        });
        CompletableFuture.allOf(sourceFuture, targetFuture).join();
        return new SfDiffVo(sourceFuture.get(), targetFuture.get());
    }

    @Override
    public byte[] retrieveZipByManifest(Long orgId, com.sforce.soap.metadata.Package manifest) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        RetrieveRequest request = new RetrieveRequest();
        request.setApiVersion(58.0);
        request.setUnpackaged(manifest);
        AsyncResult asyncResult = connection.retrieve(request);
        RetrieveResult result = waitForRetrieve(connection, asyncResult.getId());
        if (result.getStatus() == RetrieveStatus.Succeeded) return result.getZipFile();
        else throw new Exception("Retrieve failed: " + result.getErrorMessage());
    }

    @Override
    public AsyncResult deployZip(Long orgId, byte[] zipData, DeployOptions options) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        return connection.deploy(zipData, options);
    }

    /**
     * 【生产环境优化版 - FastJson2】检查部署状态
     * 1. 优先使用“轻量级”查询 (includeDetails=false)。
     * 2. 只有当任务完成时，才尝试获取完整详情。
     * 3. 严格的手动序列化，杜绝 StackOverflowError。
     */
    @Override
    public String checkDeployStatus(Long orgId, String processId) throws Exception {
        try {
            MetadataConnection connection = getMetadataConnection(orgId);

            // 1. 默认只查询基本状态，不查 Details（避免内存崩溃）
            DeployResult result = connection.checkDeployStatus(processId, false);

            // 2. 只有当部署结束(Done)时，才尝试拉取一次完整日志
            if (result.isDone()) {
                try {
                    log.info("部署/验证已完成 (ID: {})，正在拉取完整日志...", processId);
                    result = connection.checkDeployStatus(processId, true);
                } catch (Exception e) {
                    log.warn("无法拉取部署详情 (日志可能过大)，自动降级为摘要模式。错误: {}", e.getMessage());
                }
            }

            // 3. 手动构建 JSON (使用 FastJson2)，杜绝递归序列化
            JSONObject json = new JSONObject();

            json.put("id", result.getId());
            json.put("done", result.isDone());
            json.put("status", result.getStatus().name());
            json.put("checkOnly", result.isCheckOnly());
            json.put("numberComponentsDeployed", result.getNumberComponentsDeployed());
            json.put("numberComponentsTotal", result.getNumberComponentsTotal());
            json.put("numberTestsCompleted", result.getNumberTestsCompleted());
            json.put("numberTestsTotal", result.getNumberTestsTotal());
            json.put("errorMessage", result.getErrorMessage());
            json.put("stateDetail", result.getStateDetail());

            if (result.getDetails() != null) {
                JSONObject details = new JSONObject();

                // --- 手工提取 componentFailures ---
                // --- 优化 componentFailures 提取 ---
                if (result.getDetails().getComponentFailures() != null) {
                    JSONArray failures = new JSONArray();
                    for (DeployMessage msg : result.getDetails().getComponentFailures()) {
                        JSONObject f = new JSONObject();
                        f.put("fileName", msg.getFileName());
                        f.put("problem", msg.getProblem());
                        f.put("lineNumber", msg.getLineNumber()); // [新增] 获取行号
                        f.put("columnNumber", msg.getColumnNumber()); // [新增] 获取列号

                        // [新增] 简单的智能分析
                        String solution = analyzeSolution(msg.getProblem());
                        f.put("suggestedSolution", solution);

                        f.put("problemType", msg.getProblemType() != null ? msg.getProblemType().name() : "Error");
                        failures.add(f);
                    }
                    details.put("componentFailures", failures);
                }

                // --- 手工提取 runTestResult ---
                if (result.getDetails().getRunTestResult() != null) {
                    JSONObject testRes = new JSONObject();
                    RunTestsResult sfRunRes = result.getDetails().getRunTestResult();

                    testRes.put("numFailures", sfRunRes.getNumFailures());

                    if (sfRunRes.getFailures() != null) {
                        JSONArray testFailures = new JSONArray();
                        for (RunTestFailure fail : sfRunRes.getFailures()) {
                            JSONObject t = new JSONObject();
                            t.put("name", fail.getName());
                            t.put("methodName", fail.getMethodName());
                            t.put("message", fail.getMessage());
                            t.put("time", fail.getTime());
                            testFailures.add(t);
                        }
                        testRes.put("failures", testFailures);
                    }

                    if (sfRunRes.getCodeCoverageWarnings() != null) {
                        JSONArray warnings = new JSONArray();
                        for (CodeCoverageWarning w : sfRunRes.getCodeCoverageWarnings()) {
                            JSONObject warn = new JSONObject();
                            warn.put("message", w.getMessage());
                            warnings.add(warn);
                        }
                        testRes.put("codeCoverageWarnings", warnings);
                    }
                    details.put("runTestResult", testRes);
                }
                json.put("details", details);
            }

            return json.toString();

        } catch (Throwable t) {
            // 【终极兜底】捕获所有错误（包括 StackOverflow/OOM），防止 JVM 崩溃
            log.error("严重错误：检查部署状态时发生异常，已拦截。", t);

            JSONObject errorJson = new JSONObject();
            errorJson.put("done", true);
            errorJson.put("status", "Failed");
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            errorJson.put("errorMessage", "系统内部错误: " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
            return errorJson.toString();
        }
    }

    // [新增] 辅助分析方法
    private String analyzeSolution(String errorMsg) {
        if (errorMsg == null) return "";
        if (errorMsg.contains("Code coverage")) {
            return "代码覆盖率不足，请编写更多单元测试或检查 @isTest 类。";
        }
        if (errorMsg.contains("Dependent class is invalid")) {
            return "依赖类缺失或由编译错误，请检查相关联的类是否已包含在部署包中。";
        }
        if (errorMsg.contains("FIELD_CUSTOM_VALIDATION_EXCEPTION")) {
            return "触发了自定义验证规则，请检查数据或暂时停用该规则。";
        }
        return "请根据报错信息检查元数据定义。";
    }

    private RetrieveResult waitForRetrieve(MetadataConnection connection, String id) throws Exception {
        int maxPolls = 600;
        int sleepMillis = 1000;
        for (int i = 0; i < maxPolls; i++) {
            RetrieveResult result = connection.checkRetrieveStatus(id, true);
            if (result.isDone()) return result;
            Thread.sleep(sleepMillis);
        }
        throw new Exception("Salesforce Retrieve request timed out (waited 10 mins).");
    }

    @Override
    public String deployRecentValidation(Long orgId, String validationId) throws Exception {
        MetadataConnection conn = getMetadataConnection(orgId);
        return conn.deployRecentValidation(validationId);
    }

    @Override
    public List<String> getAllMetadataTypes(Long orgId) throws Exception {
        MetadataConnection conn = getMetadataConnection(orgId);
        Set<String> typeSet = new HashSet<>();
        try {
            DescribeMetadataResult result = conn.describeMetadata(58.0);
            if (result != null) {
                for (DescribeMetadataObject obj : result.getMetadataObjects()) {
                    typeSet.add(obj.getXmlName());
                    if (obj.getChildXmlNames() != null) Collections.addAll(typeSet, obj.getChildXmlNames());
                }
            }
        } catch (Exception e) {
            // fallback
        }
        List<String> mustHaveTypes = Arrays.asList("ApexClass", "ApexTrigger", "CustomObject", "CustomField");
        typeSet.addAll(mustHaveTypes);
        List<String> types = new ArrayList<>(typeSet);
        Collections.sort(types);
        return types;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncMetadataToDict(Long orgId) throws Exception {
        List<String> allTypes = getAllMetadataTypes(orgId);
        checkAndCreateDictType();
        SysDictData query = new SysDictData();
        query.setDictType(DICT_TYPE_KEY);
        List<SysDictData> existingList = dictDataService.selectDictDataList(query);
        Map<String, SysDictData> existMap = new HashMap<>();
        for (SysDictData data : existingList) existMap.put(data.getDictValue(), data);
        long sortOrder = existingList.size() + 10;
        for (String apiName : allTypes) {
            if (existMap.containsKey(apiName)) continue;
            SysDictData newData = new SysDictData();
            newData.setDictSort(sortOrder++);
            newData.setDictLabel(apiName);
            newData.setDictValue(apiName);
            newData.setDictType(DICT_TYPE_KEY);
            newData.setStatus("0");
            dictDataService.insertDictData(newData);
        }
    }

    private void checkAndCreateDictType() {
        if (dictTypeService.selectDictTypeByType(DICT_TYPE_KEY) == null) {
            SysDictType newType = new SysDictType();
            newType.setDictName("Salesforce元数据类型");
            newType.setDictType(DICT_TYPE_KEY);
            dictTypeService.insertDictType(newType);
        }
    }

    @Override
    public void clearCacheForOrg(Long orgId) {
        if (orgId == null) return;
        String pattern = REDIS_META_KEY_PREFIX + orgId + ":*";
        Collection<String> keys = redisCache.keys(pattern);
        if (keys != null && !keys.isEmpty()) redisCache.deleteObject(keys);
    }
}