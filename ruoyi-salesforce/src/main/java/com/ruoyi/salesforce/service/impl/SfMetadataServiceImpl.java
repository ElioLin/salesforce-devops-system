package com.ruoyi.salesforce.service.impl;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.core.domain.entity.SysDictType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import com.ruoyi.salesforce.utils.SfZipUtils;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDiffVo;

import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.service.ISysDictTypeService;
import com.sforce.soap.metadata.*;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.alibaba.fastjson2.JSON;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SfMetadataServiceImpl implements ISfMetadataService {
    @Autowired
    private ISfOrgService sfOrgService;

    // 【新增】简单的内存缓存: Key = orgId_metadataType, Value = List<FileProperties>
    // 注意：在集群环境下建议使用 Redis，这里为了简化架构直接用内存 Map
    private static final Map<String, List<FileProperties>> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(SfMetadataServiceImpl.class);

    // 【新增】注入若依的字典服务
    @Autowired
    private ISysDictTypeService dictTypeService;

    @Autowired
    private ISysDictDataService dictDataService;
    // 定义字典类型 Key
    private static final String DICT_TYPE_KEY = "sys_salesforce_metadata_type";

    /**
     * 获取 Metadata API 连接 (带自动重连/刷新Token功能)
     */
    @Override
    public MetadataConnection getMetadataConnection(Long orgId) throws ConnectionException {
        // 1. 从数据库查 Token
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if(org == null || org.getAccessToken() == null) {
            throw new ConnectionException("环境未配置或未授权，请先去绑定Org！");
        }

        // 【核心优化】: 检查并刷新 Token
        try {
            ensureSessionValid(org);
        } catch(Exception e) {
            log.error("自动刷新Token失败", e);
            throw new ConnectionException("连接Salesforce失败，请尝试重新授权: " + e.getMessage());
        }

        // 2. 配置连接信息
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
     * 【核心方法】保证会话有效
     * 如果当前Token失效，尝试使用RefreshToken刷新，并更新数据库
     */
    private void ensureSessionValid(SfOrg org) {
        // 1. 简单验证：尝试访问 UserInfo 端点
        String userInfoUrl = org.getInstanceUrl() + "/services/oauth2/userinfo";
        try(HttpResponse response = HttpRequest.get(userInfoUrl)
                .header("Authorization", "Bearer " + org.getAccessToken())
                .timeout(5000) // 5秒超时
                .execute()) {

            // 2. 如果返回 401 (Unauthorized)，说明 AccessToken 过期
            if(response.getStatus() == 401) {
                log.info("Org [{}] Token已过期，正在尝试自动刷新...", org.getName());
                refreshAccessToken(org);
            }
        } catch(Exception e) {
            // 网络错误或其他异常，尝试刷新一次试试
            log.warn("验证Token异常，尝试刷新Token: {}", e.getMessage());
            refreshAccessToken(org);
        }
    }

    /**
     * 执行 Refresh Token 流程
     */
    private void refreshAccessToken(SfOrg org) {
        if(org.getRefreshToken() == null) {
            throw new RuntimeException("缺少Refresh Token，无法自动续期，请手动重新授权。");
        }

        String instance = "Production".equalsIgnoreCase(org.getOrgType()) ?
                "https://login.salesforce.com" : "https://test.salesforce.com";
        String tokenUrl = instance + "/services/oauth2/token";

        // 发送刷新请求
        String result = HttpRequest.post(tokenUrl)
                .form("grant_type", "refresh_token")
                .form("client_id", org.getClientId())
                .form("client_secret", org.getClientSecret())
                .form("refresh_token", org.getRefreshToken())
                .execute()
                .body();

        JSONObject json = JSONUtil.parseObj(result);

        if(json.getStr("access_token") != null) {
            // 刷新成功，更新内存对象
            String newAccessToken = json.getStr("access_token");
            String newInstanceUrl = json.getStr("instance_url");

            org.setAccessToken(newAccessToken);
            if(newInstanceUrl != null) {
                org.setInstanceUrl(newInstanceUrl);
            }

            // 更新数据库
            sfOrgService.updateSfOrg(org);
            log.info("Org [{}] Token 自动刷新成功！", org.getName());
        } else {
            String error = json.getStr("error_description");
            throw new RuntimeException("自动刷新Token失败: " + error);
        }
    }

    @Override
    public List<String> testConnection(Long orgId) throws Exception {
        // 1. 建立连接
        MetadataConnection connection = getMetadataConnection(orgId);

        // 2. 构造查询条件：我想列出所有的 ApexClass
        ListMetadataQuery query = new ListMetadataQuery();
        query.setType("ApexClass");
        // query.setFolder(null); // ApexClass 不需要 folder

        // 3. 调用 API (最多一次查3个类型，这里只查1个)
        FileProperties[] results = connection.listMetadata(
                new ListMetadataQuery[]{query},
                58.0 // API 版本
        );

        // 4. 处理结果
        List<String> classNames = new ArrayList<>();
        if(results != null) {
            for(FileProperties file : results) {
                // 排除系统生成的类，只看我们自己的
                if(file.getFullName() != null) {
                    classNames.add(file.getFullName());
                }
                // 为了演示，只取前10个
//                if (classNames.size() >= 10) break;
            }
        }
        return classNames;
    }

    /**
     * 【核心重构】智能读取元数据内容
     * 解决 LWC、CustomField 等文件找不到的问题
     */
    @Override
    public String retrieveMetadata(Long orgId, String type, String memberName) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);

        // 1. 构建请求
        RetrieveRequest retrieveRequest = new RetrieveRequest();
        retrieveRequest.setApiVersion(58.0);
//        retrieveRequest.setSinglePackage(true); // 单包模式，目录结构更扁平

        com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
        PackageTypeMembers typeMember = new PackageTypeMembers();
        typeMember.setName(type);
        typeMember.setMembers(new String[]{memberName});
        manifest.setTypes(new PackageTypeMembers[]{typeMember});
        manifest.setVersion("58.0"); // 显式设置版本

        retrieveRequest.setUnpackaged(manifest);

        // 2. 发起异步调用
        AsyncResult asyncResult = connection.retrieve(retrieveRequest);

        // 3. 等待结果
        RetrieveResult result = waitForRetrieve(connection, asyncResult.getId());

        if(result.getStatus() != RetrieveStatus.Succeeded) {
            throw new Exception("Retrieve failed: " + result.getErrorMessage());
        }

        // 4. 【智能解析】不再单纯猜后缀，而是遍历 ZIP 包查找匹配文件
        return smartExtract(result.getZipFile(), type, memberName);
    }

    /**
     * 【核心修复】智能解压策略
     * 自动处理嵌套在 .object 或 .workflow 文件中的子元数据
     */
    private String smartExtract(byte[] zipData, String type, String memberName) throws Exception {
        if(zipData == null || zipData.length == 0) return "No content retrieved.";

        StringBuilder contentBuilder = new StringBuilder();
        boolean found = false;

        try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if(entry.isDirectory() || entryName.endsWith("package.xml")) continue;

                boolean isMatch = false;

                // 1. 【修复】对象子类型 (CustomField, ValidationRule, RecordType 等)
                // 它们都存在于 objects/ObjectName.object 文件中
                // memberName 格式通常为: ObjectName.FieldName
                if (isObjectChild(type) && memberName.contains(".")) {
                    String objName = memberName.split("\\.")[0];
                    // 匹配文件名是否以 "objects/ObjectName.object" 结尾
                    if (entryName.endsWith("objects/" + objName + ".object")) {
                        isMatch = true;
                    }
                }
                // 2. 【修复】工作流子类型 (WorkflowRule, WorkflowAlert 等)
                // 存在于 workflows/ObjectName.workflow 文件中
                else if (isWorkflowChild(type) && memberName.contains(".")) {
                    String objName = memberName.split("\\.")[0];
                    if (entryName.endsWith("workflows/" + objName + ".workflow")) {
                        isMatch = true;
                    }
                }
                // 3. Bundle 类型 (LWC, Aura)
                else if(isBundleType(type) && entryName.contains(memberName)) {
                    isMatch = true;
                }
                // 4. 标准匹配 (ApexClass, Page 等)
                else if(entryName.contains(memberName)) {
                    isMatch = true;
                }

                if(isMatch) {
                    found = true;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                    String fileContent = new String(bos.toByteArray(), StandardCharsets.UTF_8);

                    if(isBundleType(type)) {
                        contentBuilder.append("/* --- File: ").append(entryName).append(" --- */\n");
                        contentBuilder.append(fileContent).append("\n\n");
                    } else {
                        // 优先返回非 meta 文件，但如果是 .object 或 .workflow 这种单文件，直接返回
                        if(!entryName.endsWith("-meta.xml") || entryName.endsWith(".object") || entryName.endsWith(".workflow")) {
                            return fileContent;
                        }
                        if(contentBuilder.length() == 0) {
                            contentBuilder.append(fileContent);
                        }
                    }
                }
            }
        }

        if(!found) {
            return "Error: File not found in retrieved package. (Type: " + type + ", Name: " + memberName + ")";
        }
        return contentBuilder.toString();
    }

    // 辅助判断：是否为对象子类型
    private boolean isObjectChild(String type) {
        return Arrays.asList(
                "CustomField", "ValidationRule", "RecordType", "WebLink", "ListView", "FieldSet",
                "CompactLayout", "BusinessProcess", "Index", "SharingReason"
        ).contains(type);
    }

    // 辅助判断：是否为工作流子类型
    private boolean isWorkflowChild(String type) {
        return Arrays.asList(
                "WorkflowRule", "WorkflowAlert", "WorkflowFieldUpdate", "WorkflowOutboundMessage", "WorkflowTask"
        ).contains(type);
    }

    private boolean isBundleType(String type) {
        return "LightningComponentBundle".equals(type) || "AuraDefinitionBundle".equals(type);
    }

    /**
     * 获取元数据列表（优先读缓存）
     */
    @Override
    public List<FileProperties> listMetadata(Long orgId, String type) throws Exception {
        String cacheKey = orgId + "_" + type;

        // 1. 如果缓存中有，直接返回（毫秒级响应）
        if(METADATA_CACHE.containsKey(cacheKey)) {
            return METADATA_CACHE.get(cacheKey);
        }

        // 2. 如果缓存没有，执行同步
        return refreshMetadataCache(orgId, type);
    }

    /**
     * 【新增】强制从 Salesforce 同步元数据并更新缓存
     */
    @Override
    public List<FileProperties> refreshMetadataCache(Long orgId, String type) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);
        ListMetadataQuery query = new ListMetadataQuery();
        query.setType(type);

        // 调用 Salesforce API (这是最耗时的一步)
        FileProperties[] results = connection.listMetadata(new ListMetadataQuery[]{query}, 58.0);

        List<FileProperties> list = new ArrayList<>();
        if(results != null) {
            for(FileProperties f : results) {
                if(f.getFullName() != null) list.add(f);
            }
        }
        // 排序
        list.sort((a, b) -> b.getLastModifiedDate().compareTo(a.getLastModifiedDate()));

        // 更新缓存
        String cacheKey = orgId + "_" + type;
        METADATA_CACHE.put(cacheKey, list);

        return list;
    }

    /**
     * 【优化】并行比对元数据
     * 同时发起两个 Retrieve 请求，时间缩短一半
     */
    @Override
    public SfDiffVo compareMetadata(Long sourceOrgId, Long targetOrgId, String type, String memberName) throws Exception {
        // 1. 异步任务：拉取源环境
        CompletableFuture<String> sourceFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadata(sourceOrgId, type, memberName);
            } catch(Exception e) {
                throw new RuntimeException("源环境读取失败: " + e.getMessage());
            }
        });

        // 2. 异步任务：拉取目标环境
        CompletableFuture<String> targetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadata(targetOrgId, type, memberName);
            } catch(Exception e) {
                // 目标环境如果没有这个文件，不应该报错，而是返回空字符串（表示新增）
                return "";
            }
        });

        // 3. 等待两个任务都完成
        CompletableFuture.allOf(sourceFuture, targetFuture).join();

        // 4. 返回结果
        return new SfDiffVo(sourceFuture.get(), targetFuture.get());
    }

    @Override
    public byte[] retrieveZipByManifest(Long orgId, com.sforce.soap.metadata.Package manifest) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);

        RetrieveRequest request = new RetrieveRequest();
        request.setApiVersion(58.0);
        request.setUnpackaged(manifest);
