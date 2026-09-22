package com.ruoyi.salesforce.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDataJob;
import com.ruoyi.salesforce.domain.SfDataRunLog;
import com.ruoyi.salesforce.domain.SfDataRunObjLog;
import com.ruoyi.salesforce.mapper.SfDataJobMapper;
import com.ruoyi.salesforce.mapper.SfDataRunLogMapper;
import com.ruoyi.salesforce.mapper.SfDataRunObjLogMapper;
import com.ruoyi.salesforce.service.ISfDataRunObjLogService;
import com.ruoyi.salesforce.service.ISfDescribeApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.mapper.SfDataObjConfigMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对象级运行日志 Service 实现类
 */
@Slf4j
@Service
public class SfDataRunObjLogServiceImpl implements ISfDataRunObjLogService {

    @Autowired
    private SfDataRunObjLogMapper objLogMapper;

    @Autowired
    private SfDataJobMapper jobMapper;

    @Autowired
    private SfDataRunLogMapper runLogMapper;

    @Autowired
    private ISfDescribeApiService describeApiService;

    @Autowired
    private SfDataObjConfigMapper configMapper;

    @Override
    public List<SfDataRunObjLog> selectListByRunLogId(Long runLogId) {
        return objLogMapper.selectList(new LambdaQueryWrapper<SfDataRunObjLog>()
                .eq(SfDataRunObjLog::getRunLogId, runLogId)
                .orderByAsc(SfDataRunObjLog::getId));
    }

    @Override
    public SfDataRunObjLog selectById(Long id) {
        return objLogMapper.selectById(id);
    }

