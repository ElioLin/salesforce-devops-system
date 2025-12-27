package com.ruoyi.salesforce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
@Mapper
public interface SfDeploymentItemMapper extends BaseMapper<SfDeploymentItem> {
    /**
     * 根据主表ID删除所有明细
     */
    public int deleteSfDeploymentItemByDeploymentId(Long deploymentId);

}