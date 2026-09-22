package com.ruoyi.salesforce.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * Salesforce环境管理对象 sf_org
 *
 * @author ruoyi
 * @date 2025-12-25
 */
public class SfOrg extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 环境别名(如: 开发环境)
     */
    @Excel(name = "环境别名(如: 开发环境)")
    private String name;

    /**
     * 环境类型(Production/Sandbox)
     */
    @Excel(name = "环境类型(Production/Sandbox)")
    private String orgType;

    /**
     * Salesforce Org ID
     */
    @Excel(name = "Salesforce Org ID")
    private String orgId;

    /**
     * 登录用户名
     */
    @Excel(name = "登录用户名")
    private String username;

    /**
     * 实例地址(https://xxx.my.salesforce.com)
     */
    @Excel(name = "实例地址(https://xxx.my.salesforce.com)")
    private String instanceUrl;

    /**
     * 短期访问令牌
     */
    @Excel(name = "短期访问令牌")
    private String accessToken;

    /**
     * 长期刷新令牌(关键)
     */
    @Excel(name = "长期刷新令牌(关键)")
    private String refreshToken;

    /**
     * App Key
     */
    @Excel(name = "App Key")
    private String clientId;

    /**
     * App Secret
     */
    @Excel(name = "App Secret")
    private String clientSecret;

    /**
     * 自定义域名
     */
    @Excel(name = "自定义域名")
    private String customDomain;

    @Excel(name = "权限用户Id")
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Excel(name = "权限部门Id")
    private Long deptId;

    /**
     * 租户归属ID (SaaS公司隔离)
     */
    private String tenantId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }

    public String getCustomDomain() {
        return customDomain;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setOrgType(String orgType) {
        this.orgType = orgType;
    }

    public String getOrgType() {
        return orgType;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public void setInstanceUrl(String instanceUrl) {
        this.instanceUrl = instanceUrl;
    }

    public String getInstanceUrl() {
        return instanceUrl;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("name", getName())
                .append("orgType", getOrgType())
                .append("orgId", getOrgId())
                .append("username", getUsername())
                .append("instanceUrl", getInstanceUrl())
                .append("accessToken", getAccessToken())
                .append("refreshToken", getRefreshToken())
                .append("clientId", getClientId())
                .append("clientSecret", getClientSecret())
                .append("createTime", getCreateTime())
                .append("updateTime", getUpdateTime())
                .toString();
    }
}
