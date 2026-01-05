package com.ruoyi.salesforce.service;

import java.util.List;
import java.util.Map;

import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDiffVo;
import com.ruoyi.salesforce.service.impl.SfMetadataServiceImpl;
import com.sforce.soap.metadata.AsyncResult;
import com.sforce.soap.metadata.DeployOptions;
import com.sforce.soap.metadata.FileProperties;
import com.sforce.soap.metadata.MetadataConnection;

public interface ISfMetadataService {

    /**
     * 【核心升级】智能拉取指定元数据的内容（支持LWC、字段等复杂类型）
     */
    String retrieveMetadata(Long orgId, String type, String memberName) throws Exception;

    /**
     * 通用查询元数据列表
     */
    List<FileProperties> listMetadata(Long orgId, String type) throws Exception;

    /**
     * 比对元数据
     */
    SfDiffVo compareMetadata(Long sourceOrgId, Long targetOrgId, String type, String memberName) throws Exception;

    /**
     * 根据 manifest 拉取 ZIP 包
     */
    byte[] retrieveZipByManifest(Long orgId, com.sforce.soap.metadata.Package manifest) throws Exception;

    /**
     * 部署 ZIP 包
     */
    AsyncResult deployZip(Long orgId, byte[] zipData, DeployOptions options) throws Exception;

    /**
     * 获取 Metadata API 连接
     */
    MetadataConnection getMetadataConnection(Long orgId) throws Exception;

    /**
     * 检查部署状态
     */
    String checkDeployStatus(Long orgId, String processId) throws Exception;

    /**
     * 快速部署 (Quick Deploy)
     */
    String deployRecentValidation(Long orgId, String validationId) throws Exception;

    /**
     * 【新增】获取 Org 支持的所有元数据类型
     */
    List<String> getAllMetadataTypes(Long orgId) throws Exception;

    /**
     * 【新增】强制从 Salesforce 刷新元数据缓存
     */
    List<FileProperties> refreshMetadataCache(Long orgId, String type) throws Exception;

    /**
     * 【新增】将 Salesforce 元数据类型同步到若依数据字典
     */
    void syncMetadataToDict(Long orgId) throws Exception;

    /**
     * 清除指定 Org 的所有元数据缓存
     */
    void clearCacheForOrg(Long orgId);

    void refreshAccessToken(SfOrg sfOrg);

    <T> T executeWithRetry(Long orgId, SfMetadataServiceImpl.SfOperation<T> operation) throws Exception;
}