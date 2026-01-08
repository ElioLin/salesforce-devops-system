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
     */
    @Override
    public Map<String, Object> previewCsvData(Long objLogId, int pageNum, int pageSize, String diffType, String fieldName) {
        // 1. 获取日志记录
        SfDataRunObjLog objLog = objLogMapper.selectById(objLogId);
        if(objLog == null) {
            throw new ServiceException("日志记录不存在");
        }

        String path = objLog.getResultFilePath();
        if(StringUtils.isEmpty(path)) {
            // 如果还没生成文件，返回空数据
            return emptyResult();
        }

        File file = new File(path);
        if(!file.exists()) {
            // 文件丢失或被清理
            return emptyResult();
        }

        // 2. 使用 Hutool 读取 CSV (强制 UTF-8)
        CsvReadConfig config = CsvReadConfig.defaultConfig();
        config.setFieldSeparator(',');
        config.setTextDelimiter('\"');

        List<CsvRow> allRows;
        try {
            allRows = CsvUtil.getReader(config).read(FileUtil.getReader(file, StandardCharsets.UTF_8)).getRows();
        } catch(Exception e) {
            log.error("读取结果文件失败: {}", path, e);
            throw new ServiceException("文件读取失败，可能是文件损坏");
        }

        if(allRows == null || allRows.isEmpty()) {
            return emptyResult();
        }

        // 3. 跳过表头 (第一行是 Source_Key, Target_Key...)
        List<CsvRow> dataRows = allRows.size() > 1 ? allRows.subList(1, allRows.size()) : Collections.emptyList();

        // 4. 内存过滤
        List<Map<String, String>> filteredList = new ArrayList<>();
        for(CsvRow row : dataRows) {
            // 确保列数足够 [0]SrcKey, [1]TgtKey, [2]DiffType, [3]FieldName, [4]SrcVal, [5]TgtVal
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

        // 5. 内存分页
        int total = filteredList.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<Map<String, String>> pageResult = new ArrayList<>();
        if(fromIndex < total) {
            pageResult = filteredList.subList(fromIndex, toIndex);
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", total);
        resultMap.put("rows", pageResult);
        return resultMap;
    }

    private Map<String, Object> emptyResult() {
        Map<String, Object> map = new HashMap<>();
        map.put("total", 0);
        map.put("rows", Collections.emptyList());
        return map;
    }

    /**
     * 获取监控数据的业务逻辑实现
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

        // 2. 找到该任务最近一次的主日志
        SfDataRunLog lastRun = runLogMapper.selectOne(new LambdaQueryWrapper<SfDataRunLog>()
                .eq(SfDataRunLog::getJobId, jobId)
                .orderByDesc(SfDataRunLog::getStartTime)
                .last("LIMIT 1"));

        List<SfDataRunObjLog> list = new ArrayList<>();
        if(lastRun != null) {
            list = this.selectListByRunLogId(lastRun.getId());
        }

        // 3. 填充对象中文名称 (Label)
        if(!list.isEmpty()) {
            try {
                // 获取源组织的元数据缓存
                List<Map<String, String>> metaList = describeApiService.getSObjectList(job.getSourceOrgId());
                Map<String, String> nameToLabelMap = new HashMap<>();
                if(metaList != null) {
                    for(Map<String, String> meta : metaList) {
                        nameToLabelMap.put(meta.get("name"), meta.get("label"));
                    }
                }

                // 回填 Label
                for(SfDataRunObjLog log : list) {
                    String label = nameToLabelMap.get(log.getObjectName());
                    log.setObjectLabel(StringUtils.isNotEmpty(label) ? label : log.getObjectName());
                }
            } catch(Exception e) {
                log.warn("获取元数据失败，将降级显示API Name: {}", e.getMessage());
                for(SfDataRunObjLog log : list) {
                    log.setObjectLabel(log.getObjectName());
                }
            }
        }

        result.put("list", list);
        return result;
    }
}