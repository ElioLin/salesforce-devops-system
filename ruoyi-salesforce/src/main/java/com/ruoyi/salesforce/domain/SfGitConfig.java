package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@TableName("sf_git_config")
public class SfGitConfig extends BaseEntity {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String name;
    private String repoUrl;
    private String authType;

    // 返回给前端时，务必注意脱敏，不要直接传明文
    private String credentials;

    private Integer isActive;

    /**
     * 备注
     */
    @TableField(exist = false)
    private String remark;

    @TableField(exist = false)
    private Map<String, Object> params = new HashMap<>();
}
