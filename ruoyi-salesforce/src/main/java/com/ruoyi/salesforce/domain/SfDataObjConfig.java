package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 对象级配置表 sf_data_obj_config
 * 优化：纯净的配置实体，无多余字段
 */
@Data
@TableName("sf_data_obj_config")
public class SfDataObjConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String objectName;

    private String sourceKeyField;
    private String targetKeyField;

    private String excludedFields;
    private String fieldMappingJson;

    private String syncFilterLogic;
    private Date lastCheckTime;

    private String isActive;

    // 配置表通常只需要记录创建时间即可，不需要完整的审计
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    public String mappingConfig;

    /** 租户归属ID (SaaS公司隔离) */
    private String tenantId;
}
