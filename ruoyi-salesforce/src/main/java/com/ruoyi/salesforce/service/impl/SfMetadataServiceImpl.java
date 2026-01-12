package com.ruoyi.salesforce.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDiffVo;
import com.ruoyi.salesforce.service.ISfAuthService;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SfMetadataServiceImpl implements ISfMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SfMetadataServiceImpl.class);

    @Autowired private ISfAuthService sfAuthService;
    @Autowired
    private ISfOrgService sfOrgService;
    @Autowired
    private ISysDictTypeService dictTypeService;
    @Autowired
    private ISysDictDataService dictDataService;
    @Autowired
    private RedisCache redisCache;

    // 内容缓存 Key 前缀
    private static final String REDIS_CONTENT_KEY_PREFIX = "sf:content:";
    // 内容缓存时间：24小时 (Integer 类型适配 RuoYi RedisUtil)
    private static final Integer CONTENT_CACHE_TTL = 24;

    private static final String REDIS_META_KEY_PREFIX = "sf:meta:v3:";
    private static final long CACHE_TTL_MINUTES = 30;
    private static final String DICT_TYPE_KEY = "sys_salesforce_metadata_type";

    @PostConstruct
    public void init() {
        log.info(">>> SfMetadataServiceImpl (Retry & Lock Fixed) 已加载 <<<");
    }

    // =========================================================================
    // 1. 核心连接与重试机制 (修复重点)
    // =========================================================================

    /**
     * 定义一个操作接口，用于 Lambda 包装
     */
    @FunctionalInterface
    public interface SfOperation<T> {
        T execute() throws Exception;
    }


    @Override
    public MetadataConnection getMetadataConnection(Long orgId) throws ConnectionException {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if(org == null) throw new ConnectionException("未找到ID为 " + orgId + " 的配置");
        if(StringUtils.isEmpty(org.getAccessToken())) throw new ConnectionException("环境未授权，请先进行授权");

        // 【优化】移除主动校验 verifyAndRefreshSession，依赖 executeWithRetry 的被动重试

        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(org.getAccessToken());
        config.setServiceEndpoint(org.getInstanceUrl() + "/services/Soap/m/58.0");
        config.setConnectionTimeout(60000); // 60s 连接超时
        config.setReadTimeout(600000);      // 10分钟 读取超时
        return new MetadataConnection(config);
    }


    // =========================================================================
    // 2. 业务功能实现 (全部接入 executeWithRetry)
    // =========================================================================

    @Override
    @Async
    public void preloadMetadata(Long orgId, List<SfDeploymentItem> items) {
        if(items == null || items.isEmpty()) return;
        log.info("开始预加载元数据内容，OrgId: {}, 数量: {}", orgId, items.size());

        items.parallelStream().forEach(item -> {
            try {
                // 强制刷新缓存
                retrieveMetadataInternal(orgId, item.getMetadataType(), item.getMemberName(), true);
            } catch(Exception e) {
                log.warn("预加载失败: {} - {}", item.getMemberName(), e.getMessage());
            }
        });
    }

    @Override
    public String retrieveMetadata(Long orgId, String type, String memberName) throws Exception {
        return retrieveMetadataInternal(orgId, type, memberName, false);
    }

    private String retrieveMetadataInternal(Long orgId, String type, String memberName, boolean forceRefresh) throws Exception {
        String cacheKey = REDIS_CONTENT_KEY_PREFIX + orgId + ":" + type + ":" + memberName;

        if(!forceRefresh) {
            String cachedContent = redisCache.getCacheObject(cacheKey);
            if(StringUtils.isNotEmpty(cachedContent)) {
                return cachedContent;
            }
        }

        // 【应用重试机制】
        String content = sfAuthService.executeWithRetry(orgId, () -> {
            return doRetrieveMetadata(orgId, type, memberName);
        });

        if(content != null && content.length() > 0 && !content.startsWith("Error")) {
            // CONTENT_CACHE_TTL 必须是 Integer
            redisCache.setCacheObject(cacheKey, content, CONTENT_CACHE_TTL, TimeUnit.HOURS);
        }
        return content;
    }

    private String doRetrieveMetadata(Long orgId, String type, String memberName) throws Exception {
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

        if(result.getStatus() != RetrieveStatus.Succeeded) throw new Exception(result.getErrorMessage());
        return smartExtract(result.getZipFile(), type, memberName);
    }

    @Override
    public SfDiffVo compareMetadata(Long sId, Long tId, String type, String name) throws Exception {
        CompletableFuture<String> sourceFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadataInternal(sId, type, name, false);
            } catch(Exception e) {
                log.error("源环境获取失败: {}", e.getMessage());
                return "Error: " + e.getMessage();
            }
        });

        CompletableFuture<String> targetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadataInternal(tId, type, name, true);
            } catch(Exception e) {
                return "";
            }
        });

        CompletableFuture.allOf(sourceFuture, targetFuture).join();
        return new SfDiffVo(sourceFuture.get(), targetFuture.get());
    }

    @Override
    public List<FileProperties> listMetadata(Long orgId, String type) throws Exception {
        String cacheKey = REDIS_META_KEY_PREFIX + orgId + ":" + type;
        try {
            Object cacheObj = redisCache.getCacheList(cacheKey);
            if(cacheObj instanceof List) {
                List<?> rawList = (List<?>) cacheObj;
                if(!rawList.isEmpty()) {
                    String jsonString = JSON.toJSONString(rawList);
                    List<FileProperties> convertedList = JSON.parseArray(jsonString, FileProperties.class);
                    if(!convertedList.isEmpty() && convertedList.get(0).getFullName() != null) return convertedList;
                }
            }
        } catch(Exception e) {
        }

        return refreshMetadataCache(orgId, type);
    }

    @Override
    public List<FileProperties> refreshMetadataCache(Long orgId, String type) throws Exception {
        // 【应用重试机制】
        List<FileProperties> list = sfAuthService.executeWithRetry(orgId, () -> {
            MetadataConnection conn = getMetadataConnection(orgId);
            ListMetadataQuery q = new ListMetadataQuery();
            q.setType(type);
            FileProperties[] res = conn.listMetadata(new ListMetadataQuery[]{q}, 58.0);
            List<FileProperties> result = new ArrayList<>();
            if(res != null) for(FileProperties f : res) if(f.getFullName() != null) result.add(f);
            return result;
        });

        list.sort((a, b) -> {
            if(a.getLastModifiedDate() == null) return 1;
            if(b.getLastModifiedDate() == null) return -1;
            return b.getLastModifiedDate().compareTo(a.getLastModifiedDate());
        });

        if(!list.isEmpty()) {
            String cacheKey = REDIS_META_KEY_PREFIX + orgId + ":" + type;
            redisCache.deleteObject(cacheKey);
            redisCache.setCacheList(cacheKey, list);
            redisCache.expire(cacheKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }
        return list;
    }

    @Override
    public byte[] retrieveZipByManifest(Long orgId, com.sforce.soap.metadata.Package manifest) throws Exception {
        // 【应用重试机制】
        return sfAuthService.executeWithRetry(orgId, () -> {
            MetadataConnection conn = getMetadataConnection(orgId);
            RetrieveRequest req = new RetrieveRequest();
            req.setApiVersion(58.0);
            req.setUnpackaged(manifest);
            AsyncResult ar = conn.retrieve(req);
            RetrieveResult rr = waitForRetrieve(conn, ar.getId());
            if(rr.getStatus() == RetrieveStatus.Succeeded) return rr.getZipFile();
            throw new Exception(rr.getErrorMessage());
        });
    }

    @Override
    public AsyncResult deployZip(Long orgId, byte[] zipData, DeployOptions options) throws Exception {
        // 【应用重试机制】
        return sfAuthService.executeWithRetry(orgId, () -> {
            return getMetadataConnection(orgId).deploy(zipData, options);
        });
    }

    @Override
    public String checkDeployStatus(Long orgId, String processId) throws Exception {
        // 【应用重试机制】
        return sfAuthService.executeWithRetry(orgId, () -> {
            return doCheckDeployStatus(orgId, processId);
        });
    }

    // 抽取出具体的 check 逻辑
    private String doCheckDeployStatus(Long orgId, String processId) throws Exception {
        try {
            MetadataConnection connection = getMetadataConnection(orgId);
            DeployResult result = connection.checkDeployStatus(processId, false);

            if(result.isDone()) {
                try {
                    result = connection.checkDeployStatus(processId, true);
                } catch(Exception e) {
                    log.warn("拉取详情失败，降级显示: {}", e.getMessage());
                }
            }

            JSONObject json = new JSONObject();
            json.put("id", result.getId());
            json.put("done", result.isDone());

            // 状态修正
            String status = result.getStatus().name();
            if(result.isDone() && "Succeeded".equals(status) && result.isCheckOnly()) {
                status = "Validated";
            }
            json.put("status", status);
            json.put("checkOnly", result.isCheckOnly());
            json.put("numberComponentsDeployed", result.getNumberComponentsDeployed());
            json.put("numberComponentsTotal", result.getNumberComponentsTotal());
            json.put("numberTestsCompleted", result.getNumberTestsCompleted());
            json.put("numberTestsTotal", result.getNumberTestsTotal());
            json.put("errorMessage", result.getErrorMessage());
            json.put("stateDetail", result.getStateDetail());

            if(result.getDetails() != null) {
                JSONObject details = new JSONObject();

                // 1. 处理组件失败
                if(result.getDetails().getComponentFailures() != null) {
                    JSONArray failures = new JSONArray();
                    for(DeployMessage msg : result.getDetails().getComponentFailures()) {
                        JSONObject f = new JSONObject();
                        f.put("fileName", msg.getFileName());
                        f.put("problem", msg.getProblem());
                        f.put("lineNumber", msg.getLineNumber());
                        f.put("columnNumber", msg.getColumnNumber());
                        failures.add(f);
                    }
                    details.put("componentFailures", failures);
                }

                // 2. 处理测试结果
                if(result.getDetails().getRunTestResult() != null) {
                    JSONObject testRes = new JSONObject();
                    RunTestsResult sfRunRes = result.getDetails().getRunTestResult();
                    testRes.put("numFailures", sfRunRes.getNumFailures());

                    // 2.1 测试用例失败
                    if(sfRunRes.getFailures() != null) {
                        JSONArray testFailures = new JSONArray();
                        for(RunTestFailure fail : sfRunRes.getFailures()) {
                            JSONObject t = new JSONObject();
                            t.put("name", fail.getName());
                            t.put("methodName", fail.getMethodName());
                            t.put("message", fail.getMessage());
                            testFailures.add(t);
                        }
                        testRes.put("failures", testFailures);
                    }

                    // =========================【修复开始】=========================
                    // 2.2 增加代码覆盖率警告提取 (Code Coverage Warnings)
                    if(sfRunRes.getCodeCoverageWarnings() != null) {
                        JSONArray codeWarnings = new JSONArray();
                        for(CodeCoverageWarning warning : sfRunRes.getCodeCoverageWarnings()) {
                            JSONObject w = new JSONObject();
                            // Salesforce 返回的覆盖率错误信息通常在 message 字段中
                            w.put("message", warning.getMessage());
                            w.put("name", warning.getName()); // 关联的类名（可能为空）
                            codeWarnings.add(w);
                        }
                        testRes.put("codeCoverageWarnings", codeWarnings);
                    }
                    // =========================【修复结束】=========================

                    details.put("runTestResult", testRes);
                }
                json.put("details", details);
            }
            return json.toString();
        } catch(Throwable t) {
            if(t instanceof Exception && sfAuthService.isSessionExpired((Exception) t)) {
                throw (Exception) t;
            }
            JSONObject errorJson = new JSONObject();
            errorJson.put("done", true);
            errorJson.put("status", "Failed");
            errorJson.put("errorMessage", "系统错误: " + t.getMessage());
            return errorJson.toString();
        }
    }

    private RetrieveResult waitForRetrieve(MetadataConnection connection, String id) throws Exception {
        int maxPolls = 600;
        int sleepMillis = 1000;
        for(int i = 0; i < maxPolls; i++) {
            RetrieveResult res = connection.checkRetrieveStatus(id, true);
            if(res.isDone()) return res;
            Thread.sleep(sleepMillis);
        }
        throw new Exception("Salesforce Retrieve request timed out (waited 10 mins).");
    }

    // ... (deployRecentValidation, getAllMetadataTypes, syncMetadataToDict 等保持原样，但也建议加上 executeWithRetry) ...
    @Override
    public String deployRecentValidation(Long orgId, String validationId) throws Exception {
        return sfAuthService.executeWithRetry(orgId, () -> {
            return getMetadataConnection(orgId).deployRecentValidation(validationId);
        });
    }

    @Override
    public List<String> getAllMetadataTypes(Long orgId) throws Exception {
        return sfAuthService.executeWithRetry(orgId, () -> {
            MetadataConnection conn = getMetadataConnection(orgId);
            Set<String> typeSet = new HashSet<>();
            try {
                DescribeMetadataResult result = conn.describeMetadata(58.0);
                if(result != null && result.getMetadataObjects() != null) {
                    for(DescribeMetadataObject obj : result.getMetadataObjects()) {
                        typeSet.add(obj.getXmlName());
                        if(obj.getChildXmlNames() != null) Collections.addAll(typeSet, obj.getChildXmlNames());
                    }
                }
            } catch(Exception e) {
            }
            List<String> must = Arrays.asList("ApexClass", "ApexTrigger", "CustomObject", "CustomField", "PermissionSet", "Profile");
            typeSet.addAll(must);
            List<String> types = new ArrayList<>(typeSet);
            Collections.sort(types);
            return types;
        });
    }

    @Override
    public void syncMetadataToDict(Long orgId) throws Exception {
        // syncMetadataToDict 调用了 getAllMetadataTypes，那里已经有 retry 了
        List<String> allTypes = getAllMetadataTypes(orgId);
        checkAndCreateDictType();
        SysDictData query = new SysDictData();
        query.setDictType(DICT_TYPE_KEY);
        List<SysDictData> existing = dictDataService.selectDictDataList(query);
        Map<String, SysDictData> existMap = new HashMap<>();
        for(SysDictData d : existing) existMap.put(d.getDictValue(), d);
        long sort = existing.size() + 10;
        for(String api : allTypes) {
            if(existMap.containsKey(api)) continue;
            SysDictData nd = new SysDictData();
            nd.setDictSort(sort++);
            nd.setDictLabel(api);
            nd.setDictValue(api);
            nd.setDictType(DICT_TYPE_KEY);
            nd.setStatus("0");
            dictDataService.insertDictData(nd);
        }
    }

    private void checkAndCreateDictType() {
        if(dictTypeService.selectDictTypeByType(DICT_TYPE_KEY) == null) {
            SysDictType nt = new SysDictType();
            nt.setDictName("Salesforce元数据类型");
            nt.setDictType(DICT_TYPE_KEY);
            dictTypeService.insertDictType(nt);
        }
    }

    @Override
    public void clearCacheForOrg(Long orgId) {
        if(orgId == null) return;
        Collection<String> keys = redisCache.keys(REDIS_META_KEY_PREFIX + orgId + ":*");
        if(keys != null && !keys.isEmpty()) redisCache.deleteObject(keys);
    }

    private String smartExtract(byte[] zipData, String type, String memberName) throws Exception {
        if(zipData == null || zipData.length == 0) return "No content.";
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if(entry.isDirectory() || name.endsWith("package.xml")) continue;

                boolean match = false;
                if("CustomLabel".equals(type) && name.endsWith("labels/CustomLabels.labels")) match = true;
                else if("Profile".equals(type) && name.endsWith(".profile")) match = true;
                else if("PermissionSet".equals(type) && name.endsWith(".permissionset")) match = true;
                else if("Layout".equals(type) && name.endsWith(".layout")) match = true;
                else if(isObjectChild(type) && memberName.contains(".")) {
                    if(name.endsWith("objects/" + memberName.split("\\.")[0] + ".object")) match = true;
                } else if(isWorkflowChild(type) && memberName.contains(".")) {
                    if(name.endsWith("workflows/" + memberName.split("\\.")[0] + ".workflow")) match = true;
                } else if(isBundleType(type)) {
                    if(name.contains(memberName)) match = true;
                } else if(name.contains(memberName)) match = true;

                if(match) {
                    found = true;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int len;
                    while((len = zis.read(buf)) > 0) bos.write(buf, 0, len);
                    String content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    if(isBundleType(type))
                        sb.append("/* File: ").append(name).append(" */\n").append(content).append("\n\n");
                    else return content;
                }
            }
        }
        if(!found) return "File not found.";
        return sb.toString();
    }

    private boolean isObjectChild(String type) {
        return Arrays.asList("CustomField", "WebLink", "ValidationRule", "RecordType", "ListView", "FieldSet", "CompactLayout", "BusinessProcess", "Index", "SharingReason").contains(type);
    }

    private boolean isWorkflowChild(String type) {
        return Arrays.asList("WorkflowRule", "WorkflowAlert", "WorkflowFieldUpdate", "WorkflowOutboundMessage", "WorkflowTask").contains(type);
    }

    private boolean isBundleType(String type) {
        return "LightningComponentBundle".equals(type) || "AuraDefinitionBundle".equals(type);
    }

    @Override
    public void cancelDeploy(Long orgId, String processId) throws Exception {
        sfAuthService.executeWithRetry(orgId, () -> {
            MetadataConnection connection = getMetadataConnection(orgId);
            // 调用 Salesforce 原生取消接口
            connection.cancelDeploy(processId);
            return null; // Void return
        });
    }
}