    /**
     * 核心功能：读取本地 CSV 文件并进行内存分页和筛选
     * 【终极优化版：O(1) 空间复杂度，采用流式游标读取，彻底免疫 GB 级大文件导致的 OOM】
     */
    @Override
    public Map<String, Object> previewCsvData(Long objLogId, int pageNum, int pageSize, String diffType, String fieldName, Boolean excludePostCutoff) {
        // 1. 获取日志记录
        SfDataRunObjLog objLog = objLogMapper.selectById(objLogId);
        if(objLog == null) {
            throw new ServiceException("日志记录不存在");
        }

        String path = objLog.getResultFilePath();
        if(StringUtils.isEmpty(path)) {
            return emptyResult();
        }

        File file = new File(path);
        if(!file.exists()) {
            return emptyResult();
        }

        // 2. 初始化 CSV 配置
        CsvReadConfig config = CsvReadConfig.defaultConfig();
        config.setFieldSeparator(',');
        config.setTextDelimiter('\"');

        // 3. 核心流式读取引擎状态位
        // counters[0] = 表头跳过标志, counters[1] = 符合过滤条件的总行数 (用于给前端计算分页)
        final int[] counters = {0, 0};
        List<Map<String, String>> pageResult = new ArrayList<>();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = fromIndex + pageSize;

        // ==========================================
        // 【核心修复：完美兼容各版本 Hutool】
        // 显式创建 BufferedReader，交由 try-with-resources 自动安全关闭流
        // ==========================================
        try(java.io.BufferedReader reader = FileUtil.getReader(file, StandardCharsets.UTF_8)) {

            // 调用你版本中支持的 API：read(Reader reader, boolean close, CsvRowHandler rowHandler)
            // 第二个参数传 false，因为最外层的 try() 已经保证了安全关闭
            CsvUtil.getReader(config).read(reader, false, (CsvRow row) -> {
                // 跳过第一行的表头
                if(counters[0] == 0) {
                    counters[0] = 1;
                    return;
                }

                // 确保列数足够防越界
                if(row.size() < 6) return;

                String rowDiffType = row.get(2);
                String rowFieldName = row.get(3);

                // 筛选条件匹配
                boolean matchDiff = StringUtils.isEmpty(diffType) || (rowDiffType != null && rowDiffType.equalsIgnoreCase(diffType));
                boolean matchField = StringUtils.isEmpty(fieldName) || (rowFieldName != null && rowFieldName.toLowerCase().contains(fieldName.toLowerCase()));

                if(matchDiff && matchField) {
                    // 如果该行命中了当前的页码范围，才将其组装成 Map 加入返回结果集
                    if(counters[1] >= fromIndex && counters[1] < toIndex) {
                        Map<String, String> item = new HashMap<>();
                        item.put("sourceKey", row.get(0));
                        item.put("targetKey", row.get(1));
                        item.put("diffType", row.get(2));
                        item.put("fieldName", row.get(3));
                        item.put("sourceValue", row.get(4));
                        item.put("targetValue", row.get(5));
                        if(row.size() >= 10) {
                            item.put("sourceCreatedDate", row.get(6));
                            item.put("targetCreatedDate", row.get(7));
                            item.put("sourceLastModifiedDate", row.get(8));
                            item.put("targetLastModifiedDate", row.get(9));
                        }
                        if(row.size() >= 12) {
                            item.put("sourceId", row.get(10));
                            item.put("targetId", row.get(11));
                        }
                        pageResult.add(item);
                    }
                    // 只要匹配，总数就累加，最后扔给前端作为 total
                    counters[1]++;
                }
            });
        } catch(Exception e) {
            log.error("读取结果文件失败: {}", path, e);
            throw new ServiceException("文件读取流式处理失败，可能是文件损坏");
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", counters[1]); // 返回精确的过滤后总条数
        resultMap.put("rows", pageResult);   // 返回且仅返回当前页的 50 或 100 条数据
        return resultMap;
    }

    private Map<String, Object> emptyResult() {
        Map<String, Object> map = new HashMap<>();
        map.put("total", 0);
        map.put("rows", Collections.emptyList());
        return map;
    }

    /**
     * 获取监控数据的业务逻辑实现 (全新重构版)
     */
    @Override
    public Map<String, Object> getMonitorData(Long jobId) {
        Map<String, Object> result = new HashMap<>();

        // 1. 获取任务信息
        SfDataJob job = jobMapper.selectById(jobId);
        if(job == null) {
            throw new RuntimeException("任务不存在");
        }
        result.put("jobName", job.getJobName());

        // 【核心修复 1】获取该任务当前真实的【对象配置清单】作为绝对基准
        List<SfDataObjConfig> configs = configMapper.selectList(
                new LambdaQueryWrapper<SfDataObjConfig>().eq(SfDataObjConfig::getJobId, jobId)
        );

        // 2. 找到该任务最近一次的主日志
        SfDataRunLog lastRun = runLogMapper.selectOne(new LambdaQueryWrapper<SfDataRunLog>()
                .eq(SfDataRunLog::getJobId, jobId)
                .orderByDesc(SfDataRunLog::getStartTime)
                .last("LIMIT 1"));

        // 3. 将最近一次运行的对象日志转为 Map，以便按对象名称快速匹配
        Map<String, SfDataRunObjLog> logMap = new HashMap<>();
        if(lastRun != null) {
            List<SfDataRunObjLog> lastLogs = this.selectListByRunLogId(lastRun.getId());
            for(SfDataRunObjLog log : lastLogs) {
                logMap.put(log.getObjectName(), log);
            }
        }

        // 4. 获取元数据字典，用于翻译中文 Label
        Map<String, String> nameToLabelMap = new HashMap<>();
        try {
            List<Map<String, String>> metaList = describeApiService.getSObjectList(job.getSourceOrgId());
            if(metaList != null) {
                for(Map<String, String> meta : metaList) {
                    nameToLabelMap.put(meta.get("name"), meta.get("label"));
                }
            }
        } catch(Exception e) {
            log.warn("获取元数据失败，将降级显示API Name: {}", e.getMessage());
        }

        // 【核心修复 2】基于配置清单拼装最终视图，巧妙处理新增对象
        List<SfDataRunObjLog> resultList = new ArrayList<>();
        for(SfDataObjConfig config : configs) {
            // 尝试去历史日志中匹配
            SfDataRunObjLog logInfo = logMap.get(config.getObjectName());

            if(logInfo == null) {
                // 如果是新加的对象，尚未运行过，伪造一条空闲状态的记录供前端无缝展示
                logInfo = new SfDataRunObjLog();
                // 故意不设置 ID，避免触发错误逻辑
                logInfo.setObjectName(config.getObjectName());
                logInfo.setJobId(jobId);
                logInfo.setObjConfigId(config.getId());
                logInfo.setStatus("IDLE"); // 关键：标记为空闲待执行
                logInfo.setProgress(0);
            }

            // 回填前端所需的中文 Label
            String label = nameToLabelMap.get(logInfo.getObjectName());
            logInfo.setObjectLabel(StringUtils.isNotEmpty(label) ? label : logInfo.getObjectName());

            resultList.add(logInfo);
        }

        result.put("list", resultList);
        return result;
    }
}
