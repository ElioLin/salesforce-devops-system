package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfDataRunObjLog;

import java.util.List;
import java.util.Map;

public interface ISfDataRunObjLogService {

    /**
     * 根据主日志ID查询对象日志列表
     */
    List<SfDataRunObjLog> selectListByRunLogId(Long runLogId);

    /**
     * 获取单个对象日志详情
     */
    SfDataRunObjLog selectById(Long id);

    /**
     * 在线预览 CSV 结果文件
     *
     * @param objLogId  对象日志ID
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param diffType  差异类型过滤
     * @param fieldName 字段名过滤
     * @return 分页数据 Map(total, rows)
     */
    Map<String, Object> previewCsvData(Long objLogId, int pageNum, int pageSize, String diffType, String fieldName);

    /**
     * 获取任务监控聚合数据 (包含任务名、对象列表、中文Label)
     */
    Map<String, Object> getMonitorData(Long jobId);
}
