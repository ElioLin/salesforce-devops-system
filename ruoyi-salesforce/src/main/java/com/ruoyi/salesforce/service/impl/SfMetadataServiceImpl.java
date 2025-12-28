package com.ruoyi.salesforce.service.impl;

import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import com.ruoyi.salesforce.utils.SfZipUtils;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDiffVo;


import com.sforce.soap.metadata.*;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
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

@Service
public class SfMetadataServiceImpl implements ISfMetadataService {
    @Autowired
    private ISfOrgService sfOrgService;

    // 【新增】简单的内存缓存: Key = orgId_metadataType, Value = List<FileProperties>
    // 注意：在集群环境下建议使用 Redis，这里为了简化架构直接用内存 Map
    private static final Map<String, List<FileProperties>> METADATA_CACHE = new ConcurrentHashMap<>();

    /**
     * 获取 Metadata API 连接
     */
    @Override
    public MetadataConnection getMetadataConnection(Long orgId) throws ConnectionException {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if(org == null || org.getAccessToken() == null) {
            throw new ConnectionException("环境未配置或未授权，请先去绑定Org！");
        }
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(org.getAccessToken());
        String metadataEndpoint = org.getInstanceUrl() + "/services/Soap/m/58.0";
        config.setServiceEndpoint(metadataEndpoint);

        // 【核心优化】设置更长的超时时间 (单位：毫秒)
        // 连接超时 30秒，读取超时 2分钟 (应对 CustomField 这种大量数据)
        config.setConnectionTimeout(30000);
        config.setReadTimeout(120000);

        return new MetadataConnection(config);
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

        if (result.getStatus() != RetrieveStatus.Succeeded) {
            throw new Exception("Retrieve failed: " + result.getErrorMessage());
        }

        // 4. 【智能解析】不再单纯猜后缀，而是遍历 ZIP 包查找匹配文件
        return smartExtract(result.getZipFile(), type, memberName);
    }

    /**
     * 智能解压策略：支持 LWC(多文件)、CustomField(嵌套)、Apex(单文件)
     */
    private String smartExtract(byte[] zipData, String type, String memberName) throws Exception {
        if (zipData == null || zipData.length == 0) return "No content retrieved.";

        StringBuilder contentBuilder = new StringBuilder();
        boolean found = false;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // 忽略目录和 package.xml
                if (entry.isDirectory() || entryName.endsWith("package.xml")) continue;

                boolean isMatch = false;

                // 1. 自定义字段特殊处理: Account.MyField__c -> 位于 objects/Account.object
                if ("CustomField".equals(type) && memberName.contains(".")) {
                    String objName = memberName.split("\\.")[0];
                    if (entryName.endsWith(objName + ".object")) isMatch = true;
                }
                // 2. LWC / Aura Bundle: 路径包含组件名 (如 lwc/myComp/myComp.js)
                else if (isBundleType(type) && entryName.contains(memberName)) {
                    isMatch = true;
                }
                // 3. 通用匹配: 文件名包含元数据名 (如 classes/MyClass.cls)
                else if (entryName.contains(memberName)) {
                    isMatch = true;
                }

                if (isMatch) {
                    found = true;
                    // 读取文件内容
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                    String fileContent = new String(bos.toByteArray(), StandardCharsets.UTF_8);

                    // 如果是 Bundle，拼接多个文件展示
                    if (isBundleType(type)) {
                        contentBuilder.append("/* --- File: ").append(entryName).append(" --- */\n");
                        contentBuilder.append(fileContent).append("\n\n");
                    } else {
                        // 对于单文件，优先返回代码文件，忽略 -meta.xml (除非只有 meta.xml)
                        if (!entryName.endsWith("-meta.xml")) {
                            return fileContent;
                        }
                        // 如果暂只找到 meta.xml，先缓存，万一没别的代码文件就返回它
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
        if (METADATA_CACHE.containsKey(cacheKey)) {
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
            } catch (Exception e) {
                throw new RuntimeException("源环境读取失败: " + e.getMessage());
            }
        });

        // 2. 异步任务：拉取目标环境
        CompletableFuture<String> targetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return retrieveMetadata(targetOrgId, type, memberName);
            } catch (Exception e) {
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
     * 【核心修复】动态获取 Salesforce 支持的所有元数据类型
     * 修改点：遍历 ChildXmlNames，将 CustomField, ValidationRule 等加入列表
     */
    @Override
    public List<String> getAllMetadataTypes(Long orgId) throws Exception {
        MetadataConnection conn = getMetadataConnection(orgId);
        DescribeMetadataResult result = conn.describeMetadata(58.0);

        Set<String> typeSet = new HashSet<>(); // 使用 Set 去重
        if (result != null && result.getMetadataObjects() != null) {
            for (DescribeMetadataObject obj : result.getMetadataObjects()) {
                typeSet.add(obj.getXmlName());

                // 【新增】添加子类型 (如 CustomField, ValidationRule, RecordType 等)
                if (obj.getChildXmlNames() != null) {
                    for (String childName : obj.getChildXmlNames()) {
                        typeSet.add(childName);
                    }
                }
            }
        }

        List<String> types = new ArrayList<>(typeSet);
        Collections.sort(types);
        return types;
    }
}