package com.ruoyi.salesforce.service;

import java.util.List;

import com.ruoyi.salesforce.domain.vo.SfDiffVo;
import com.sforce.soap.metadata.AsyncResult;
import com.sforce.soap.metadata.DeployOptions;
import com.sforce.soap.metadata.FileProperties;
import com.sforce.soap.metadata.MetadataConnection;

public interface ISfMetadataService {
    /**
     * 测试连接并列出前10个 Apex 类名
     * @param orgId 数据库中的环境ID
     * @return 类名列表
     */
    List<String> testConnection(Long orgId) throws Exception;

    /**
     * 拉取指定元数据的内容
     * @param orgId 数据库Org ID
     * @param type 元数据类型 (例如 ApexClass, ApexTrigger, CustomObject)
     * @param memberName 元数据名称 (例如 MyClass)
     * @return 代码内容字符串
     */
    String retrieveMetadata(Long orgId, String type, String memberName) throws Exception;

    /**
     * 通用查询元数据列表
     * @param orgId 数据库OrgID
     * @param type 元数据类型 (ApexClass, ApexTrigger, etc.)
     * @return 元数据文件信息列表
     */
    List<FileProperties> listMetadata(Long orgId, String type) throws Exception;

    /**
     * 比对元数据
     * @param sourceOrgId 源环境ID
     * @param targetOrgId 目标环境ID
     * @param type 元数据类型
     * @param memberName 元数据名称
     * @return 比对结果对象
     */
    SfDiffVo compareMetadata(Long sourceOrgId, Long targetOrgId, String type, String memberName) throws Exception;

    // 根据 manifest 拉取 ZIP 包
    byte[] retrieveZipByManifest(Long orgId, com.sforce.soap.metadata.Package manifest) throws Exception;

    // 部署 ZIP 包
    AsyncResult deployZip(Long orgId, byte[] zipData, DeployOptions options) throws Exception;


    /**
     * 获取 Metadata API 连接 (这是之前你在 Impl 里写了但可能没在接口暴露的方法)
     */
    MetadataConnection getMetadataConnection(Long orgId) throws Exception;

    /**
     * 检查部署状态
     */
    String checkDeployStatus(Long orgId, String processId) throws Exception;

    /**
     * 快速部署 (Quick Deploy)
     * @param orgId 目标环境ID
     * @param validationId 之前验证成功的 Process ID
     * @return 新的 Process ID
     */
    String deployRecentValidation(Long orgId, String validationId) throws Exception;
}
