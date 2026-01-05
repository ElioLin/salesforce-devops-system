package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 执行结果日志表 sf_data_run_log
 * 优化：轻量级日志实体
 */
@Data
@TableName("sf_data_run_log")
public class SfDataRunLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;
    private Long configId;
    private String runBatchNo;

    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    private Integer totalSourceRows;
    private Integer totalTargetRows;
    private Integer diffRowCount;
    private Integer missingTargetCount;

    private String resultFilePath;
    private String errorMsg;
}