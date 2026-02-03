package com.ruoyi.salesforce.controller;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.domain.vo.SfDashboardVo;
import com.ruoyi.salesforce.service.ISfDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/salesforce/dashboard")
public class SfDashboardController extends BaseController {

    @Autowired
    private ISfDashboardService dashboardService;

    @Autowired
    private RuoYiConfig ruoYiConfig;

    @GetMapping("/data")
    public AjaxResult getDashboardData() {
        SfDashboardVo vo = new SfDashboardVo();

        // 1. 准备查询对象
        SfOrg orgQuery = new SfOrg();
        SfDeployment deployQuery = new SfDeployment();
        SfDeploymentHistory historyQuery = new SfDeploymentHistory();

        // --- 核心指标 ---
        vo.setConnectedOrgs(dashboardService.countOrgs(orgQuery));
        vo.setActiveTasks(dashboardService.countActiveTasks(deployQuery));
        vo.setWeeklyDeployments(dashboardService.getWeeklyDeployCount(historyQuery));

        // --- 成功率 ---
        Long total = dashboardService.getTotalDeployCount(historyQuery);
        Long success = dashboardService.getSuccessDeployCount(historyQuery);
        if(total > 0) {
            BigDecimal rate = new BigDecimal(success)
                    .divide(new BigDecimal(total), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            vo.setSuccessRate(rate.setScale(1, RoundingMode.HALF_UP).toString() + "%");
        } else {
            vo.setSuccessRate("0%");
        }

        // --- 趋势图 (核心修复区域) ---
        List<Map<String, Object>> dailyCounts = dashboardService.getDailyCounts(13, historyQuery);

        // 【优化】使用 getValueIgnoreCase 兼容大小写，并处理空指针
        List<String> xDates = dailyCounts.stream()
                .map(m -> String.valueOf(getValueIgnoreCase(m, "dateStr")))
                .collect(Collectors.toList());

        List<Long> yCounts = dailyCounts.stream()
                .map(m -> {
                    Object val = getValueIgnoreCase(m, "countVal");
                    return val == null ? 0L : Long.valueOf(String.valueOf(val));
                })
                .collect(Collectors.toList());

        vo.setChartDates(xDates);
        vo.setChartCounts(yCounts);

        // --- 分布图 ---
        List<Map<String, Object>> pieData = dashboardService.getStatusDistribution(historyQuery);
        // 【优化】分布图也做一下大小写兼容，防止 Map Key 变大写导致前端饼图没名字
        List<Map<String, Object>> normalizedPieData = new ArrayList<>();
        for(Map<String, Object> m : pieData) {
            m.put("name", getValueIgnoreCase(m, "name")); // 确保 name 字段存在
            m.put("value", getValueIgnoreCase(m, "value")); // 确保 value 字段存在
            normalizedPieData.add(m);
        }
        vo.setStatusPieData(normalizedPieData);

        // --- 最新动态 ---
        historyQuery.setLimit(8);
        List<SfDeploymentHistory> recentList = dashboardService.getRecentActivities(historyQuery);
        vo.setRecentActivities(recentList);

        // --- 版本信息 ---
        vo.setSysVersion("v" + ruoYiConfig.getVersion());
        vo.setSysName(ruoYiConfig.getName());

        return AjaxResult.success(vo);
    }

    /**
     * 【新增辅助方法】忽略大小写获取 Map 值
     * 解决 MyBatis 在不同数据库/配置下返回 Key 大小写不一致的问题
     */
    private Object getValueIgnoreCase(Map<String, Object> map, String key) {
        if(map == null) return null;
        if(map.containsKey(key)) return map.get(key);
        if(map.containsKey(key.toUpperCase())) return map.get(key.toUpperCase());
        if(map.containsKey(key.toLowerCase())) return map.get(key.toLowerCase());
        return null;
    }
}
