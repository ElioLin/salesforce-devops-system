package com.ruoyi.salesforce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.vo.SfAuditVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SfDeploymentHistoryMapper extends BaseMapper<SfDeploymentHistory> {

    /**
     * 查询元数据审计列表
     */
    List<SfAuditVo> selectAuditList(@Param("type") String type, @Param("name") String name);
}
