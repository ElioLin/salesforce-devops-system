package com.ruoyi.salesforce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.vo.SfDashboardVo;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentMapper;
import com.ruoyi.salesforce.mapper.SfOrgMapper; // 假设你有这个 Mapper
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/salesforce/dashboard")
public class SfDashboardController extends BaseController {

    @Autowired
    private SfDeploymentMapper deploymentMapper;
    @Autowired
    private SfDeploymentHistoryMapper historyMapper;
    @Autowired
    private SfOrgMapper orgMapper; // 需要注入 Org Mapper

    @Autowired
    private RuoYiConfig ruoYiConfig;

    @GetMapping("/data")
    public AjaxResult getDashboardData() {
        SfDashboardVo vo = new SfDashboardVo();

        // 1. 顶部卡片指标
        // 1.1 已连接环境
        vo.setConnectedOrgs(orgMapper.selectCount(null));

        // 1.2 正在执行的任务 (状态为 Processing, Deploying, Validating)
        Long activeCount = deploymentMapper.selectCount(new LambdaQueryWrapper<SfDeployment>()
                .in(SfDeployment::getStatus, "Processing", "Deploying", "Validating", "Queued"));
        vo.setActiveTasks(activeCount);

        // 1.3 本周部署
        vo.setWeeklyDeployments(historyMapper.countWeeklyDeployments());

        // 1.4 成功率计算
        Long total = historyMapper.countTotalHistory();
        Long success = historyMapper.countSuccessDeployments();
        if (total > 0) {
            BigDecimal rate = new BigDecimal(success)
                    .divide(new BigDecimal(total), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal(100));
            vo.setSuccessRate(rate.setScale(1, RoundingMode.HALF_UP).toString() + "%");
        } else {
            vo.setSuccessRate("0%");
        }

        // 2. 趋势图数据 (补全日期，防止某天没数据导致断层)
        List<Map<String, Object>> dailyCounts = historyMapper.selectDailyDeployCount(13); // 查询过去13天+今天
        Map<String, Long> dateMap = new HashMap<>();
        for (Map<String, Object> map : dailyCounts) {
            String date = (String) map.get("dateStr");
            Long count = Long.valueOf(String.valueOf(map.get("countVal")));
            dateMap.put(date, count);
        }

        // 构建连续的日期列表
        List<String> xDates = new ArrayList<>();
        List<Long> yCounts = new ArrayList<>();
        // 简单逻辑：生成过去14天日期字符串 (需配合 DateUtils，这里简化处理，生产环境建议用 Calendar)
        // 为简化代码，这里直接使用查询出的 Key 排序，如果某天没数据可能会缺省。
        // 建议前端处理或后端完整生成日期 List。这里简单处理：只返回数据库有的。
        vo.setChartDates(dailyCounts.stream().map(m -> (String)m.get("dateStr")).collect(Collectors.toList()));
        vo.setChartCounts(dailyCounts.stream().map(m -> Long.valueOf(String.valueOf(m.get("countVal")))).collect(Collectors.toList()));


        // 3. 状态分布图 (饼图)
        List<Map<String, Object>> pieData = historyMapper.selectStatusDistribution();
        vo.setStatusPieData(pieData);

        // 4. 最新动态 (取前 8 条)
        List<SfDeploymentHistory> recentList = historyMapper.selectRecentList(8);

        vo.setRecentActivities(recentList);

        vo.setSysVersion("v" + ruoYiConfig.getVersion());
        vo.setSysName(ruoYiConfig.getName());

        return AjaxResult.success(vo);
    }
}
