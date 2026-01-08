package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDataJob;

import java.util.List;

public interface ISfDataJobService {
    // 任务管理
    List<SfDataJob> selectJobList(SfDataJob job);

    SfDataJob selectJobById(Long id);

    int insertJob(SfDataJob job);

    int updateJob(SfDataJob job);

    int deleteJobByIds(Long[] ids);
}
