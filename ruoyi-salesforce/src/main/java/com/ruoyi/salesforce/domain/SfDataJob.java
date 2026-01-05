package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据比对任务主表 sf_data_job
 * 优化：解耦 BaseEntity，使用 MP 注解管理字段
 */
@Data
@TableName("sf_data_job")
public class SfDataJob implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobName;

    private Long sourceOrgId;

    private Long targetOrgId;

    private String status; // IDLE, RUNNING, PAUSED

    private Integer concurrentLimit;

    // --- 手动接管 BaseEntity 的常用字段 ---

    /** 创建者 */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新者 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /** 备注 (数据库需有此字段) */
    private String remark;

    /** * 请求参数 (用于前端查询传参，如日期范围)
     * exist = false 表示该字段不映射到数据库列
     */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> params = new HashMap<>();
}