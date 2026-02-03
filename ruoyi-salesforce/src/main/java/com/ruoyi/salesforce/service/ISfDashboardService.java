package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfOrg;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计专属 Service
 */
public interface ISfDashboardService {

    // 统计 Org 数量
    Long countOrgs(SfOrg sfOrg);

    // 统计正在运行的任务
    Long countActiveTasks(SfDeployment sfDeployment);

    // 统计本周部署
    Long getWeeklyDeployCount(SfDeploymentHistory query);

    // 统计总数
    Long getTotalDeployCount(SfDeploymentHistory query);

    // 统计成功数
    Long getSuccessDeployCount(SfDeploymentHistory query);

    // 获取趋势图数据
    List<Map<String, Object>> getDailyCounts(int days, SfDeploymentHistory query);

    // 获取分布图数据
    List<Map<String, Object>> getStatusDistribution(SfDeploymentHistory query);

    // 获取最新动态
    List<SfDeploymentHistory> getRecentActivities(SfDeploymentHistory query);
}
