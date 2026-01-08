package com.ruoyi.salesforce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.mapper.SfDataObjConfigMapper;
import com.ruoyi.salesforce.service.ISfDataObjConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SfDataObjConfigServiceImpl implements ISfDataObjConfigService {
    @Autowired
    private SfDataObjConfigMapper configMapper;

    @Override
    public List<SfDataObjConfig> selectConfigList(Long jobId) {
        return configMapper.selectList(new LambdaQueryWrapper<SfDataObjConfig>().eq(SfDataObjConfig::getJobId, jobId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchSaveConfigs(Long jobId, List<SfDataObjConfig> configs) {
        List<SfDataObjConfig> dbConfigs = configMapper.selectList(
                new LambdaQueryWrapper<SfDataObjConfig>().eq(SfDataObjConfig::getJobId, jobId)
        );
        Set<Long> dbIds = dbConfigs.stream().map(SfDataObjConfig::getId).collect(Collectors.toSet());
        Set<Long> inputIds = configs.stream().map(SfDataObjConfig::getId).filter(Objects::nonNull).collect(Collectors.toSet());

        List<Long> idsToDelete = new ArrayList<>();
        for(Long dbId : dbIds) {
            if(!inputIds.contains(dbId)) idsToDelete.add(dbId);
        }

        if(!idsToDelete.isEmpty()) configMapper.deleteBatchIds(idsToDelete);

        for(SfDataObjConfig config : configs) {
            config.setJobId(jobId);
            if(StringUtils.isEmpty(config.getIsActive())) config.setIsActive("Y");
            if(config.getId() != null) configMapper.updateById(config);
            else {
                config.setCreateTime(new Date());
                configMapper.insert(config);
            }
        }
    }

}
