package com.ruoyi.salesforce.service.impl;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.github.pagehelper.util.StringUtil;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfAuthService;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SfAuthServiceImpl implements ISfAuthService {
    private static final Logger log = LoggerFactory.getLogger(SfAuthServiceImpl.class);
    @Autowired
    private ISfOrgService sfOrgService;

    // 定义函数式接口 (原 SfMetadataServiceImpl 中的)
    @FunctionalInterface
    public interface SfOperation<T> {
        T execute() throws Exception;
    }

    /**
     * 【核心修复】通用重试包装器
     * 自动捕获 INVALID_SESSION_ID，刷新 Token 后重试
     */
    @Override
    public <T> T executeWithRetry(Long orgId, SfMetadataServiceImpl.SfOperation<T> operation) throws Exception {
        try {
            return operation.execute();
        } catch(Exception e) {
            // 判断是否为 Session 过期
            if(isSessionExpired(e) || (e.getCause() instanceof Exception && isSessionExpired((Exception) e.getCause()))) {
                log.warn("Org [{}] Session 已过期，触发自动续期并重试...", orgId);

                // 【修复】加锁防止并发刷新导致 Token 互相覆盖
                synchronized(this) {
                    SfOrg org = sfOrgService.selectSfOrgById(orgId);
                    refreshAccessToken(org);
                }

                // 刷新后重试一次
                return operation.execute();
            }
            throw e; // 其他异常直接抛出
        }
    }

    // 【修复】刷新 Token 方法加锁，或者是被调用处加锁
    @Override
    public void refreshAccessToken(SfOrg org) {
        String tokenUrl = getHost(org) + "/services/oauth2/token";

        try {
            String result = HttpRequest.post(tokenUrl)
                    .form("grant_type", "refresh_token")
                    .form("client_id", org.getClientId())
                    .form("client_secret", org.getClientSecret())
                    .form("refresh_token", org.getRefreshToken())
                    .timeout(20000) // 设置 HTTP 请求超时
                    .execute().body();

            JSONObject json = JSON.parseObject(result);
            if(json.getString("access_token") != null) {
                org.setAccessToken(json.getString("access_token"));
                if(json.getString("instance_url") != null) org.setInstanceUrl(json.getString("instance_url"));
                sfOrgService.updateSfOrg(org);
                log.info("Org [{}] Token 刷新成功", org.getId());
            } else {
                throw new ServiceException("刷新失败: " + json.getString("error") + " - " + json.getString("error_description"));
            }
        } catch(Exception e) {
            log.error("刷新 Token 异常", e);
            throw new ServiceException("自动续期失败: " + e.getMessage());
        }
    }

    /**
     * 辅助判断 Session 是否过期
     */
    // 增加更多 Session 失效的错误特征
    @Override
    public boolean isSessionExpired(Exception e) {
        String msg = e.getMessage();
        if(msg == null) return false;
        return msg.contains("INVALID_SESSION_ID")
                || msg.contains("Session expired")
                || msg.contains("Session not found")
                || msg.contains("Full authentication is required")
                || msg.contains("missing session hash");
    }

    /**
     * 【新增辅助方法】获取与 Controller 逻辑一致的登录 Host
     */
    private String getHost(SfOrg org) {
        if(StringUtil.isNotEmpty(org.getCustomDomain())) {
            String domain = org.getCustomDomain();
            if(!domain.startsWith("http")) domain = "https://" + domain;
            if(domain.endsWith("/")) domain = domain.substring(0, domain.length() - 1);
            return domain;
        }
        if("Sandbox".equalsIgnoreCase(org.getOrgType())) {
            return "https://test.salesforce.com";
        }
        return "https://login.salesforce.com";
    }
}