//        request.setSinglePackage(true);

        AsyncResult asyncResult = connection.retrieve(request);

        // 等待拉取完成 (同步等待)
        // 复用之前的 waitForRetrieve 方法
        RetrieveResult result = waitForRetrieve(connection, asyncResult.getId());

        if(result.getStatus() == RetrieveStatus.Succeeded) {
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
        // checkDeployStatus(id, includeDetails)
        DeployResult result = connection.checkDeployStatus(processId, true);

        // 将结果转为 JSON 字符串返回给前端，方便前端解析日志
        return JSON.toJSONString(result);
    }

    /**
     * 私有辅助方法：轮询等待
     */
    private RetrieveResult waitForRetrieve(MetadataConnection connection, String id) throws Exception {
        int maxPolls = 60; // 30秒 (0.5s * 60)
        int sleepMillis = 500;

        for(int i = 0; i < maxPolls; i++) {
            RetrieveResult result = connection.checkRetrieveStatus(id, true); // true = includeZip
            if(result.isDone()) {
                return result;
            }
            Thread.sleep(sleepMillis);
        }
        throw new Exception("Retrieve request timed out.");
    }

    @Override
    public String deployRecentValidation(Long orgId, String validationId) throws Exception {
        MetadataConnection conn = getMetadataConnection(orgId);
        // 调用 Salesforce 原生快速部署接口
        return conn.deployRecentValidation(validationId);
    }

    /**
     * 【核心优化】动态获取 Salesforce 支持的所有元数据类型
     * 优化点：
     * 1. 遍历 ChildXmlNames，将 CustomField, ValidationRule 等加入列表
     * 2. 增加“兜底补全”逻辑，确保 Aura, WebLink, LWC 等关键类型绝对存在
     */
    @Override
    public List<String> getAllMetadataTypes(Long orgId) throws Exception {
        MetadataConnection conn = getMetadataConnection(orgId);

        // 1. 尝试从 Salesforce API 获取动态元数据描述
        Set<String> typeSet = new HashSet<>();
        try {
            DescribeMetadataResult result = conn.describeMetadata(58.0);
            if(result != null && result.getMetadataObjects() != null) {
                for(DescribeMetadataObject obj : result.getMetadataObjects()) {
                    // 添加顶层类型 (如 CustomObject, ApexClass)
                    typeSet.add(obj.getXmlName());

                    // 添加子类型 (如 CustomField, WebLink, ValidationRule)
                    if(obj.getChildXmlNames() != null) {
                        for(String childName : obj.getChildXmlNames()) {
                            typeSet.add(childName);
                        }
                    }
                }
            }
        } catch(Exception e) {
            // 即使 describe 失败，也不应该阻断，继续使用兜底列表
            System.err.println("Warning: describeMetadata failed, using fallback list. " + e.getMessage());
        }

        // 2. 【核心优化】手动补全常用/关键元数据类型 (兜底策略)
        // 解决因 API 版本或权限问题导致 Aura, WebLink 等类型未返回的问题
        List<String> mustHaveTypes = Arrays.asList(
                // --- 代码开发类 ---
                "AuraDefinitionBundle",       // Aura 组件 (Lightning Component)
                "LightningComponentBundle",   // LWC 组件
                "ApexClass",
                "ApexTrigger",
                "ApexPage",
                "ApexComponent",
                "StaticResource",

                // --- 对象与字段类 ---
                "CustomObject",
                "CustomField",
                "WebLink",                    // 按钮或链接 (Buttons or Links)
                "ValidationRule",             // 验证规则
                "RecordType",                 // 记录类型
                "ListView",                   // 列表视图
                "FieldSet",                   // 字段集
                "CompactLayout",              // 紧凑布局
                "BusinessProcess",            // 业务流程
                "Index",                      // 索引

                // --- 权限与配置类 ---
                "PermissionSet",
                "Profile",
                "Role",
                "Group",
                "Queue",
                "CustomTab",
                "CustomApplication",
                "CustomLabel",                // 自定义标签 (注意: 父级是 CustomLabels)
                "CustomMetadata",             // 自定义元数据
                "RemoteSiteSetting",
                "CspTrustedSite",
                "NamedCredential",

                // --- 流程自动化类 ---
                "Flow",                       // Flow (新版)
                "FlowDefinition",             // Flow (旧版定义)
                "Workflow",                   // 工作流容器
                "WorkflowRule",               // 工作流规则
                "WorkflowAlert",              // 工作流警告
                "WorkflowFieldUpdate",        // 字段更新
                "WorkflowOutboundMessage",    // 出站消息
                "ApprovalProcess",            // 审批流程

                // --- 报表与面板 ---
                "Report",
                "Dashboard",
                "EmailTemplate"
        );

        typeSet.addAll(mustHaveTypes);

        // 3. 排序并返回
        List<String> types = new ArrayList<>(typeSet);
        Collections.sort(types);
        return types;
    }

    /**
     * 【新增】同步元数据类型到若依字典
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncMetadataToDict(Long orgId) throws Exception {
        // 1. 获取所有元数据类型 (复用之前的逻辑)
        List<String> allTypes = getAllMetadataTypes(orgId);

        // 2. 检查字典类型是否存在，不存在则创建
        checkAndCreateDictType();

        // 3. 定义预设的友好名称映射 (初始化时使用)
        Map<String, String> friendlyNameMap = new HashMap<>();
        friendlyNameMap.put("LightningComponentBundle", "Lightning Web Components (LWC)");
        friendlyNameMap.put("AuraDefinitionBundle", "Lightning Component Bundle");
        friendlyNameMap.put("Layout", "Page Layout");
        friendlyNameMap.put("FlexiPage", "Lightning Page");
        friendlyNameMap.put("ApexPage", "Visualforce Page (VFP)");
        friendlyNameMap.put("ApexComponent", "Visualforce Component");
        friendlyNameMap.put("CustomObject", "Custom Object");
        friendlyNameMap.put("CustomField", "Custom Field");
        friendlyNameMap.put("WebLink", "Buttons or Links");
        friendlyNameMap.put("ApexClass", "Apex Class");
        friendlyNameMap.put("ApexTrigger", "Apex Trigger");
        friendlyNameMap.put("Flow", "Flows");
        friendlyNameMap.put("PermissionSet", "Permission Set");
        friendlyNameMap.put("Profile", "Profile");
        friendlyNameMap.put("StaticResource", "Static Resource");

        // 4. 获取当前字典已有的数据 (防止覆盖用户已修改的名称)
        SysDictData query = new SysDictData();
        query.setDictType(DICT_TYPE_KEY);
        List<SysDictData> existingList = dictDataService.selectDictDataList(query);

        // 转为 Map 方便比对: Value -> Data
        Map<String, SysDictData> existMap = new HashMap<>();
        for(SysDictData data : existingList) {
            existMap.put(data.getDictValue(), data);
        }

        // 5. 遍历并插入新数据
        long sortOrder = existingList.size() + 10; // 排序号从现有数量后续开始

        for(String apiName : allTypes) {
            // 如果字典里已经有了，就跳过 (保留用户可能手动改过的 Label)
            if(existMap.containsKey(apiName)) {
                continue;
            }

            // 构建新字典项
            SysDictData newData = new SysDictData();
            newData.setDictSort(sortOrder++);
            newData.setDictLabel(friendlyNameMap.getOrDefault(apiName, apiName)); // 有预设用预设，没有用API名
            newData.setDictValue(apiName);
            newData.setDictType(DICT_TYPE_KEY);
            newData.setStatus("0"); // 正常状态
            newData.setIsDefault("N");
            newData.setCreateBy(SecurityUtils.getUsername()); // 获取当前操作人
            newData.setRemark("Auto synced from Salesforce");

            // 插入数据库
            dictDataService.insertDictData(newData);
        }
    }

    /**
     * 辅助方法：检查并创建字典类型
     */
    private void checkAndCreateDictType() {
        SysDictType dictType = dictTypeService.selectDictTypeByType(DICT_TYPE_KEY);
        if(dictType == null) {
            SysDictType newType = new SysDictType();
            newType.setDictName("Salesforce元数据类型");
            newType.setDictType(DICT_TYPE_KEY);
            newType.setStatus("0");
            newType.setCreateBy(SecurityUtils.getUsername());
            newType.setRemark("用于Salesforce部署模块的元数据类型选择");
            dictTypeService.insertDictType(newType);
        }
    }
}