package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sf_deployment_history")
public class SfDeploymentHistory {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deploymentId;      // 关联的部署包ID
    private Long orgId;             // 目标环境ID
    private String type;            // Validate(仅验证), Deploy(完整部署), Quick(快速部署), Rollback(回滚)
    private String status;          // Processing, Succeeded, Failed, Canceled
    private String backupPath;      // 备份文件存储路径 (仅Deploy/Rollback有值)
    private String deployAsyncId;   // Salesforce 任务ID
    private Date startTime;
    private Date endTime;
    private String errorMsg;
    /**
     * 创建者
     * 使用 fill = FieldFill.INSERT 标记
     */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新者
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 备注
     */
    private String remark;

    /** 部署包标题 (非数据库字段) */
    @TableField(exist = false)
    private String deploymentTitle;
}
