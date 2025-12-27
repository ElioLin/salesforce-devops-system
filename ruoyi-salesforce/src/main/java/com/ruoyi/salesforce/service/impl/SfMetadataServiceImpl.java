package com.ruoyi.salesforce.service.impl;

import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import com.ruoyi.salesforce.utils.SfZipUtils;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDiffVo;


import com.sforce.soap.metadata.*;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.alibaba.fastjson2.JSON;

@Service
public class SfMetadataServiceImpl implements ISfMetadataService {

    @Autowired
    private ISfOrgService sfOrgService;

    /**
     * 获取 Metadata API 连接
     */
    @Override
    public MetadataConnection getMetadataConnection(Long orgId) throws ConnectionException {
        // 1. 从数据库查 Token
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if(org == null || org.getAccessToken() == null) {
            throw new ConnectionException("环境未配置或未授权，请先去绑定Org！");
        }

        // 2. 配置连接信息
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(org.getAccessToken()); // 关键：填入 Access Token

        // 3. 设置 API 端点
        // 注意：instanceUrl 是 https://xxx.my.salesforce.com
        // Metadata API 的地址必须手动拼接为: /services/Soap/m/{version}
        String metadataEndpoint = org.getInstanceUrl() + "/services/Soap/m/58.0";
        config.setServiceEndpoint(metadataEndpoint);

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

    @Override
    public String retrieveMetadata(Long orgId, String type, String memberName) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);

        // 1. 构建 Retrieve 请求
        RetrieveRequest retrieveRequest = new RetrieveRequest();
        retrieveRequest.setApiVersion(58.0);
        retrieveRequest.setSinglePackage(true);

        // 构建 manifest (package.xml)
        com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
        PackageTypeMembers typeMember = new PackageTypeMembers();
        typeMember.setName(type); // 例如 ApexClass
        typeMember.setMembers(new String[]{memberName}); // 例如 MyController
        manifest.setTypes(new PackageTypeMembers[]{typeMember});

        retrieveRequest.setUnpackaged(manifest);

        // 2. 发起异步调用
        AsyncResult asyncResult = connection.retrieve(retrieveRequest);
        String processId = asyncResult.getId();

        // 3. 轮询等待结果 (最多等待 30 秒)
        RetrieveResult result = waitForRetrieve(connection, processId);

        if(result.getStatus() == RetrieveStatus.Succeeded) {
            // 4. 解压并提取代码
            // 根据类型推断文件后缀 (简单版)
            String extension = ".cls";
            if("ApexTrigger".equals(type)) extension = ".trigger";
            if("ApexPage".equals(type)) extension = ".page";
            if("CustomObject".equals(type)) extension = ".object";
            // ... 更多类型可后续补充

            String fileName = memberName + extension;
            return SfZipUtils.extractFileContent(result.getZipFile(), fileName);
        } else {
            throw new Exception("Retrieve failed: " + result.getErrorStatusCode() + " - " + result.getErrorMessage());
        }
    }

    @Override
    public List<FileProperties> listMetadata(Long orgId, String type) throws Exception {
        MetadataConnection connection = getMetadataConnection(orgId);

        ListMetadataQuery query = new ListMetadataQuery();
        query.setType(type);
        // query.setFolder(null); // 大部分 Metadata 不需要 folder，EmailTemplate 等需要，暂时先留空

        // 调用 API
        // Salesforce 限制一次 listMetadata 只能查 3 个 type，我们这里只查 1 个
        FileProperties[] results = connection.listMetadata(
                new ListMetadataQuery[]{query},
                58.0
        );

        List<FileProperties> list = new ArrayList<>();
        if(results != null) {
            for(FileProperties file : results) {
                // 过滤掉未管理的包或者空名字的（可选）
                if(file.getFullName() != null) {
                    list.add(file);
                }
            }
        }

        // 建议按最后修改时间倒序排序，方便用户看到最近改的文件
        list.sort((a, b) -> b.getLastModifiedDate().compareTo(a.getLastModifiedDate()));

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
        request.setSinglePackage(true);

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
}