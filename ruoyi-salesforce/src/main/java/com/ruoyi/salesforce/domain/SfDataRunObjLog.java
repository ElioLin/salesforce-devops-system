package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("sf_data_run_obj_log")
public class SfDataRunObjLog implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runLogId;      // 父级日志ID
    private Long jobId;         // 任务ID
    private Long objConfigId;   // 配置ID
    private String objectName;  // 对象名
    @TableField(exist = false)
    private String objectLabel;

    private String status;      // WAITING, RUNNING, FINISHED, FAILED
    private Integer progress;   // 进度
    private Integer totalSource;
    private Integer totalTarget;
    private Integer diffCount;
    private String resultFilePath;
    private String errorMsg;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /**
     * 租户归属ID (SaaS公司隔离)
     */
    private String tenantId;
}
