package com.ruoyi.salesforce.manager;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfOrgService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Salesforce 认证管理器
 * 核心职责：提供永远有效的 AccessToken (自动处理过期刷新)
 */
@Slf4j
@Component
public class SfTokenManager {

    @Autowired
    private ISfOrgService sfOrgService;

    /**
     * 获取有效的 Access Token (带自动刷新机制)
     */
    public String getValidAccessToken(Long orgId) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        if (org == null) {
            throw new ServiceException("未找到环境配置 OrgId: " + orgId);
        }

        // 这里做一个简单的预判，虽然不能100%保证未过期，但能减少调用失败率
        // 实际生产中，更稳健的做法是捕获 401 异常后重试，但这里先做主动刷新
        // 建议：如果你有存 token_expire_time，这里可以判断时间

        // 直接返回 (后续如果 Bulk API 报 401，我们会在 Bulk Service 里调用 refresh)
        return org.getAccessToken();
    }

    /**
     * 强制刷新 Token 并更新数据库
     */
    public synchronized String refreshAccessToken(Long orgId) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        log.info("开始为 Org [{}] 执行 Token 刷新...", org.getName());

        if (StringUtils.isEmpty(org.getRefreshToken()) || StringUtils.isEmpty(org.getClientId())) {
            throw new ServiceException("无法刷新：缺少 Refresh Token 或 Client ID");
        }

        String instance = "Sandbox".equalsIgnoreCase(org.getOrgType()) ? "https://test.salesforce.com" : "https://login.salesforce.com";
        // 如果配了自定义域名，最好用自定义域名
        if (StringUtils.isNotEmpty(org.getCustomDomain())) {
            instance = org.getCustomDomain().startsWith("http") ? org.getCustomDomain() : "https://" + org.getCustomDomain();
        }

        String tokenUrl = instance + "/services/oauth2/token";

        try {
            String result = HttpRequest.post(tokenUrl)
                    .form("grant_type", "refresh_token")
                    .form("client_id", org.getClientId())
                    .form("client_secret", org.getClientSecret())
                    .form("refresh_token", org.getRefreshToken())
                    .timeout(20000)
                    .execute().body();

            JSONObject json = JSON.parseObject(result);
            String newAccessToken = json.getString("access_token");

            if (StringUtils.isNotEmpty(newAccessToken)) {
                // 更新数据库
                org.setAccessToken(newAccessToken);
                // 只有当返回了新的 instance_url 时才更新，否则保持原样
                if (StringUtils.isNotEmpty(json.getString("instance_url"))) {
                    org.setInstanceUrl(json.getString("instance_url"));
                }
                sfOrgService.updateSfOrg(org);
                log.info("Org [{}] Token 刷新成功！", org.getName());
                return newAccessToken;
            } else {
                String err = json.getString("error_description");
                log.error("刷新失败: {}", err);
                throw new ServiceException("Token刷新失败: " + err);
            }
        } catch (Exception e) {
            log.error("刷新请求异常", e);
            throw new ServiceException("Token刷新网络异常: " + e.getMessage());
        }
    }

    /**
     * 获取实例 URL (Instance URL)
     */
    public String getInstanceUrl(Long orgId) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        return org.getInstanceUrl();
    }
}