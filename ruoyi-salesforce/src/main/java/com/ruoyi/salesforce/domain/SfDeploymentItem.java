package com.ruoyi.salesforce.domain;

import com.alibaba.fastjson2.annotation.JSONField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ruoyi.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * 部署包明细对象 sf_deployment_item
 */
@TableName("sf_deployment_item")
public class SfDeploymentItem {
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;
    private Long deploymentId;
    private String metadataType;
    private String memberName;
    private String action; // Add or Delete

    private Date createTime;

    private String diffStatus;
    private Date lastCheckTime;

    // Getter & Setter
    public String getDiffStatus() {
        return diffStatus;
    }

    public void setDiffStatus(String diffStatus) {
        this.diffStatus = diffStatus;
    }

    public Date getLastCheckTime() {
        return lastCheckTime;
    }

    public void setLastCheckTime(Date lastCheckTime) {
        this.lastCheckTime = lastCheckTime;
    }

    // Getter & Setter
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getMetadataType() {
        return metadataType;
    }

    public void setMetadataType(String metadataType) {
        this.metadataType = metadataType;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}