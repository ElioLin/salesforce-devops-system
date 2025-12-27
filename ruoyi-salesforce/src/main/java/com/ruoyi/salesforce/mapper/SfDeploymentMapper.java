package com.ruoyi.salesforce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.salesforce.domain.SfDeployment;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
@Mapper
public interface SfDeploymentMapper extends BaseMapper<SfDeployment> {



    /**
     * 查询部署包详情（包含子列表）
     */
    public SfDeployment selectSfDeploymentById(Long id);

    /**
     * 查询部署包列表
     */
    public List<SfDeployment> selectSfDeploymentList(SfDeployment sfDeployment);
}