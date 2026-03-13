package com.ruoyi.salesforce.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.springframework.stereotype.Service;
import com.ruoyi.salesforce.mapper.SfOrgMapper;
import com.ruoyi.salesforce.domain.SfOrg;

/**
 * Salesforce环境管理Service业务层处理
 *
 * @author ruoyi
 * @date 2025-12-25
 */
@Service
public class SfOrgServiceImpl extends ServiceImpl<SfOrgMapper, SfOrg> implements ISfOrgService {

    /**
     * 查询Salesforce环境管理
     *
     * @param id Salesforce环境管理主键
     * @return Salesforce环境管理
     */
    @Override
    public SfOrg selectSfOrgById(Long id) {
        return this.baseMapper.selectSfOrgById(id);
    }

    @Override
    public SfOrg selectSfOrgByOrgId(String orgId) {
        return this.baseMapper.selectSfOrgByOrgId(orgId);
    }

    /**
     * 查询Salesforce环境管理列表
     *
     * @param sfOrg Salesforce环境管理
     * @return Salesforce环境管理
     */
    @Override
    public List<SfOrg> selectSfOrgList(SfOrg sfOrg) {
        return this.baseMapper.selectSfOrgList(sfOrg);
    }

    /**
     * 新增Salesforce环境管理
     *
     * @param sfOrg Salesforce环境管理
     * @return 结果
     */
    @Override
    public int insertSfOrg(SfOrg sfOrg) {
        sfOrg.setCreateTime(DateUtils.getNowDate());
        sfOrg.setUserId(SecurityUtils.getUserId());
        sfOrg.setDeptId(SecurityUtils.getDeptId());
        return this.baseMapper.insertSfOrg(sfOrg);
    }

    /**
     * 修改Salesforce环境管理
     *
     * @param sfOrg Salesforce环境管理
     * @return 结果
     */
    @Override
    public int updateSfOrg(SfOrg sfOrg) {
        sfOrg.setUpdateTime(DateUtils.getNowDate());
        return this.baseMapper.updateSfOrg(sfOrg);
    }

    /**
     * 批量删除Salesforce环境管理
     *
     * @param ids 需要删除的Salesforce环境管理主键
     * @return 结果
     */
    @Override
    public int deleteSfOrgByIds(Long[] ids) {
        return this.baseMapper.deleteSfOrgByIds(ids);
    }

    /**
     * 删除Salesforce环境管理信息
     *
     * @param id Salesforce环境管理主键
     * @return 结果
     */
    @Override
    public int deleteSfOrgById(Long id) {
        return this.baseMapper.deleteSfOrgById(id);
    }
}
