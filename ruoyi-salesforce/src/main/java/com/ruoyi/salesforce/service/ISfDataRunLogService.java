package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDataRunLog;

import java.util.List;

public interface ISfDataRunLogService {
    // 日志与结果
    List<SfDataRunLog> selectLogList(Long jobId);

    String getResultFilePath(Long logId);

}
