package com.ruoyi.salesforce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentMapper;
import com.ruoyi.salesforce.mapper.SfOrgMapper;
import com.ruoyi.salesforce.service.ISfDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class SfDashboardServiceImpl implements ISfDashboardService {

    @Autowired
    private SfDeploymentHistoryMapper historyMapper;
    @Autowired
    private SfDeploymentMapper deploymentMapper;
    @Autowired
    private SfOrgMapper orgMapper;

    /**
     * 1. 统计 Org 数量 (带权限)
     */
    @Override
    public Long countOrgs(SfOrg sfOrg) {
        return orgMapper.selectCount(new LambdaQueryWrapper<>());
    }

    /**
     * 2. 统计正在运行的任务 (带权限)
     */
    @Override
    public Long countActiveTasks(SfDeployment sfDeployment) {
        return deploymentMapper.selectCount(new LambdaQueryWrapper<SfDeployment>()
                .in(SfDeployment::getStatus, Arrays.asList("Processing", "Deploying", "Validating", "Queued")));
    }

    /**
     * 3. 本周部署数量
     */
    @Override
    public Long getWeeklyDeployCount(SfDeploymentHistory query) {
        return historyMapper.countWeeklyDeployments(query);
    }

    /**
     * 4. 部署总数
     */
    @Override
    public Long getTotalDeployCount(SfDeploymentHistory query) {
        return historyMapper.countTotalHistory(query);
    }

    /**
     * 5. 成功总数
     */
    @Override
    public Long getSuccessDeployCount(SfDeploymentHistory query) {
        return historyMapper.countSuccessDeployments(query);
    }

    /**
     * 6. 趋势图数据
     */
    @Override
    public List<Map<String, Object>> getDailyCounts(int days, SfDeploymentHistory query) {
        // 将 AOP 注入的 params 传递给 Mapper
        return historyMapper.selectDailyDeployCount(days, query.getParams());
    }

    /**
     * 7. 状态分布图
     */
    @Override
    public List<Map<String, Object>> getStatusDistribution(SfDeploymentHistory query) {
        return historyMapper.selectStatusDistribution(query);
    }

    /**
     * 8. 最新动态
     * 注意：SfDeploymentHistoryMapper.xml 中 selectRecentList 用的别名是 's'
     */
    @Override
    public List<SfDeploymentHistory> getRecentActivities(SfDeploymentHistory query) {
        return historyMapper.selectRecentList(query);
    }
}
