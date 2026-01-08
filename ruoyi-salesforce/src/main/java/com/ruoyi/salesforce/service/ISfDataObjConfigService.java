package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDataObjConfig;

import java.util.List;
import java.util.Map;

public interface ISfDataObjConfigService {
    // 配置管理
    List<SfDataObjConfig> selectConfigList(Long jobId);

    void batchSaveConfigs(Long jobId, List<SfDataObjConfig> configs);
}
