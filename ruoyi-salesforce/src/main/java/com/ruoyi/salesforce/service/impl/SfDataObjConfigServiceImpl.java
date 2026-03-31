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
import com.ruoyi.salesforce.domain.SfDataRunObjLog;
import com.ruoyi.salesforce.mapper.SfDataRunObjLogMapper;

import java.util.*;
import java.util.stream.Collectors;
import cn.hutool.core.io.FileUtil;

@Slf4j
@Service
public class SfDataObjConfigServiceImpl implements ISfDataObjConfigService {
    @Autowired
    private SfDataObjConfigMapper configMapper;

    @Autowired
    private SfDataRunObjLogMapper objLogMapper;

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

        if(!idsToDelete.isEmpty()) {
            // 1. 在删除数据库记录前，先查出所有即将被销毁的对象执行明细日志
            // （因为一个配置对象如果被多次重试/运行，可能会产生多条 log 记录）
            List<SfDataRunObjLog> logsToDelete = objLogMapper.selectList(
                    new LambdaQueryWrapper<SfDataRunObjLog>().in(SfDataRunObjLog::getObjConfigId, idsToDelete)
            );
            for (SfDataRunObjLog logItem : logsToDelete) {
                // 2.1 常规清理：删除数据库中明确登记的结果文件
                if (StringUtils.isNotEmpty(logItem.getResultFilePath())) {
                    FileUtil.del(logItem.getResultFilePath());
                }

                // 2.2 极限防御清理：根据我们的底层引擎规则，主动嗅探并剿灭所有可能的残留文件
                // 防止因为宕机、强杀等原因导致数据库没有记录路径，但磁盘上仍有残骸
                String baseDir = "/tmp/sf_reconcile/";
                FileUtil.del(baseDir + "res_" + logItem.getId() + ".csv");        // 兜底正式文件
                FileUtil.del(baseDir + "res_" + logItem.getId() + "_temp.csv");   // 兜底临时文件
                FileUtil.del(baseDir + jobId + "_" + logItem.getId() + "_src.csv"); // 兜底源端下载文件
                FileUtil.del(baseDir + jobId + "_" + logItem.getId() + "_tgt.csv"); // 兜底目标端下载文件

                log.info("已完成对比对象配置的物理文件清理，清理的 ObjLogId: {}", logItem.getId());
            }

            // 1. 删除对象配置
            configMapper.deleteBatchIds(idsToDelete);

            // 【核心修复】：2. 级联彻底删除下游关联的对象执行明细日志 (清理脏数据)
            objLogMapper.delete(new LambdaQueryWrapper<SfDataRunObjLog>()
                    .in(SfDataRunObjLog::getObjConfigId, idsToDelete));
        }

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
