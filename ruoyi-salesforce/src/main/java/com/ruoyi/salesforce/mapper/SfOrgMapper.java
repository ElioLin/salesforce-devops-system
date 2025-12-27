package com.ruoyi.salesforce.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.salesforce.domain.SfOrg;
import org.apache.ibatis.annotations.Mapper;

/**
 * Salesforce环境管理Mapper接口
 *
 * @author ruoyi
 * @date 2025-12-25
 */
@Mapper
public interface SfOrgMapper extends BaseMapper<SfOrg>
{
    /**
     * 查询Salesforce环境管理
     *
     * @param id Salesforce环境管理主键
     * @return Salesforce环境管理
     */
    public SfOrg selectSfOrgById(Long id);

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
     * 删除Salesforce环境管理
     *
     * @param id Salesforce环境管理主键
     * @return 结果
     */
    public int deleteSfOrgById(Long id);

    /**
     * 批量删除Salesforce环境管理
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSfOrgByIds(Long[] ids);
}
