package com.ruoyi.salesforce.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ruoyi.salesforce.domain.SfOrg;

/**
 * Salesforce环境管理Service接口
 *
 * @author ruoyi
 * @date 2025-12-25
 */
public interface ISfOrgService extends IService<SfOrg> {
    /**
     * 查询Salesforce环境管理
     *
     * @param id Salesforce环境管理主键
     * @return Salesforce环境管理
     */
    public SfOrg selectSfOrgById(Long id);

    /**
     * 查询Salesforce环境管理列表
     *
     * @param orgId Salesforce环境管理
     * @return Salesforce环境管理集合
     */
    public SfOrg selectSfOrgByOrgId(String orgId);

    /**
     * 查询Salesforce环境管理列表
     *
     * @param sfOrg Salesforce环境管理
     * @return Salesforce环境管理集合
     */
    public List<SfOrg> selectSfOrgList(SfOrg sfOrg);

    /**
     * 新增Salesforce环境管理
     *
     * @param sfOrg Salesforce环境管理
     * @return 结果
     */
    public int insertSfOrg(SfOrg sfOrg);

    /**
     * 修改Salesforce环境管理
     *
     * @param sfOrg Salesforce环境管理
     * @return 结果
     */
    public int updateSfOrg(SfOrg sfOrg);

    /**
     * 批量删除Salesforce环境管理
     *
     * @param ids 需要删除的Salesforce环境管理主键集合
     * @return 结果
     */
    public int deleteSfOrgByIds(Long[] ids);

    /**
     * 删除Salesforce环境管理信息
     *
     * @param id Salesforce环境管理主键
     * @return 结果
     */
    public int deleteSfOrgById(Long id);
}