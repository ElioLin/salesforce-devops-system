package com.ruoyi.salesforce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.vo.SfAuditVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SfDeploymentHistoryMapper extends BaseMapper<SfDeploymentHistory> {

    /**
     * 查询元数据审计列表
     */
    List<SfAuditVo> selectAuditList(@Param("type") String type, @Param("name") String name);

    /**
     * 统计过去 N 天趋势 (传入实体以支持权限)
     */
    List<Map<String, Object>> selectDailyDeployCount(@Param("days") int days, @Param("params") Map<String, Object> params);

    /**
     * 统计分布 (传入实体)
     */
    List<Map<String, Object>> selectStatusDistribution(SfDeploymentHistory history);

    /**
     * 本周数量
     */
    Long countWeeklyDeployments(SfDeploymentHistory history);

    /**
     * 成功数量
     */
    Long countSuccessDeployments(SfDeploymentHistory history);

    /**
     * 总数量
     */
    Long countTotalHistory(SfDeploymentHistory history);

    /**
     * 最新动态 (注意 params 会包含在 entity 中)
     */
    List<SfDeploymentHistory> selectRecentList(SfDeploymentHistory history);

    @Update("UPDATE sf_deployment_history SET git_commit_hash = #{commitHash}, " +
            "git_sync_status = #{status}, git_sync_log = #{logMsg} " +
            "WHERE id = #{id}")
    int updateGitStatus(@Param("id") Long id,
                        @Param("commitHash") String commitHash,
                        @Param("status") String status,
                        @Param("logMsg") String logMsg);
}
