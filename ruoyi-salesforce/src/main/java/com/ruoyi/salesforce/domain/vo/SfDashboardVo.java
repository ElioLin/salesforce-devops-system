package com.ruoyi.salesforce.domain.vo;

import com.ruoyi.salesforce.domain.SfDeploymentHistory;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合数据对象
 */
public class SfDashboardVo {
    // 顶部卡片数据
    private Long connectedOrgs;       // 已连接环境
    private Long activeTasks;         // 正在执行的任务
    private Long weeklyDeployments;   // 本周部署次数
    private String successRate;       // 部署成功率 (e.g., "92.5%")

    // 趋势图数据 (Line Chart)
    private List<String> chartDates;  // X轴: 日期 ["01-20", "01-21"]
    private List<Long> chartCounts;   // Y轴: 数量 [5, 12]

    // 状态分布图数据 (Donut Chart)
    // 格式: [{name: "Succeeded", value: 10}, {name: "Failed", value: 2}]
    private List<Map<String, Object>> statusPieData;

    // 最新动态列表 (只取前 10 条)
    private List<SfDeploymentHistory> recentActivities;

    // 【新增】系统版本号
    private String sysVersion;

    // 【新增】系统名称
    private String sysName;

    /**
     * 租户归属ID (SaaS公司隔离)
     */
    private String tenantId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSysVersion() {
        return sysVersion;
    }

    public void setSysVersion(String sysVersion) {
        this.sysVersion = sysVersion;
    }

    public String getSysName() {
        return sysName;
    }

    public void setSysName(String sysName) {
        this.sysName = sysName;
    }

    // Getter & Setter 省略...
    public Long getConnectedOrgs() {
        return connectedOrgs;
    }

    public void setConnectedOrgs(Long connectedOrgs) {
        this.connectedOrgs = connectedOrgs;
    }

    public Long getActiveTasks() {
        return activeTasks;
    }

    public void setActiveTasks(Long activeTasks) {
        this.activeTasks = activeTasks;
    }

    public Long getWeeklyDeployments() {
        return weeklyDeployments;
    }

    public void setWeeklyDeployments(Long weeklyDeployments) {
        this.weeklyDeployments = weeklyDeployments;
    }

    public String getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(String successRate) {
        this.successRate = successRate;
    }

    public List<String> getChartDates() {
        return chartDates;
    }

    public void setChartDates(List<String> chartDates) {
        this.chartDates = chartDates;
    }

    public List<Long> getChartCounts() {
        return chartCounts;
    }

    public void setChartCounts(List<Long> chartCounts) {
        this.chartCounts = chartCounts;
    }

    public List<Map<String, Object>> getStatusPieData() {
        return statusPieData;
    }

    public void setStatusPieData(List<Map<String, Object>> statusPieData) {
        this.statusPieData = statusPieData;
    }

    public List<SfDeploymentHistory> getRecentActivities() {
        return recentActivities;
    }

    public void setRecentActivities(List<SfDeploymentHistory> recentActivities) {
        this.recentActivities = recentActivities;
    }
}
