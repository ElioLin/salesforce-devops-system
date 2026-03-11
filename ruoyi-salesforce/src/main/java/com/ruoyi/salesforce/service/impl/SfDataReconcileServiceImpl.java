package com.ruoyi.salesforce.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.*;
import com.ruoyi.salesforce.mapper.*;
import com.ruoyi.salesforce.service.*;
import com.ruoyi.salesforce.websocket.DeployWebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Salesforce 数据比对核心执行引擎 (多线程并发完整版)
 * 特性：
 * 1. 支持通过 job.concurrentLimit 控制并发数
 * 2. 线程安全的任务分发与状态汇总
 * 3. 细粒度的停止检查
 */
@Slf4j
@Service
public class SfDataReconcileServiceImpl implements ISfDataReconcileService {

    @Autowired
    private SfDataJobMapper jobMapper;
    @Autowired
    private SfDataObjConfigMapper configMapper;
    @Autowired
    private SfDataRunLogMapper runLogMapper;
    @Autowired
    private SfDataRunObjLogMapper objLogMapper;

    @Autowired
    private SfBulkApiService bulkApiService;
    @Autowired
    private SfReconcileAlgorithm algorithm;
    @Autowired
    private ISfDescribeApiService describeApiService;

    // 运行标志位 (JobId -> Boolean)，用于控制停止
    private static final Map<Long, Boolean> runningFlags = new ConcurrentHashMap<>();

    @Override
    public void stopJob(Long jobId) {
        if(jobId != null) {
            runningFlags.remove(jobId);
            log.info("任务 [{}] 停止指令已下达", jobId);
        }
    }

    /**
     * 核心入口：异步执行比对任务 (多线程调度)
     */
    @Async
    @Override
    public void runJob(Long jobId) {
        SfDataJob job = jobMapper.selectById(jobId);
        if(job == null) return;

        // 1. 初始化主日志
        SfDataRunLog runLog = new SfDataRunLog();
        runLog.setJobId(jobId);
        runLog.setStartTime(new Date());
        runLog.setStatus("RUNNING");
        runLogMapper.insert(runLog);

        // 2. 获取配置
        List<SfDataObjConfig> configs = configMapper.selectList(new LambdaQueryWrapper<SfDataObjConfig>()
                .eq(SfDataObjConfig::getJobId, jobId)
                .eq(SfDataObjConfig::getIsActive, "Y"));

        if(configs.isEmpty()) {
            finishRunLog(runLog, job, "FINISHED", "未找到启用的对象配置");
            return;
        }

        // 3. 预生成所有 WAITING 日志
        List<SfDataRunObjLog> queue = new ArrayList<>();
        for(SfDataObjConfig config : configs) {
            SfDataRunObjLog objLog = new SfDataRunObjLog();
            objLog.setRunLogId(runLog.getId());
            objLog.setJobId(jobId);
            objLog.setObjConfigId(config.getId());
            objLog.setObjectName(config.getObjectName());
            objLog.setStatus("WAITING");
            objLog.setProgress(0);
            objLogMapper.insert(objLog);
            queue.add(objLog);
        }

        // 标记开始
        runningFlags.put(jobId, true);
        job.setStatus("RUNNING");
        jobMapper.updateById(job);
        publishProgress(jobId, null, "INIT", "任务启动，准备并发执行...", 0);

        // 4. --- 多线程执行核心逻辑 Start ---

        // 获取并发数，默认为 3，最大防爆设为 10
        int threads = job.getConcurrentLimit() != null && job.getConcurrentLimit() > 0 ? job.getConcurrentLimit() : 3;
        if(threads > 10) threads = 10;

        log.info("任务 [{}] 启动并发执行，线程池大小: {}", jobId, threads);

        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            // 使用 CountDownLatch 等待所有子任务完成
            CountDownLatch latch = new CountDownLatch(queue.size());

            for(SfDataRunObjLog objLog : queue) {
                // 提交任务到线程池
                executor.submit(() -> {
                    try {
                        // 双重检查停止标志
                        if(!isRunning(jobId)) {
                            updateObjLogStatus(objLog, "ABORTED", "用户手动停止", 0);
                        } else {
                            executeObjectLog(job, objLog);
                        }
                    } catch(Exception e) {
                        log.error("子线程执行异常", e);
                        updateObjLogStatus(objLog, "FAILED", "系统异常: " + e.getMessage(), 0);
                    } finally {
                        latch.countDown(); // 无论成功失败，计数器减一
                    }
                });
            }

            // 主线程在此阻塞，直到所有子任务完成
            latch.await();

        } catch(InterruptedException e) {
            log.error("主线程被中断", e);
            Thread.currentThread().interrupt();
        } finally {
            // 关闭线程池
            executor.shutdown();
            runningFlags.remove(jobId); // 清理标志位
        }
        // --- 多线程执行核心逻辑 End ---

