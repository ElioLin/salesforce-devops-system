package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ruoyi.common.core.domain.BaseEntity;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署包主对象 sf_deployment
 */

public class SfDeployment extends BaseEntity {
    private static final long serialVersionUID = 1L;
    /**
     * 【修复】重写父类 params 字段，并标记为数据库不存在
     * 解决 MyBatis-Plus 试图将 params 插入数据库导致的 TypeHandler 异常
     */
    private Long id;

    private String title;
    private Long sourceOrgId;
    private Long targetOrgId;
    private String status;       // Draft, Validating, Succeeded...
    private String testLevel;    // NoTestRun, RunLocalTests...
    private String specifiedTests;
    private String description;
    private String delFlag;

    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    private Long deptId;

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    @TableField(exist = false)
    private String remark;

    @Override
    public String getRemark() {
        return remark;
    }

    @Override
    public void setRemark(String remark) {
        this.remark = remark;
    }

    @TableField(exist = false)
    private Map<String, Object> params = new HashMap<>();

    @Override
    public Map<String, Object> getParams() {
        if(params == null) {
            params = new HashMap<>();
        }
        return params;
    }

    @Override
    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    /**
     * Salesforce异步处理ID
     */
    private String lastAsyncId;

    /**
     * 错误信息
     */
    private String errorMsg;

    public String getLastAsyncId() {
        return lastAsyncId;
    }

    public void setLastAsyncId(String lastAsyncId) {
        this.lastAsyncId = lastAsyncId;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }


    @Version
    private Long version;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    // 【关键】子表数据列表，exist=false 表示这不是数据库字段
    @TableField(exist = false)
    private List<SfDeploymentItem> itemList;

    // Getter & Setter
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getSourceOrgId() {
        return sourceOrgId;
    }

    public void setSourceOrgId(Long sourceOrgId) {
        this.sourceOrgId = sourceOrgId;
    }

    public Long getTargetOrgId() {
        return targetOrgId;
    }

    public void setTargetOrgId(Long targetOrgId) {
        this.targetOrgId = targetOrgId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTestLevel() {
        return testLevel;
    }

    public void setTestLevel(String testLevel) {
        this.testLevel = testLevel;
    }

    public String getSpecifiedTests() {
        return specifiedTests;
    }

    public void setSpecifiedTests(String specifiedTests) {
        this.specifiedTests = specifiedTests;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(String delFlag) {
        this.delFlag = delFlag;
    }

    public List<SfDeploymentItem> getItemList() {
        return itemList;
    }

    public void setItemList(List<SfDeploymentItem> itemList) {
        this.itemList = itemList;
    }
}
