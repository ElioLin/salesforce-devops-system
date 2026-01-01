package com.ruoyi.salesforce.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSON;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // 简单的内存缓存: Key = orgId_metadataType
    private static final Map<String, List<FileProperties>> METADATA_CACHE = new ConcurrentHashMap<>();

    // 定义字典类型 Key
    private static final String DICT_TYPE_KEY = "sys_salesforce_metadata_type";

    // 【新增】注入 RedisCache
    @Autowired
    private RedisCache redisCache;

    // 【新增】定义 Redis Key 前缀 (建议加在常量类中，这里为了方便直接写)
    private static final String REDIS_META_KEY_PREFIX = "sf:meta:";
    // 【新增】缓存过期时间 (分钟)
    private static final long CACHE_TTL_MINUTES = 30;

    /**
     * 获取 Metadata API 连接 (带自动重连/刷新Token功能)
     */
    @Override
    public MetadataConnection getMetadataConnection(Long orgId) throws ConnectionException {
        // 1. 从数据库查 Org 信息
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if (org == null) {
            throw new ConnectionException("未找到ID为 " + orgId + " 的Salesforce环境配置！");
        }
        if (StringUtils.isEmpty(org.getAccessToken()) || StringUtils.isEmpty(org.getInstanceUrl())) {
            throw new ConnectionException("环境 [" + org.getName() + "] 尚未授权，请先前往环境管理进行授权。");
        }

        // 2. 【核心优化】: 验证会话有效性，失效则自动刷新
        verifyAndRefreshSession(org);

        // 3. 配置连接信息 (使用可能已更新的 Token)
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(org.getAccessToken());
        // 增加超时设置，防止大数据量拉取断开
        config.setConnectionTimeout(30000);
        config.setReadTimeout(120000);

        String metadataEndpoint = org.getInstanceUrl() + "/services/Soap/m/58.0";
        config.setServiceEndpoint(metadataEndpoint);

        return new MetadataConnection(config);
    }

    /**
     * 【核心方法】验证 Session 是否有效
     * 使用 MetadataConnection.describeMetadata 替代 EnterpriseConnection，避免引入新依赖
     */
    private void verifyAndRefreshSession(SfOrg org) throws ConnectionException {
        try {
            // 构造一个临时的连接配置用于测试
            ConnectorConfig testConfig = new ConnectorConfig();
            testConfig.setSessionId(org.getAccessToken());
            testConfig.setServiceEndpoint(org.getInstanceUrl() + "/services/Soap/m/58.0");

            MetadataConnection testConn = new MetadataConnection(testConfig);

            // 调用一个轻量级 Metadata API 方法来验证 Session
            // 如果 Session 无效，这里会抛出 ConnectionException
            testConn.describeMetadata(58.0);

        } catch (ConnectionException e) {
            // 判断是否为 Session 过期异常
            if (isSessionExpired(e)) {
                log.info("检测到 Org [{}] Session 已过期，正在执行自动续期...", org.getName());
                try {
                    // 执行刷新逻辑
                    refreshAccessToken(org);
                    log.info("Org [{}] 自动续期成功！", org.getName());
                } catch (Exception refreshEx) {
                    log.error("自动续期失败", refreshEx);
                    throw new ConnectionException("Salesforce授权已过期且自动续期失败：" + refreshEx.getMessage() + "，请尝试手动重新授权。");
                }
            } else {
                // 如果是其他网络错误，直接抛出
                log.error("Salesforce 连接验证异常", e);
                throw e;
            }
        }
    }

    /**
     * 【修复】判断异常是否由 Session 过期引起
     * 移除了 getExceptionCode() 调用，仅使用字符串匹配，解决编译红字问题
     */
    private boolean isSessionExpired(ConnectionException e) {
        String msg = e.getMessage();
        if (StringUtils.isEmpty(msg)) {
            return false;
        }
        // 匹配 Salesforce 常见的过期提示
        return msg.contains("INVALID_SESSION_ID") ||
                msg.contains("Session expired") ||
                msg.contains("Session not found") ||
                msg.contains("Full authentication is required");
    }

    /**
     * 执行 Refresh Token 流程
     * 使用 Org 中存储的 ClientId 和 ClientSecret
     */
    private void refreshAccessToken(SfOrg org) {
        // 1. 校验必要参数
        if (StringUtils.isEmpty(org.getRefreshToken())) {
            throw new ServiceException("缺少 Refresh Token，无法自动续期。请先进行一次完整的手动授权。");
        }
        if (StringUtils.isEmpty(org.getClientId()) || StringUtils.isEmpty(org.getClientSecret())) {
            throw new ServiceException("自动续期失败：环境配置中缺失 Client ID 或 Client Secret，请在环境管理页面补充这些必填项。");
        }

        // 2. 确定认证端点
        String instance = "Sandbox".equalsIgnoreCase(org.getOrgType()) ?
                "https://test.salesforce.com" : "https://login.salesforce.com";
        String tokenUrl = instance + "/services/oauth2/token";

        // 3. 发送刷新请求 (使用 Hutool HttpRequest)
        String result = HttpRequest.post(tokenUrl)
                .form("grant_type", "refresh_token")
                .form("client_id", org.getClientId())         // 使用 Org 配置的 ID
                .form("client_secret", org.getClientSecret()) // 使用 Org 配置的 Secret
                .form("refresh_token", org.getRefreshToken())
                .execute()
                .body();

        JSONObject json = JSONUtil.parseObj(result);

        if (json.getStr("access_token") != null) {
            // 4. 刷新成功，更新内存对象和数据库
            String newAccessToken = json.getStr("access_token");
            String newInstanceUrl = json.getStr("instance_url");

            org.setAccessToken(newAccessToken);
            if (newInstanceUrl != null) {
                org.setInstanceUrl(newInstanceUrl);
            }

            sfOrgService.updateSfOrg(org);
        } else {
            String error = json.getStr("error");
            String errorDesc = json.getStr("error_description");
            throw new ServiceException("Salesforce 拒绝了刷新请求: " + error + " - " + errorDesc);
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
            for (FileProperties file : results) {
                if (file.getFullName() != null) {
                    classNames.add(file.getFullName());
                }
            }
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

        if (result.getStatus() != RetrieveStatus.Succeeded) {
            throw new Exception("Retrieve failed: " + result.getErrorMessage());
        }

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
                    if (entryName.endsWith("objects/" + objName + ".object")) {
                        isMatch = true;
                    }
                } else if (isWorkflowChild(type) && memberName.contains(".")) {
                    String objName = memberName.split("\\.")[0];
                    if (entryName.endsWith("workflows/" + objName + ".workflow")) {
                        isMatch = true;
                    }
                } else if (isBundleType(type) && entryName.contains(memberName)) {
                    isMatch = true;
                } else if (entryName.contains(memberName)) {
                    isMatch = true;
                }

                if (isMatch) {
                    found = true;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                    String fileContent = new String(bos.toByteArray(), StandardCharsets.UTF_8);

                    if (isBundleType(type)) {
                        contentBuilder.append("/* --- File: ").append(entryName).append(" --- */\n");
                        contentBuilder.append(fileContent).append("\n\n");
                    } else {
                        if (!entryName.endsWith("-meta.xml") || entryName.endsWith(".object") || entryName.endsWith(".workflow")) {
                            return fileContent;
                        }
                        if (contentBuilder.length() == 0) {
                            contentBuilder.append(fileContent);
                        }
                    }
                }
            }
        }

        if (!found) {
            return "Error: File not found in retrieved package. (Type: " + type + ", Name: " + memberName + ")";
        }
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

    /**
     * 【重写】获取元数据列表（优先读 Redis 缓存）
     */
    @Override
    public List<FileProperties> listMetadata(Long orgId, String type) throws Exception {
        String cacheKey = getCacheKey(orgId, type);

        // 1. 尝试从 Redis 获取
        List<FileProperties> cacheList = redisCache.getCacheList(cacheKey);

        if (cacheList != null && !cacheList.isEmpty()) {
            // log.info("命中Redis缓存: {}", cacheKey); // 调试时可开启
            return cacheList;
        }

        // 2. 缓存未命中，执行同步并写入缓存
        log.info("Redis缓存未命中，正在从 Salesforce 拉取: {}", cacheKey);
        return refreshMetadataCache(orgId, type);
    }

    // 【新增】生成规范的 Redis Key
    private String getCacheKey(Long orgId, String type) {
        return REDIS_META_KEY_PREFIX + orgId + ":" + type;
    }

    /**
     * 【重写】强制从 Salesforce 同步元数据并更新 Redis 缓存
     */
    @Override
    public List<FileProperties> refreshMetadataCache(Long orgId, String type) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        ListMetadataQuery query = new ListMetadataQuery();
        query.setType(type);

        // 调用 Salesforce API (耗时操作)
        FileProperties[] results = connection.listMetadata(new ListMetadataQuery[]{query}, 58.0);

        List<FileProperties> list = new ArrayList<>();
        if (results != null) {
            for (FileProperties f : results) {
                if (f.getFullName() != null) list.add(f);
            }
        }
        // 排序
        list.sort((a, b) -> b.getLastModifiedDate().compareTo(a.getLastModifiedDate()));

        // 【新增】存入 Redis，设置过期时间
        String cacheKey = getCacheKey(orgId, type);
        if (!list.isEmpty()) {
            redisCache.setCacheList(cacheKey, list);
            redisCache.expire(cacheKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.info("已更新Redis缓存: {}, 条数: {}", cacheKey, list.size());
        } else {
            // 如果列表为空，也建议缓存一个空列表（时间稍短），防止缓存穿透
            redisCache.setCacheList(cacheKey, new ArrayList<>());
            redisCache.expire(cacheKey, 5, TimeUnit.MINUTES);
        }

        return list;
    }

    @Override
    public SfDiffVo compareMetadata(Long sourceOrgId, Long targetOrgId, String type, String memberName) throws Exception {
        CompletableFuture<String> sourceFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadata(sourceOrgId, type, memberName);
            } catch (Exception e) {
                throw new RuntimeException("源环境读取失败: " + e.getMessage());
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

        if (result.getStatus() == RetrieveStatus.Succeeded) {
            return result.getZipFile();
        } else {
            throw new Exception("Retrieve failed: " + result.getErrorMessage());
        }
    }

    @Override
    public AsyncResult deployZip(Long orgId, byte[] zipData, DeployOptions options) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        return connection.deploy(zipData, options);
    }

    @Override
    public String checkDeployStatus(Long orgId, String processId) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        DeployResult result = connection.checkDeployStatus(processId, true);
        return JSON.toJSONString(result);
    }

    private RetrieveResult waitForRetrieve(MetadataConnection connection, String id) throws Exception {
        int maxPolls = 60;
        int sleepMillis = 500;

        for (int i = 0; i < maxPolls; i++) {
            RetrieveResult result = connection.checkRetrieveStatus(id, true);
            if (result.isDone()) {
                return result;
            }
            Thread.sleep(sleepMillis);
        }
        throw new Exception("Retrieve request timed out.");
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
            if (result != null && result.getMetadataObjects() != null) {
                for (DescribeMetadataObject obj : result.getMetadataObjects()) {
                    typeSet.add(obj.getXmlName());
                    if (obj.getChildXmlNames() != null) {
                        Collections.addAll(typeSet, obj.getChildXmlNames());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: describeMetadata failed, using fallback list. " + e.getMessage());
        }

        List<String> mustHaveTypes = Arrays.asList(
                "AuraDefinitionBundle", "LightningComponentBundle", "ApexClass", "ApexTrigger", "ApexPage",
                "ApexComponent", "StaticResource", "CustomObject", "CustomField", "WebLink", "ValidationRule",
                "RecordType", "ListView", "FieldSet", "CompactLayout", "BusinessProcess", "Index",
                "PermissionSet", "Profile", "Role", "Group", "Queue", "CustomTab", "CustomApplication",
                "CustomLabel", "CustomMetadata", "RemoteSiteSetting", "CspTrustedSite", "NamedCredential",
                "Flow", "FlowDefinition", "Workflow", "WorkflowRule", "WorkflowAlert", "WorkflowFieldUpdate",
                "WorkflowOutboundMessage", "ApprovalProcess", "Report", "Dashboard", "EmailTemplate"
        );
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

        Map<String, String> friendlyNameMap = new HashMap<>();
        friendlyNameMap.put("LightningComponentBundle", "Lightning Web Components (LWC)");
        friendlyNameMap.put("AuraDefinitionBundle", "Lightning Component Bundle");
        friendlyNameMap.put("ApexClass", "Apex Class");
        friendlyNameMap.put("ApexTrigger", "Apex Trigger");
        friendlyNameMap.put("CustomObject", "Custom Object");
        friendlyNameMap.put("CustomField", "Custom Field");
        friendlyNameMap.put("Flow", "Flows");
        friendlyNameMap.put("PermissionSet", "Permission Set");
        friendlyNameMap.put("Profile", "Profile");

        SysDictData query = new SysDictData();
        query.setDictType(DICT_TYPE_KEY);
        List<SysDictData> existingList = dictDataService.selectDictDataList(query);

        Map<String, SysDictData> existMap = new HashMap<>();
        for (SysDictData data : existingList) {
            existMap.put(data.getDictValue(), data);
        }

        long sortOrder = existingList.size() + 10;

        for (String apiName : allTypes) {
            if (existMap.containsKey(apiName)) {
                continue;
            }
            SysDictData newData = new SysDictData();
            newData.setDictSort(sortOrder++);
            newData.setDictLabel(friendlyNameMap.getOrDefault(apiName, apiName));
            newData.setDictValue(apiName);
            newData.setDictType(DICT_TYPE_KEY);
            newData.setStatus("0");
            newData.setIsDefault("N");
            newData.setCreateBy(SecurityUtils.getUsername());
            newData.setRemark("Auto synced from Salesforce");
            dictDataService.insertDictData(newData);
        }
    }

    private void checkAndCreateDictType() {
        SysDictType dictType = dictTypeService.selectDictTypeByType(DICT_TYPE_KEY);
        if (dictType == null) {
            SysDictType newType = new SysDictType();
            newType.setDictName("Salesforce元数据类型");
            newType.setDictType(DICT_TYPE_KEY);
            newType.setStatus("0");
            newType.setCreateBy(SecurityUtils.getUsername());
            newType.setRemark("用于Salesforce部署模块的元数据类型选择");
            dictTypeService.insertDictType(newType);
        }
    }

    /**
     * 【新增】清除指定 Org 的所有元数据缓存
     * 模式：sf:meta:{orgId}:*
     */
    @Override
    public void clearCacheForOrg(Long orgId) {
        if (orgId == null) return;

        // 构造匹配模式，例如 sf:meta:1001:*
        String pattern = REDIS_META_KEY_PREFIX + orgId + ":*";

        // 获取所有匹配的 Key
        Collection<String> keys = redisCache.keys(pattern);

        if (keys != null && !keys.isEmpty()) {
            redisCache.deleteObject(keys);
            log.info("已清理 Org [{}] 的元数据缓存，共 {} 个 Key", orgId, keys.size());
        }
    }
}