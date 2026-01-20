package com.ruoyi.salesforce.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署包主对象 sf_deployment
 */
@TableName("sf_deployment")
public class SfDeployment {
    private static final long serialVersionUID = 1L;
    /**
     * 【修复】重写父类 params 字段，并标记为数据库不存在
     * 解决 MyBatis-Plus 试图将 params 插入数据库导致的 TypeHandler 异常
     */
    @TableId
    private Long id;

    private String title;
    private Long sourceOrgId;
    private Long targetOrgId;
    private String status;       // Draft, Validating, Succeeded...
    private String testLevel;    // NoTestRun, RunLocalTests...
    private String specifiedTests;
    private String description;
    private String delFlag;
    /**
     * Salesforce异步处理ID
     */
    private String lastAsyncId;

    @TableField(exist = false)
    private Map<String, Object> params = new HashMap<>();

    public Map<String, Object> getParams() {
        if(params == null) {
            params = new HashMap<>();
        }
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

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

    /**
     * 创建者
     */
    private String createBy;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /**
     * 更新者
     */
    private String updateBy;

    @Version
    private Long version;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

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

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public List<SfDeploymentItem> getItemList() {
        return itemList;
    }

    public void setItemList(List<SfDeploymentItem> itemList) {
        this.itemList = itemList;
    }
}
