package com.ruoyi.salesforce.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.*;
import com.ruoyi.salesforce.mapper.SfDataJobMapper;
import com.ruoyi.salesforce.mapper.SfDataObjConfigMapper;
import com.ruoyi.salesforce.mapper.SfDataRunLogMapper;
import com.ruoyi.salesforce.service.ISfDataReconcileService;
import com.ruoyi.salesforce.service.ISfDescribeApiService;
import com.ruoyi.salesforce.service.SfBulkApiService;
import com.ruoyi.salesforce.service.SfReconcileAlgorithm;
import com.ruoyi.salesforce.websocket.DeployWebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SfDataReconcileServiceImpl implements ISfDataReconcileService {

    @Autowired
    private SfDataJobMapper jobMapper;
    @Autowired
    private SfDataObjConfigMapper configMapper;
    @Autowired
    private SfDataRunLogMapper logMapper;
    @Autowired
    private SfBulkApiService bulkApiService;
    @Autowired
    private SfReconcileAlgorithm algorithm;
    @Autowired
    private ISfDescribeApiService sfDescribeApiService;

    // 注入自身代理对象 (确保 @Async 生效)
    @Autowired
    @Lazy
    private ISfDataReconcileService self;

    // 【新增】用于控制任务中断的标志位 Map <JobId, IsRunning>
    private static final Map<Long, Boolean> runningFlags = new ConcurrentHashMap<>();

    // ==================== 基础 CRUD 实现 (保持不变) ====================

    @Override
    public List<SfDataJob> selectJobList(SfDataJob job) {
        return jobMapper.selectList(new LambdaQueryWrapper<SfDataJob>()
                .like(StringUtils.isNotEmpty(job.getJobName()), SfDataJob::getJobName, job.getJobName())
                .eq(StringUtils.isNotEmpty(job.getStatus()), SfDataJob::getStatus, job.getStatus())
                .orderByDesc(SfDataJob::getCreateTime));
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
        // 如果前端强制把状态改为 IDLE，我们需要移除运行标志位，让后台线程感知并退出
        if("IDLE".equals(job.getStatus()) && job.getId() != null) {
            runningFlags.remove(job.getId());
        }
        return jobMapper.updateById(job);
    }

    @Override
    public int deleteJobByIds(Long[] ids) {
        configMapper.delete(new LambdaQueryWrapper<SfDataObjConfig>().in(SfDataObjConfig::getJobId, Arrays.asList(ids)));
        for(Long id : ids) {
            runningFlags.remove(id); // 清理标志位
        }
        return jobMapper.deleteBatchIds(Arrays.asList(ids));
    }

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

    @Override
    public List<SfDataRunLog> selectLogList(Long jobId) {
        return logMapper.selectList(new LambdaQueryWrapper<SfDataRunLog>()
                .eq(SfDataRunLog::getJobId, jobId)
                .orderByDesc(SfDataRunLog::getStartTime));
    }

    @Override
    public String getResultFilePath(Long logId) {
        SfDataRunLog log = logMapper.selectById(logId);
        if(log == null) throw new ServiceException("日志不存在");
        return log.getResultFilePath();
    }

    // ==================== 核心业务逻辑 (优化重点) ====================

    @Override
    public void startJob(Long jobId) {
        SfDataJob job = jobMapper.selectById(jobId);
        if(job == null) throw new ServiceException("任务不存在");

        if("RUNNING".equals(job.getStatus())) {
            // 双重检查：如果内存标记也在，说明真的在跑
            if(runningFlags.containsKey(jobId)) {
                throw new ServiceException("任务正在运行中，请勿重复启动");
            }
        }

        // 1. 获取启用配置
        List<SfDataObjConfig> configs = configMapper.selectList(new LambdaQueryWrapper<SfDataObjConfig>()
                .eq(SfDataObjConfig::getJobId, jobId)
                .eq(SfDataObjConfig::getIsActive, "Y"));

        if(configs.isEmpty()) {
            throw new ServiceException("未找到启用的对象配置");
        }

        // 2. 更新数据库状态
        job.setStatus("RUNNING");
        jobMapper.updateById(job);

        // 3. 设置运行标志位
        runningFlags.put(jobId, true);

        // 4. 【修复】只调用代理对象的 Async 方法，且不进行本地直接调用
        // 这将立即返回，释放 HTTP 线程，前端不再超时
        self.processConfigsAsync(job, configs);
    }

    @Override
    @Async("sfTaskExecutor") // 【建议】指定线程池名称，见下文配置
    public void processConfigsAsync(SfDataJob job, List<SfDataObjConfig> configs) {
        log.info("任务 [{}] 开始异步执行...", job.getJobName());
        try {
            int total = configs.size();
            for(int i = 0; i < total; i++) {
                // 【新增】每次处理前检查是否被强制停止
                if(!runningFlags.containsKey(job.getId())) {
                    log.warn("任务 [{}] 检测到停止信号，终止后续流程", job.getJobName());
                    publishProgress(job.getId(), "STOPPED", "任务已手动停止", 0);
                    return;
                }

                SfDataObjConfig config = configs.get(i);
                processSingleObject(job, config);

                // 计算总体进度 (简单估算)
                int percent = (int) (((double) (i + 1) / total) * 100);
                // 这里不推100%，留给最后 finish 推送
                if(percent < 100) {
                    publishProgress(job.getId(), "PROCESSING", "当前进度: " + (i + 1) + "/" + total, percent);
                }
            }
            publishProgress(job.getId(), "ALL_DONE", "所有对象比对完成", 100);
        } catch(Exception e) {
            log.error("批量比对流程异常", e);
            publishProgress(job.getId(), "ERROR", "任务异常终止: " + e.getMessage(), 0);
        } finally {
            // 清理状态
            runningFlags.remove(job.getId());
            job.setStatus("IDLE");
            jobMapper.updateById(job);
        }
    }

    /**
     * 单个对象的完整比对流程
     */
    private void processSingleObject(SfDataJob job, SfDataObjConfig config) {
        // 如果被标记停止，直接返回
        if(!runningFlags.containsKey(job.getId())) return;

        String batchNo = UUID.randomUUID().toString();
        SfDataRunLog runLog = new SfDataRunLog();
        runLog.setJobId(job.getId());
        runLog.setConfigId(config.getId());
        runLog.setRunBatchNo(batchNo);
        runLog.setStartTime(new Date());
        runLog.setStatus("PROCESSING");
        logMapper.insert(runLog);

        File srcFile = null;
        File tgtFile = null;
        File resFile = new File("/tmp/reconcile_res_" + batchNo + ".csv"); // 建议改为可配置路径

        try {
            publishProgress(job.getId(), "ANALYZING", "正在分析 [" + config.getObjectName() + "]...", 10);

            // 1. 构造 SOQL
            List<String> srcFields = buildDynamicFields(job.getSourceOrgId(), config, config.getSourceKeyField());
            String srcSoql = buildSoql(config.getObjectName(), srcFields, config.getSyncFilterLogic());

            List<String> tgtFields = buildDynamicFields(job.getTargetOrgId(), config, config.getTargetKeyField());
            String tgtSoql = buildSoql(config.getObjectName(), tgtFields, config.getSyncFilterLogic());

            // 2. 提交 Bulk
            publishProgress(job.getId(), "SUBMITTING", "提交 Salesforce 查询...", 20);
            String srcJobId = bulkApiService.submitQueryJob(job.getSourceOrgId(), srcSoql);
            String tgtJobId = bulkApiService.submitQueryJob(job.getTargetOrgId(), tgtSoql);

            // 3. 轮询等待 (传入 jobId 用于中断检测)
            waitForJobs(job.getId(), job.getSourceOrgId(), srcJobId, job.getTargetOrgId(), tgtJobId);

            // 4. 下载
            if(!runningFlags.containsKey(job.getId())) throw new InterruptedException("User Stopped");
            publishProgress(job.getId(), "DOWNLOADING", "下载数据中...", 60);
            srcFile = bulkApiService.downloadResult(job.getSourceOrgId(), srcJobId, "/tmp/src_" + batchNo + ".csv");
            tgtFile = bulkApiService.downloadResult(job.getTargetOrgId(), tgtJobId, "/tmp/tgt_" + batchNo + ".csv");

            // 5. 比对
            if(!runningFlags.containsKey(job.getId())) throw new InterruptedException("User Stopped");
            publishProgress(job.getId(), "COMPARING", "本地比对计算中...", 80);
            SfReconcileAlgorithm.ReconcileStats stats = algorithm.execute(srcFile, tgtFile, resFile, config);

            // 6. 结果
            runLog.setStatus("SUCCESS");
            runLog.setTotalSourceRows(stats.getTotalSource());
            runLog.setTotalTargetRows(stats.getTotalTarget());
            runLog.setDiffRowCount(stats.getDiffCount());
            runLog.setMissingTargetCount(stats.getMissingTarget());
            runLog.setResultFilePath(resFile.getAbsolutePath());
            if(!stats.getPreviewList().isEmpty()) {
                runLog.setErrorMsg(JSON.toJSONString(stats.getPreviewList()));
            }

        } catch(InterruptedException ie) {
            log.warn("任务被中断");
            runLog.setStatus("STOPPED");
            runLog.setErrorMsg("用户手动停止");
        } catch(Exception e) {
            log.error("比对异常", e);
            runLog.setStatus("FAILED");
            runLog.setErrorMsg("执行失败: " + e.getMessage());
        } finally {
            runLog.setEndTime(new Date());
            logMapper.updateById(runLog);
            // 清理临时文件
            if(srcFile != null) srcFile.delete();
            if(tgtFile != null) tgtFile.delete();
        }
    }

    private void waitForJobs(Long sysJobId, Long srcOrgId, String srcJobId, Long tgtOrgId, String tgtJobId) throws Exception {
        boolean srcDone = false;
        boolean tgtDone = false;
        long startTime = System.currentTimeMillis();
        long timeout = 60 * 60 * 1000; // 1小时超时

        while(!srcDone || !tgtDone) {
            // 检查中断信号
            if(!runningFlags.containsKey(sysJobId)) {
                throw new InterruptedException("任务已停止");
            }
            if(System.currentTimeMillis() - startTime > timeout) {
                throw new ServiceException("Bulk API 查询超时");
            }

            if(!srcDone) {
                String state = bulkApiService.checkJobStatus(srcOrgId, srcJobId);
                if("JobComplete".equals(state)) srcDone = true;
                else if("Failed".equals(state) || "Aborted".equals(state)) {
                    throw new ServiceException("源环境 Job 失败: " + bulkApiService.getErrorMessage(srcOrgId, srcJobId));
                }
            }
            if(!tgtDone) {
                String state = bulkApiService.checkJobStatus(tgtOrgId, tgtJobId);
                if("JobComplete".equals(state)) tgtDone = true;
                else if("Failed".equals(state) || "Aborted".equals(state)) {
                    throw new ServiceException("目标环境 Job 失败: " + bulkApiService.getErrorMessage(tgtOrgId, tgtJobId));
                }
            }

            Thread.sleep(5000);
        }
    }

    /**
     * 动态构建查询字段
     */
    private List<String> buildDynamicFields(Long orgId, SfDataObjConfig config, String keyField) throws Exception {
        String objName = config.getObjectName();
        List<Map<String, Object>> allFields = sfDescribeApiService.getSObjectFields(orgId, objName);

        if (allFields == null || allFields.isEmpty()) {
            log.warn("对象 [{}] Describe结果为空", objName);
            return Collections.singletonList(keyField);
        }

        // 【核心判断】是否为自定义元数据类型 (__mdt)
        // __mdt 类型的字段在 API 中经常返回 queryable=false，但实际上是支持 SOQL 的，所以需要强制放行
        boolean isMdt = StringUtils.isNotEmpty(objName) && objName.toLowerCase().endsWith("__mdt");

        log.info("对象 [{}] (isMdt={}) Describe共获取到 {} 个字段，开始筛选...", objName, isMdt, allFields.size());

        Set<String> excludedSet = new HashSet<>();
        if (StringUtils.isNotEmpty(config.getExcludedFields())) {
            for (String f : config.getExcludedFields().split(",")) {
                excludedSet.add(f.trim().toLowerCase());
            }
        }

        List<String> finalFields = new ArrayList<>();
        finalFields.add(keyField);

        int skippedByQueryable = 0;
        int skippedByType = 0;
        int skippedByExclude = 0;

        for (Map<String, Object> field : allFields) {
            String name = getMapValueStr(field, "name");
            String type = getMapValueStr(field, "type");
            boolean queryable = getMapValueBool(field, "queryable", true);

            if (StringUtils.isEmpty(name)) continue;
            if (name.equalsIgnoreCase(keyField)) continue;

            if (excludedSet.contains(name.toLowerCase())) {
                skippedByExclude++;
                continue;
            }

            // 【核心修复】如果是 __mdt 对象，忽略 queryable=false 的限制
            if (!queryable && !isMdt) {
                if (skippedByQueryable < 3) log.info("字段 [{}] 被跳过: queryable=false", name);
                skippedByQueryable++;
                continue;
            }

            if ("base64".equalsIgnoreCase(type) || "address".equalsIgnoreCase(type) || "location".equalsIgnoreCase(type)) {
                skippedByType++;
                continue;
            }

            finalFields.add(name);
        }

        log.info("筛选统计: Key=[{}], 总数=[{}], 最终可用=[{}]. 跳过详情: [不可查询(已放行MDT)={}, 类型不支持={}, 手动排除={}]",
                keyField, allFields.size(), finalFields.size(), isMdt ? 0 : skippedByQueryable, skippedByType, skippedByExclude);

        return finalFields;
    }

    // --- 必须包含这两个辅助方法 ---

    private String getMapValueStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) val = map.get(StringUtils.capitalize(key)); // Try "Name"
        if (val == null) val = map.get(key.toLowerCase()); // Try "name"
        return val == null ? null : val.toString();
    }

    private boolean getMapValueBool(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        // 依次尝试 "Queryable", "queryable"
        if (val == null) val = map.get(StringUtils.capitalize(key));
        if (val == null) val = map.get(key.toLowerCase());

        if (val == null) return defaultValue; // 这一步至关重要：如果没找到，默认它是可以查询的
        if (val instanceof Boolean) return (Boolean) val;
        return Boolean.parseBoolean(val.toString());
    }

    private String buildSoql(String obj, List<String> fields, String where) {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", fields));
        sb.append(" FROM ").append(obj);
        if(StringUtils.isNotEmpty(where)) {
            sb.append(" WHERE ").append(where);
        }
        return sb.toString();
    }

    private void publishProgress(Long jobId, String stage, String msg, int percent) {
        JSONObject json = new JSONObject();
        json.put("jobId", jobId);
        json.put("stage", stage);
        json.put("msg", msg);
        json.put("percent", percent);
        DeployWebSocketServer.sendMessage("reconcile_" + jobId, json.toJSONString());
    }
}