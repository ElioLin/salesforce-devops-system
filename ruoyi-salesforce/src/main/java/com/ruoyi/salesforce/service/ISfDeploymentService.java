package com.ruoyi.salesforce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentItem;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

public interface ISfDeploymentService extends IService<SfDeployment> {
    // 查询列表
    List<SfDeployment> selectSfDeploymentList(SfDeployment sfDeployment);
    // 获取详情（带子表）
    SfDeployment selectSfDeploymentById(Long id);
    // 新增
    int insertSfDeployment(SfDeployment sfDeployment);
    // 批量添加明细（从元数据浏览器添加）
    void addItems(Long deploymentId, List<SfDeploymentItem> items);

    /**
     * 修改部署包
     */
    int updateSfDeployment(SfDeployment sfDeployment);

    /**
     * 批量删除部署包
     */
    int deleteSfDeploymentByIds(Long[] ids);

    // 移除明细
    void removeItems(List<Long> itemIds);
    // 获取某个包下的所有明细
    List<SfDeploymentItem> selectItems(Long deploymentId);

    /**
     * 执行部署/验证
     * @param deploymentId 部署包ID
     * @param checkOnly true=仅验证, false=实际部署
     * @return 异步任务ID (Process ID)
     */
    void deployPackage(Long deploymentId, boolean checkOnly) throws Exception;

    /**
     * 检查部署状态
     * @param targetOrgId 目标环境ID
     * @param processId 部署任务ID
     * @return 状态字符串 (JSON)
     */
    String checkDeployStatus(Long targetOrgId, String processId) throws Exception;

    void quickDeploy(Long deploymentId);

    /**
     * 异步计算部署包内所有明细的状态
     */
    void checkDiffStatus(Long deploymentId);

    // 【新增】预览部署包
    Map<String, Object> previewPackage(Long deploymentId);

    void downloadPackage(Long deploymentId, HttpServletResponse response);
}