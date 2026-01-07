package com.ruoyi.salesforce.service.impl;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
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

import cn.hutool.core.text.csv.CsvUtil;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvReadConfig;

import java.nio.charset.StandardCharsets;

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
            List<String> srcFields = buildDynamicFields(job.getSourceOrgId(), config, config.getSourceKeyField(), false);
            String srcSoql = buildSoql(config.getObjectName(), srcFields, config.getSyncFilterLogic());
            log.info("源 SOQL: " + srcSoql);
            List<String> tgtFields = buildDynamicFields(job.getTargetOrgId(), config, config.getTargetKeyField(), true);
            String tgtSoql = buildSoql(config.getObjectName(), tgtFields, config.getSyncFilterLogic());
            log.info("目标 SOQL: " + tgtSoql);
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
     * 核心优化：构建 SOQL 字段列表 (支持深度关联映射配置)
     */
    private List<String> buildDynamicFields(Long orgId, SfDataObjConfig config, String keyField, boolean isTarget) throws Exception {
        String objName = config.getObjectName();

        // 1. 获取所有元数据字段
        List<Map<String, Object>> allFields = sfDescribeApiService.getSObjectFields(orgId, objName);
        if(allFields == null || allFields.isEmpty()) return Collections.singletonList(keyField);

        boolean isMdt = StringUtils.isNotEmpty(objName) && objName.toLowerCase().endsWith("__mdt");

        // 2. 解析前端传递的映射配置 JSON
        // 结构: { "FieldName": { "type": "REFERENCE", "sourcePath": "...", "targetPath": "..." } }
        Map<String, Map<String, String>> mappingConfig = new HashMap<>();
        if(StringUtils.isNotEmpty(config.getMappingConfig())) {
            try {
                mappingConfig = JSON.parseObject(config.getMappingConfig(),
                        new TypeReference<Map<String, Map<String, String>>>() {
                        });
            } catch(Exception e) {
                log.error("解析映射配置失败", e);
            }
        }

        // 3. 处理排除字段
        Set<String> excludedSet = new HashSet<>();
        if(StringUtils.isNotEmpty(config.getExcludedFields())) {
            for(String f : config.getExcludedFields().split(",")) excludedSet.add(f.trim().toLowerCase());
        }

        List<String> finalFields = new ArrayList<>();
        finalFields.add(keyField); // 确保主键存在

        for(Map<String, Object> field : allFields) {
            String name = getMapValueStr(field, "name");
            String type = getMapValueStr(field, "type");
            String relationshipName = getMapValueStr(field, "relationshipName");
            boolean queryable = getMapValueBool(field, "queryable", true);

            // 基础过滤
            if(StringUtils.isEmpty(name) || name.equalsIgnoreCase(keyField)) continue;
            if(excludedSet.contains(name.toLowerCase())) continue;
            if(!queryable && !isMdt) continue;
            if("base64".equalsIgnoreCase(type) || "address".equalsIgnoreCase(type) || "location".equalsIgnoreCase(type))
                continue;

            // --- 核心逻辑升级 ---

            // 检查是否有自定义映射配置
            if(mappingConfig.containsKey(name)) {
                Map<String, String> configItem = mappingConfig.get(name);
                String path = isTarget ? configItem.get("targetPath") : configItem.get("sourcePath");

                if(StringUtils.isNotEmpty(path)) {
                    // 如果配置了路径 (例如 Owner.Email)，直接使用配置的路径
                    log.info("应用自定义映射: 字段 [{}] -> SOQL [{}]", name, path);
                    finalFields.add(path);
                    continue; // 处理完毕，跳过默认逻辑
                }
            }

            // 默认逻辑 (如果没有配置，或者是普通字段)
            if(isTarget && "reference".equalsIgnoreCase(type) && StringUtils.isNotEmpty(relationshipName)) {
                // 默认降级策略：尝试自动寻找 Source_Org_Id__c
                // 只有在没配置的情况下才走这个默认逻辑，保证兼容性
                String defaultTargetRelField = relationshipName + "." + config.getTargetKeyField();
                finalFields.add(defaultTargetRelField);
            } else {
                finalFields.add(name);
            }
        }
        return finalFields;
    }

    // --- 必须包含这两个辅助方法 ---

    private String getMapValueStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if(val == null) val = map.get(StringUtils.capitalize(key)); // Try "Name"
        if(val == null) val = map.get(key.toLowerCase()); // Try "name"
        return val == null ? null : val.toString();
    }

    private boolean getMapValueBool(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        // 依次尝试 "Queryable", "queryable"
        if(val == null) val = map.get(StringUtils.capitalize(key));
        if(val == null) val = map.get(key.toLowerCase());

        if(val == null) return defaultValue; // 这一步至关重要：如果没找到，默认它是可以查询的
        if(val instanceof Boolean) return (Boolean) val;
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

    /**
     * 在线预览比对结果 (支持分页与筛选)
     */
    @Override
    public Map<String, Object> previewCsvData(Long jobId, int pageNum, int pageSize, String diffType, String fieldName) {
        // 1. 获取任务信息
        // 修复：如果 Mapper 中没有 selectSfDataJobById，直接用 MyBatis Plus 的 selectById
        SfDataJob job = jobMapper.selectById(jobId);
        if(job == null) {
            throw new ServiceException("任务不存在");
        }

        // 2. 定位结果文件
        // 假设文件存储在临时目录，文件名为 reconcile_res_{jobId}.csv
        // 生产环境建议在 SfDataJob 中增加 resultFilePath 字段来存储确切路径
        String fileName = "reconcile_res_" + job.getId() + ".csv";
        File file = new File("/tmp/sf_reconcile/" + fileName);

        // 容错：如果找不到，尝试在同目录下模糊搜索
        if(!file.exists()) {
            File dir = new File("/tmp/sf_reconcile/");
            if(dir.exists() && dir.isDirectory()) {
                File[] match = dir.listFiles((d, name) -> name.startsWith("reconcile_res_") && name.contains(job.getId().toString()) && name.endsWith(".csv"));
                if(match != null && match.length > 0) {
                    file = match[0];
                }
            }
        }

        if(!file.exists()) {
            // 修复 Map.of 报错 (JDK 8 不支持)
            Map<String, Object> emptyMap = new HashMap<>();
            emptyMap.put("total", 0);
            emptyMap.put("rows", Collections.emptyList());
            return emptyMap;
        }

        // 3. 读取 CSV (使用 Hutool)
        CsvReadConfig config = CsvReadConfig.defaultConfig();
        config.setFieldSeparator(',');
        config.setTextDelimiter('\"');

        List<CsvRow> allRows;
        try {
            // 强制 UTF-8 读取
            allRows = CsvUtil.getReader(config).read(FileUtil.getReader(file, StandardCharsets.UTF_8)).getRows();
        } catch(Exception e) {
            throw new ServiceException("结果文件读取失败: " + e.getMessage());
        }

        if(allRows == null || allRows.isEmpty()) {
            Map<String, Object> emptyMap = new HashMap<>();
            emptyMap.put("total", 0);
            emptyMap.put("rows", Collections.emptyList());
            return emptyMap;
        }

        // 4. 解析数据 (跳过表头)
        List<CsvRow> dataRows = allRows.size() > 1 ? allRows.subList(1, allRows.size()) : Collections.emptyList();

        // 5. 内存过滤
        List<Map<String, String>> filteredList = new ArrayList<>();
        for(CsvRow row : dataRows) {
            // 确保列数足够 (Source_Key, Target_Key, Diff_Type, Field_Name, Source_Value, Target_Value)
            if(row.size() < 6) continue;

            String rowDiffType = row.get(2);
            String rowFieldName = row.get(3);

            // 筛选条件匹配
            boolean matchDiff = StringUtils.isEmpty(diffType) || (rowDiffType != null && rowDiffType.equalsIgnoreCase(diffType));
            boolean matchField = StringUtils.isEmpty(fieldName) || (rowFieldName != null && rowFieldName.toLowerCase().contains(fieldName.toLowerCase()));

            if(matchDiff && matchField) {
                Map<String, String> item = new HashMap<>();
                item.put("sourceKey", row.get(0));
                item.put("targetKey", row.get(1));
                item.put("diffType", row.get(2));
                item.put("fieldName", row.get(3));
                item.put("sourceValue", row.get(4));
                item.put("targetValue", row.get(5));
                filteredList.add(item);
            }
        }

        // 6. 内存分页
        int total = filteredList.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<Map<String, String>> pageResult = new ArrayList<>();
        if(fromIndex < total) {
            pageResult = filteredList.subList(fromIndex, toIndex);
        }

        // JDK 8 写法
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", total);
        resultMap.put("rows", pageResult);

        return resultMap;
    }
}