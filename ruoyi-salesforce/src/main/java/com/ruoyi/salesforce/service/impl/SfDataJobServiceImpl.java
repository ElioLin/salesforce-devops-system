package com.ruoyi.salesforce.service.impl;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDataJob;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.domain.SfDataRunLog;
import com.ruoyi.salesforce.domain.SfDataRunObjLog;
import com.ruoyi.salesforce.mapper.SfDataJobMapper;
import com.ruoyi.salesforce.mapper.SfDataObjConfigMapper;
import com.ruoyi.salesforce.mapper.SfDataRunLogMapper;
import com.ruoyi.salesforce.mapper.SfDataRunObjLogMapper;
import com.ruoyi.salesforce.service.ISfDataJobService;
import com.ruoyi.salesforce.service.ISfDataReconcileService;
import com.ruoyi.salesforce.service.ISfDescribeApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SfDataJobServiceImpl implements ISfDataJobService {
    @Autowired
    private SfDataJobMapper jobMapper;

    @Autowired
    private SfDataObjConfigMapper configMapper;

    @Autowired
    @Lazy
    private ISfDataReconcileService sfDataReconcileService;

    @Autowired
    private ISfDescribeApiService sfDescribeApiService;

    @Autowired
    private SfDataRunLogMapper runLogMapper;
    @Autowired
    private SfDataRunObjLogMapper objLogMapper;

    /**
     * 查询任务列表 (已优化：显示对象中文名称)
     */
    @Override
    public List<SfDataJob> selectJobList(SfDataJob sfDataJob) {
        // 1. 查询任务基础信息
        LambdaQueryWrapper<SfDataJob> wrapper = new LambdaQueryWrapper<>();
        if(StringUtils.isNotEmpty(sfDataJob.getJobName())) {
            wrapper.like(SfDataJob::getJobName, sfDataJob.getJobName());
        }
        wrapper.orderByDesc(SfDataJob::getCreateTime);
        List<SfDataJob> jobList = jobMapper.selectList(wrapper);

        // 2. 填充关联对象名称 (尝试转为 Label)
        if(jobList != null && !jobList.isEmpty()) {
            for(SfDataJob item : jobList) {
                // A. 查数据库获取配置的 API Names
                List<Object> apiNamesObj = configMapper.selectObjs(new LambdaQueryWrapper<SfDataObjConfig>()
                        .select(SfDataObjConfig::getObjectName)
                        .eq(SfDataObjConfig::getJobId, item.getId()));

                if(apiNamesObj != null && !apiNamesObj.isEmpty()) {
                    List<String> apiNames = apiNamesObj.stream()
                            .map(String::valueOf)
                            .collect(Collectors.toList());

                    // B. 尝试获取 Label 映射
                    // 逻辑：调用 DescribeService (它会先查 Redis，没有再查 Salesforce API)
                    List<String> displayNames = new ArrayList<>();
                    Map<String, String> nameToLabelMap = new HashMap<>();

                    try {
                        if(item.getSourceOrgId() != null) {
                            List<Map<String, String>> metaList = sfDescribeApiService.getSObjectList(item.getSourceOrgId());
                            if(metaList != null) {
                                for(Map<String, String> meta : metaList) {
                                    nameToLabelMap.put(meta.get("name"), meta.get("label"));
                                }
                            }
                        }
                    } catch(Exception e) {
                        // 如果获取元数据失败（如 Token 过期或网络问题），仅记录日志，不阻断列表展示
                        log.warn("获取任务[{}]的元数据失败，将降级显示 API Name: {}", item.getId(), e.getMessage());
                    }

                    // C. 转换名称
                    for(String apiName : apiNames) {
                        String label = nameToLabelMap.get(apiName);
                        // 如果找到了 Label，显示 "Label (API Name)" 格式，或者只显示 Label，这里推荐混合显示更清晰
                        // 如果没找到（比如 Redis 没缓存到或对象被删了），兜底显示 API Name
                        if(StringUtils.isNotEmpty(label)) {
                            displayNames.add(label);
                        } else {
                            displayNames.add(apiName);
                        }
                    }

                    item.setObjectNames(displayNames);
                }

                SfDataRunLog lastRun = runLogMapper.selectOne(new LambdaQueryWrapper<SfDataRunLog>()
                        .eq(SfDataRunLog::getJobId, item.getId())
                        .orderByDesc(SfDataRunLog::getStartTime)
                        .last("LIMIT 1"));

                if (lastRun != null) {
                    // 汇总该批次下所有对象的差异
                    List<SfDataRunObjLog> objLogs = objLogMapper.selectList(new LambdaQueryWrapper<SfDataRunObjLog>()
                            .eq(SfDataRunObjLog::getRunLogId, lastRun.getId()));
                    int totalDiff = 0;
                    int diffObj = 0;
                    for (SfDataRunObjLog oLog : objLogs) {
                        if (oLog.getDiffCount() != null && oLog.getDiffCount() > 0) {
                            totalDiff += oLog.getDiffCount();
                            diffObj++;
                        }
                    }
                    // 赋值给虚拟字段传给前端
                    item.setLastDiffCount(totalDiff);
                    item.setDiffObjCount(diffObj);
                }
            }
        }
        return jobList;
    }

    @Override
    public SfDataJob selectJobById(Long id) {
        return jobMapper.selectById(id);
    }

    @Override
    public int insertJob(SfDataJob job) {
        job.setCreateTime(new Date());
        job.setStatus("IDLE"); // 默认空闲
        return jobMapper.insert(job);
    }

    @Override
    public int updateJob(SfDataJob job) {
        job.setUpdateTime(new Date());
        // 如果前端强制把状态改为 IDLE (即停止任务)
        if("IDLE".equals(job.getStatus()) && job.getId() != null) {
            // 【修改】调用 Service 方法，而不是直接操作 Map
            sfDataReconcileService.stopJob(job.getId());
        }
        return jobMapper.updateById(job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteJobByIds(Long[] ids) {
        List<Long> idList = Arrays.asList(ids);

        // 1. 停止可能正在运行的任务
        for(Long id : ids) {
            sfDataReconcileService.stopJob(id);
        }

        List<SfDataRunObjLog> oldObjLogs = objLogMapper.selectList(new LambdaQueryWrapper<SfDataRunObjLog>()
                .in(SfDataRunObjLog::getJobId, idList));

        for (SfDataRunObjLog objLog : oldObjLogs) {
            if (StringUtils.isNotEmpty(objLog.getResultFilePath())) {
                File resFile = new File(objLog.getResultFilePath());
                if (resFile.exists()) {
                    // 使用 Hutool 工具类安全物理删除文件
                    FileUtil.del(resFile);
                }
            }
        }

        // 2. 级联删除：对象配置表 (sf_data_obj_config)
        configMapper.delete(new LambdaQueryWrapper<SfDataObjConfig>()
                .in(SfDataObjConfig::getJobId, idList));

        // 3. 级联删除：主运行日志表 (sf_data_run_log)
        runLogMapper.delete(new LambdaQueryWrapper<SfDataRunLog>()
                .in(SfDataRunLog::getJobId, idList));

        // 4. 级联删除：对象运行明细表 (sf_data_run_obj_log)
        objLogMapper.delete(new LambdaQueryWrapper<SfDataRunObjLog>()
                .in(SfDataRunObjLog::getJobId, idList));

        // 5. 最后删除：任务主表 (sf_data_job)
        return jobMapper.deleteBatchIds(idList);
    }
}
