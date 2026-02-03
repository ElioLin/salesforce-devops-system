package com.ruoyi.salesforce.service.impl;

import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentMapper;
import com.ruoyi.salesforce.mapper.SfOrgMapper;
import com.ruoyi.salesforce.service.ISfDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    @DataScope(deptAlias = "o", userAlias = "o")
    public Long countOrgs(SfOrg sfOrg) {
        // 利用 selectList 的 size 来统计（最稳妥，利用现有的 selectSfOrgList XML）
        return (long) orgMapper.selectSfOrgList(sfOrg).size();
    }

    /**
     * 2. 统计正在运行的任务 (带权限)
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "d")
    public Long countActiveTasks(SfDeployment sfDeployment) {
        List<SfDeployment> list = deploymentMapper.selectSfDeploymentList(sfDeployment);
        return list.stream().filter(d ->
                "Processing".equals(d.getStatus()) ||
                        "Deploying".equals(d.getStatus()) ||
                        "Validating".equals(d.getStatus()) ||
                        "Queued".equals(d.getStatus())
        ).count();
    }

    /**
     * 3. 本周部署数量
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "d") // XML 中 LEFT JOIN sf_deployment d
    public Long getWeeklyDeployCount(SfDeploymentHistory query) {
        return historyMapper.countWeeklyDeployments(query);
    }

    /**
     * 4. 部署总数
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "d")
    public Long getTotalDeployCount(SfDeploymentHistory query) {
        return historyMapper.countTotalHistory(query);
    }

    /**
     * 5. 成功总数
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "d")
    public Long getSuccessDeployCount(SfDeploymentHistory query) {
        return historyMapper.countSuccessDeployments(query);
    }

    /**
     * 6. 趋势图数据
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "d")
    public List<Map<String, Object>> getDailyCounts(int days, SfDeploymentHistory query) {
        // 将 AOP 注入的 params 传递给 Mapper
        return historyMapper.selectDailyDeployCount(days, query.getParams());
    }

    /**
     * 7. 状态分布图
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "d")
    public List<Map<String, Object>> getStatusDistribution(SfDeploymentHistory query) {
        return historyMapper.selectStatusDistribution(query);
    }

    /**
     * 8. 最新动态
     * 注意：SfDeploymentHistoryMapper.xml 中 selectRecentList 用的别名是 's'
     */
    @Override
    @DataScope(deptAlias = "s", userAlias = "s")
    public List<SfDeploymentHistory> getRecentActivities(SfDeploymentHistory query) {
        return historyMapper.selectRecentList(query);
    }
}
