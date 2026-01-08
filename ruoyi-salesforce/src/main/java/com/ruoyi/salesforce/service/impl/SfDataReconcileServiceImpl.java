package com.ruoyi.salesforce.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Salesforce 数据比对核心执行引擎 (最终修正版)
 * 逻辑：Waiting队列 -> 逐个执行 -> 动态字段获取(含排除逻辑) -> 独立结果文件
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

    // 运行标志位 (JobId -> Boolean)
    private static final Map<Long, Boolean> runningFlags = new ConcurrentHashMap<>();

    @Override
    public void stopJob(Long jobId) {
        if(jobId != null) {
            runningFlags.remove(jobId);
            log.info("任务 [{}] 停止指令已下达", jobId);
        }
    }

    /**
     * 核心入口：异步执行比对任务
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

        // 2. 获取所有启用的对象配置
        List<SfDataObjConfig> configs = configMapper.selectList(new LambdaQueryWrapper<SfDataObjConfig>()
                .eq(SfDataObjConfig::getJobId, jobId)
                .eq(SfDataObjConfig::getIsActive, "Y"));

        if(configs.isEmpty()) {
            finishRunLog(runLog, job, "FINISHED", "未找到启用的对象配置");
            return;
        }

        // 3. 预生成所有对象的 WAITING 日志
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

        // 标记任务开始
        runningFlags.put(jobId, true);
        job.setStatus("RUNNING");
        jobMapper.updateById(job);
        publishProgress(jobId, null, "INIT", "任务已启动，等待执行...", 0);

        try {
            // 4. 顺序执行每个对象
            for(SfDataRunObjLog objLog : queue) {
                // 检查停止标志
                if(!runningFlags.containsKey(jobId)) {
                    updateObjLogStatus(objLog, "ABORTED", "用户手动停止", 0);
                    continue;
                }

                // 执行核心逻辑
                executeObjectLog(job, objLog);
            }

            // 5. 汇总结果
            Long failedCount = objLogMapper.selectCount(new LambdaQueryWrapper<SfDataRunObjLog>()
                    .eq(SfDataRunObjLog::getRunLogId, runLog.getId())
                    .eq(SfDataRunObjLog::getStatus, "FAILED"));

            String finalStatus = failedCount > 0 ? "PARTIAL_SUCCESS" : "FINISHED";
            finishRunLog(runLog, job, finalStatus, null);

        } catch(Exception e) {
            log.error("任务执行发生未捕获异常", e);
            finishRunLog(runLog, job, "FAILED", e.getMessage());
        } finally {
            runningFlags.remove(jobId);
        }
    }

    /**
     * 单个对象的执行逻辑
     */
    private void executeObjectLog(SfDataJob job, SfDataRunObjLog objLog) {
        try {
            updateObjLogStatus(objLog, "RUNNING", "正在初始化...", 5);
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "初始化配置...", 5);

            SfDataObjConfig config = configMapper.selectById(objLog.getObjConfigId());

            // 1. 动态构建 SOQL (核心：全量字段 - 排除字段 + 关联映射)
            String srcSoql = buildDynamicSoql(job.getSourceOrgId(), config);
            // 目标环境目前假设结构相同，或者根据需求传入 targetOrgId
            String tgtSoql = buildDynamicSoql(job.getTargetOrgId(), config);

            // 2. 下载源数据
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "下载源数据...", 20);
            String srcJobId = bulkApiService.submitQueryJob(job.getSourceOrgId(), srcSoql);
            waitForJob(job.getSourceOrgId(), srcJobId);
            String srcPath = "/tmp/sf_reconcile/" + job.getId() + "_" + objLog.getId() + "_src.csv";
            File srcFile = bulkApiService.downloadResult(job.getSourceOrgId(), srcJobId, srcPath);

            // 3. 下载目标数据
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "下载目标数据...", 40);
            String tgtJobId = bulkApiService.submitQueryJob(job.getTargetOrgId(), tgtSoql);
            waitForJob(job.getTargetOrgId(), tgtJobId);
            String tgtPath = "/tmp/sf_reconcile/" + job.getId() + "_" + objLog.getId() + "_tgt.csv";
            File tgtFile = bulkApiService.downloadResult(job.getTargetOrgId(), tgtJobId, tgtPath);

            // 4. 执行比对算法
            publishProgress(job.getId(), objLog.getId(), "RUNNING", "执行比对算法...", 70);
            String resPath = "/tmp/sf_reconcile/res_" + objLog.getId() + ".csv";
            File resFile = new File(resPath);

            SfReconcileAlgorithm.ReconcileStats stats = algorithm.execute(srcFile, tgtFile, resFile, config);

            // 5. 保存结果
            objLog.setTotalSource(stats.getTotalSource());
            objLog.setTotalTarget(stats.getTotalTarget());
            objLog.setDiffCount(stats.getDiffCount());
            objLog.setResultFilePath(resPath);

            updateObjLogStatus(objLog, "FINISHED", "比对完成", 100);
            publishProgress(job.getId(), objLog.getId(), "FINISHED", "完成", 100);

            // 清理临时文件
            FileUtil.del(srcFile);
            FileUtil.del(tgtFile);

        } catch(Exception e) {
            log.error("对象 [" + objLog.getObjectName() + "] 执行失败", e);
            updateObjLogStatus(objLog, "FAILED", e.getMessage(), 0);
            publishProgress(job.getId(), objLog.getId(), "FAILED", "异常: " + e.getMessage(), 0);
        }
    }

    /**
     * 动态构建 SOQL
     * 逻辑：
     * 1. 调接口获取该对象所有字段。
     * 2. 解析 excludedFields，剔除黑名单字段。
     * 3. 解析 mappingConfig (JSON)，处理关联字段映射。
     * 4. 拼接 SELECT 语句。
     */
    /**
     * 动态构建 SOQL
     */
    private String buildDynamicSoql(Long orgId, SfDataObjConfig config) {
        String objectName = config.getObjectName();

        // A. 获取元数据
        List<Map<String, Object>> fieldsMeta;
        try {
            fieldsMeta = describeApiService.getSObjectFields(orgId, objectName);
        } catch(Exception e) {
            throw new RuntimeException("获取元数据失败: " + e.getMessage());
        }

        // B. 解析 excludedFields
        Set<String> excludedSet = new HashSet<>();
        if(StringUtils.isNotEmpty(config.getExcludedFields())) {
            excludedSet.addAll(Arrays.asList(config.getExcludedFields().split(",")));
        }

        // C. 解析 mappingConfig (核心修复点)
        // 前端存的是: {"OwnerId": {"targetPath": "Owner.Source_Org_Id__c", ...}}
        // 所以这里要解析成 Map<String, JSONObject>
        Map<String, JSONObject> relationMap = new HashMap<>();
        if(StringUtils.isNotEmpty(config.getMappingConfig())) {
            try {
                // 【修复】使用 JSONObject 接收复杂结构
                relationMap = JSON.parseObject(config.getMappingConfig(), new TypeReference<Map<String, JSONObject>>() {
                });
            } catch(Exception e) {
                log.warn("MappingConfig 解析失败: {}", config.getMappingConfig());
            }
        }

        // D. 构建查询字段
        Set<String> queryFields = new LinkedHashSet<>();

        String keyField = StringUtils.isEmpty(config.getSourceKeyField()) ? "Id" : config.getSourceKeyField();
        queryFields.add(keyField);

        for(Map<String, Object> field : fieldsMeta) {
            String apiName = (String) field.get("name");
            String type = (String) field.get("type");

            if(excludedSet.contains(apiName)) continue;
            if("base64".equalsIgnoreCase(type) || "address".equalsIgnoreCase(type)) continue;

            // 处理关联字段
            if("reference".equalsIgnoreCase(type) && relationMap.containsKey(apiName)) {
                // 【修复】从 JSONObject 中提取 targetPath
                JSONObject mappingObj = relationMap.get(apiName);
                if(mappingObj != null && mappingObj.containsKey("targetPath")) {
                    // 前端存的 targetPath 已经是完整路径 (例如 Owner.Source_Org_Id__c)
                    // 所以这里直接添加，不需要再拼 relationshipName
                    String targetPath = mappingObj.getString("targetPath");
                    queryFields.add(targetPath);
                } else {
                    queryFields.add(apiName); // 降级处理
                }
            } else {
                queryFields.add(apiName);
            }
        }

        // E. 拼接 SQL
        // 【建议】打印一下生成的 SQL 以便调试
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(String.join(", ", queryFields));
        sb.append(" FROM ").append(objectName);
        if(StringUtils.isNotEmpty(config.getSyncFilterLogic())) {
            sb.append(" WHERE ").append(config.getSyncFilterLogic());
        }

        String finalSoql = sb.toString();
        log.info("生成对象 [{}] 的 SOQL: {}", objectName, finalSoql); // 添加日志方便排查

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

    private void waitForJob(Long orgId, String jobId) throws InterruptedException {
        for(int i = 0; i < 180; i++) { // 最多等 6分钟
            String state = bulkApiService.checkJobStatus(orgId, jobId);
            if("JobComplete".equals(state)) return;
            if("Failed".equals(state) || "Aborted".equals(state)) {
                String error = bulkApiService.getErrorMessage(orgId, jobId);
                throw new RuntimeException("Bulk Job Error: " + error);
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("Bulk Job Timeout");
    }

    private void publishProgress(Long jobId, Long objLogId, String status, String msg, int percent) {
        JSONObject json = new JSONObject();
        json.put("type", "OBJ_PROGRESS");
        json.put("jobId", jobId);
        json.put("objLogId", objLogId);
        json.put("status", status);
        json.put("message", msg);
        json.put("percent", percent);

        // 关键：Key 必须是 "reconcile_" + jobId
        // 确保 jobId 转为 String，否则可能拼接出错
        DeployWebSocketServer.sendMessage("reconcile_" + jobId, json.toJSONString());
    }

    /**
     * 新增：重试单个对象
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

        // 3. 设置运行标志 (防止被拦截)
        runningFlags.put(job.getId(), true);

        try {
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