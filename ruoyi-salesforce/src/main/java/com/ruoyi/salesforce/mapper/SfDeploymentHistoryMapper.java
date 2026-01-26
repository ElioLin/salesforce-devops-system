package com.ruoyi.salesforce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.vo.SfAuditVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface SfDeploymentHistoryMapper extends BaseMapper<SfDeploymentHistory> {

    /**
     * 查询元数据审计列表
     */
    List<SfAuditVo> selectAuditList(@Param("type") String type, @Param("name") String name);

    /** 统计过去 N 天每天的部署数量 */
    List<Map<String, Object>> selectDailyDeployCount(@Param("days") int days);

    /** 统计各状态的分布情况 */
    List<Map<String, Object>> selectStatusDistribution();

    /** 统计本周部署总数 */
    Long countWeeklyDeployments();

    /** 统计总成功数 (用于计算成功率) */
    Long countSuccessDeployments();

    /** 统计总历史数 */
    Long countTotalHistory();

    /**
     * 查询最新的历史记录（带部署包标题）
     * @param limit 条数
     */
    List<SfDeploymentHistory> selectRecentList(@Param("limit") int limit);
}
