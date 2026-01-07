package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDataJob;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.domain.SfDataRunLog;

import java.util.List;
import java.util.Map;

public interface ISfDataReconcileService {

    // 任务管理
    List<SfDataJob> selectJobList(SfDataJob job);

    SfDataJob selectJobById(Long id);

    int insertJob(SfDataJob job);

    int updateJob(SfDataJob job);

    int deleteJobByIds(Long[] ids);

    // 配置管理
    List<SfDataObjConfig> selectConfigList(Long jobId);

    void batchSaveConfigs(Long jobId, List<SfDataObjConfig> configs);

    // 核心操作
    void startJob(Long jobId);

    // 日志与结果
    List<SfDataRunLog> selectLogList(Long jobId);

    String getResultFilePath(Long logId);

    void processConfigsAsync(SfDataJob job, List<SfDataObjConfig> configs);

    Map<String, Object> previewCsvData(Long jobId, int pageNum, int pageSize, String diffType, String fieldName);
}