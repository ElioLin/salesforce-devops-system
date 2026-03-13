package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDataJob;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.domain.SfDataRunLog;

import java.util.List;
import java.util.Map;

public interface ISfDataReconcileService {
    // 核心操作
    void runJob(Long jobId, String currentTenantId);

    /**
     * 停止任务（移除运行标记）
     * 供 JobService 在修改状态或删除任务时调用
     */
    void stopJob(Long jobId);

    /**
     * 重试单个对象 (异步)
     */
    void retryObject(Long objLogId, String currentTenantId);
}