        // 5. 汇总结果
        Long failedCount = objLogMapper.selectCount(new LambdaQueryWrapper<SfDataRunObjLog>()
                .eq(SfDataRunObjLog::getRunLogId, runLog.getId())
                .eq(SfDataRunObjLog::getStatus, "FAILED"));

        Long abortedCount = objLogMapper.selectCount(new LambdaQueryWrapper<SfDataRunObjLog>()
                .eq(SfDataRunObjLog::getRunLogId, runLog.getId())
                .eq(SfDataRunObjLog::getStatus, "ABORTED"));

        String finalStatus = "FINISHED";
        if(failedCount > 0) finalStatus = "PARTIAL_SUCCESS";
        if(abortedCount > 0 && failedCount == 0) finalStatus = "FINISHED"; // 停止也算完成的一种

        finishRunLog(runLog, job, finalStatus, null);
    }

    /**
     * 单个对象的执行逻辑 (优化版：云端并发提取 + 严格磁盘回收)
     */
    private void executeObjectLog(SfDataJob job, SfDataRunObjLog objLog) {
        String srcPath = "/tmp/sf_reconcile/" + job.getId() + "_" + objLog.getId() + "_src.csv";
        String tgtPath = "/tmp/sf_reconcile/" + job.getId() + "_" + objLog.getId() + "_tgt.csv";
        File srcFile = new File(srcPath);
        File tgtFile = new File(tgtPath);

        try {
            updateObjLogStatus(objLog, "RUNNING", "正在初始化...", 5);
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "初始化配置...", 5);

            if(!isRunning(job.getId())) {
                updateObjLogStatus(objLog, "ABORTED", "已停止", 0);
                return;
            }

            SfDataObjConfig config = configMapper.selectById(objLog.getObjConfigId());

            // 1. 构建 SOQL
            String srcKey = StringUtils.defaultIfEmpty(config.getSourceKeyField(), "Id");
            String srcSoql = buildDynamicSoql(job.getSourceOrgId(), config, srcKey);
            String tgtKey = StringUtils.defaultIfEmpty(config.getTargetKeyField(), "Id");
            String tgtSoql = buildDynamicSoql(job.getTargetOrgId(), config, tgtKey);

            if(!isRunning(job.getId())) return;

            // 【核心优化 1：云端并发下发】让源和目标在 Salesforce 云端同时开始提取数据，节省 50% 等待时间
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "下发双端并行提取指令...", 10);
            String srcJobId = bulkApiService.submitQueryJob(job.getSourceOrgId(), srcSoql);
            String tgtJobId = bulkApiService.submitQueryJob(job.getTargetOrgId(), tgtSoql);

            // 2. 依次等待云端处理完成并获取行数
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "等待云端打包源数据...", 20);
            waitForJob(job.getSourceOrgId(), srcJobId);
            int srcRows = bulkApiService.getJobRecordCount(job.getSourceOrgId(), srcJobId);

            publishProgress(job.getId(), objLog.getId(), "RUNNING", "等待云端打包目标数据...", 30);
            waitForJob(job.getTargetOrgId(), tgtJobId);
            int tgtRows = bulkApiService.getJobRecordCount(job.getTargetOrgId(), tgtJobId);

            if(!isRunning(job.getId())) return;

            // 3. 依次拉取到本地硬盘
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "正在下载源环境数据...", 40);
            srcFile = bulkApiService.downloadResult(job.getSourceOrgId(), srcJobId, srcPath);

            publishProgress(job.getId(), objLog.getId(), "RUNNING", "正在下载目标环境数据...", 55);
            tgtFile = bulkApiService.downloadResult(job.getTargetOrgId(), tgtJobId, tgtPath);

            if(!isRunning(job.getId())) return;

            // 4. 执行本地比对算法
            int totalEstimatedRows = srcRows + tgtRows;
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "数据就绪，引擎极速碰撞中...", 70);
            String resPath = "/tmp/sf_reconcile/res_" + objLog.getId() + ".csv";
            File resFile = new File(resPath);

            java.util.function.Consumer<Integer> progressCallback = (pct) -> {
                if(isRunning(job.getId())) {
                    objLog.setProgress(pct);
                    objLogMapper.updateById(objLog);
                    publishProgress(job.getId(), objLog.getId(), "RUNNING", "正在比对数据...", pct);
                }
            };

            SfReconcileAlgorithm.ReconcileStats stats = algorithm.execute(srcFile, tgtFile, resFile, config, totalEstimatedRows, progressCallback);

            // 5. 保存结果
            objLog.setTotalSource(stats.getTotalSource());
            objLog.setTotalTarget(stats.getTotalTarget());
            objLog.setDiffCount(stats.getDiffCount());
            objLog.setResultFilePath(resPath);

            updateObjLogStatus(objLog, "FINISHED", "比对完成", 100);
            publishProgress(job.getId(), objLog.getId(), "FINISHED", "完成", 100);

        } catch(Exception e) {
            log.error("对象 [" + objLog.getObjectName() + "] 执行失败", e);
            updateObjLogStatus(objLog, "FAILED", e.getMessage(), 0);
            publishProgress(job.getId(), objLog.getId(), "FAILED", "异常: " + e.getMessage(), 0);
        } finally {
            // 【核心优化 2：终极防泄漏兜底】无论算法是否因为脏数据或者内存溢出崩溃，在此处必定销毁源和目标CSV巨型文件
            FileUtil.del(srcFile);
            FileUtil.del(tgtFile);
            // 备注：resFile (差异结果文件) 故意不删，留给用户从前端点击下载
        }
    }

    // 辅助方法：检查任务是否还在运行
    private boolean isRunning(Long jobId) {
        return runningFlags.containsKey(jobId);
    }

    /**
     * 动态构建 SOQL (含排序)
     */
    private String buildDynamicSoql(Long orgId, SfDataObjConfig config, String keyField) {
        String objectName = config.getObjectName();

        // 获取元数据
        List<Map<String, Object>> fieldsMeta;
        try {
            fieldsMeta = describeApiService.getSObjectFields(orgId, objectName);
        } catch(Exception e) {
            throw new RuntimeException("获取元数据失败: " + e.getMessage());
        }

        Set<String> excludedSet = new HashSet<>();
        if(StringUtils.isNotEmpty(config.getExcludedFields())) {
            excludedSet.addAll(Arrays.asList(config.getExcludedFields().split(",")));
        }

        Map<String, JSONObject> relationMap = new HashMap<>();
        if(StringUtils.isNotEmpty(config.getMappingConfig())) {
            try {
                relationMap = JSON.parseObject(config.getMappingConfig(), new TypeReference<Map<String, JSONObject>>() {
                });
            } catch(Exception e) {
            }
        }

        Set<String> queryFields = new LinkedHashSet<>();

        // 确保 KeyField 存在
        String effectiveKeyField = StringUtils.isEmpty(keyField) ? "Id" : keyField;
        queryFields.add(effectiveKeyField);

        for(Map<String, Object> field : fieldsMeta) {
            String apiName = (String) field.get("name");
            String type = (String) field.get("type");

            if(excludedSet.contains(apiName)) continue;
            if("base64".equalsIgnoreCase(type) || "address".equalsIgnoreCase(type)) continue;

            if("reference".equalsIgnoreCase(type) && relationMap.containsKey(apiName)) {
                JSONObject mappingObj = relationMap.get(apiName);
                if(mappingObj != null && mappingObj.containsKey("targetPath")) {
                    String targetPath = mappingObj.getString("targetPath");
                    queryFields.add(targetPath);
                } else {
                    queryFields.add(apiName);
                }
            } else {
                queryFields.add(apiName);
            }
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", queryFields));
        sb.append(" FROM ").append(objectName);
        if(StringUtils.isNotEmpty(config.getSyncFilterLogic())) {
            sb.append(" WHERE ").append(config.getSyncFilterLogic());
        }

        // 强制排序：ORDER BY Key ASC, Id ASC
        sb.append(" ORDER BY ").append(effectiveKeyField).append(" ASC");
        if(!"Id".equalsIgnoreCase(effectiveKeyField)) {
            sb.append(", Id ASC");
        }

        String finalSoql = sb.toString();
        log.info("生成对象 [{}] 的 SOQL: {}", objectName, finalSoql);
        return finalSoql;
    }

    // --- 辅助方法 ---

    private void updateObjLogStatus(SfDataRunObjLog log, String status, String msg, int progress) {
        log.setStatus(status);
        if(msg != null) log.setErrorMsg(msg);
        log.setProgress(progress);
        log.setUpdateTime(new Date());
        objLogMapper.updateById(log);
    }

    private void finishRunLog(SfDataRunLog runLog, SfDataJob job, String status, String msg) {
        runLog.setStatus(status);
        if(msg != null) runLog.setErrorMsg(msg);
        runLog.setEndTime(new Date());
        runLogMapper.updateById(runLog);

        job.setStatus("FINISHED");
        jobMapper.updateById(job);

        publishProgress(job.getId(), null, "ALL_FINISHED", "任务结束", 100);
    }

    /**
     * 等待 Bulk Job 完成 (详细实现)
     */
    private void waitForJob(Long orgId, String jobId) throws InterruptedException {
        // 轮询 180 次，每次 2 秒，共 6 分钟
        for(int i = 0; i < 180; i++) {
            String state = bulkApiService.checkJobStatus(orgId, jobId);

            // 成功
            if("JobComplete".equals(state)) {
                return;
            }

            // 失败或终止
            if("Failed".equals(state) || "Aborted".equals(state)) {
                String error = bulkApiService.getErrorMessage(orgId, jobId);
                throw new RuntimeException("Bulk Job Error: " + error);
            }

            // 等待下一次轮询
            Thread.sleep(2000);
        }
        // 超时抛出异常
        throw new RuntimeException("Bulk Job Timeout (6 min limit)");
    }

    private void publishProgress(Long jobId, Long objLogId, String status, String msg, int percent) {
        JSONObject json = new JSONObject();
        json.put("type", "OBJ_PROGRESS");
        json.put("jobId", jobId);
        json.put("objLogId", objLogId);
        json.put("status", status);
        json.put("message", msg);
        json.put("percent", percent);
        DeployWebSocketServer.sendMessage("reconcile_" + jobId, json.toJSONString());
    }

    /**
     * 重试单个对象 (完整实现)
     */
    @Async
    @Override
    public void retryObject(Long objLogId) {
        SfDataRunObjLog objLog = objLogMapper.selectById(objLogId);
        if(objLog == null) return;

        SfDataJob job = jobMapper.selectById(objLog.getJobId());
        if(job == null) return;

        log.info(">>>>>> 开始重试对象: [{}], JobId: [{}], ObjLogId: [{}]", objLog.getObjectName(), job.getId(), objLog.getId());

        // 1. 重置日志状态
        objLog.setStatus("WAITING");
        objLog.setErrorMsg("");
        objLog.setProgress(0);
        objLog.setTotalSource(0);
        objLog.setTotalTarget(0);
        objLog.setDiffCount(0);
        objLog.setResultFilePath(""); // 清空旧结果路径
        objLog.setUpdateTime(new Date());
        objLogMapper.updateById(objLog);

        // 2. 立即推送 "WAITING" 状态 (确保 JobId 正确)
        publishProgress(job.getId(), objLog.getId(), "WAITING", "进入重试队列...", 0);

        // 3. 设置运行标志 (防止被 executeObjectLog 中的 isRunning 拦截)
        runningFlags.put(job.getId(), true);

        try {
            // 稍作停顿确保前端收到 WAITING 消息
            Thread.sleep(100);

            // 4. 执行核心逻辑
            // executeObjectLog 内部会推送 RUNNING (5%) -> ... -> FINISHED (100%)
            executeObjectLog(job, objLog);

        } catch(Exception e) {
            log.error("重试执行异常", e);
            updateObjLogStatus(objLog, "FAILED", "重试异常: " + e.getMessage(), 0);
            publishProgress(job.getId(), objLog.getId(), "FAILED", "执行异常", 0);
        }
    }
}
