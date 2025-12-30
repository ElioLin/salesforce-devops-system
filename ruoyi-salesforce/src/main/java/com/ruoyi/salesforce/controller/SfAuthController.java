package com.ruoyi.salesforce.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils; // 引入若依的字符串工具类
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;

/**
 * Salesforce 授权认证 Controller (支持 混合模式)
 */
@RestController
@RequestMapping("/system/sf")
public class SfAuthController {

    @Autowired
    private ISfOrgService sfOrgService;

    // --- 全局默认配置 (Global App) ---
    @Value("${ruoyi.salesforce.callbackUrl}")
    private String callbackUrl;

    @Value("${ruoyi.salesforce.frontendUrl}")
    private String frontendUrl;

    @Value("${ruoyi.salesforce.clientId}")
    private String globalClientId;

    @Value("${ruoyi.salesforce.clientSecret}")
    private String globalClientSecret;

    /**
     * 1. 获取授权URL
     */
    @GetMapping("/authUrl")
    public AjaxResult getAuthUrl(@RequestParam("Id") Long id) {
        SfOrg org = sfOrgService.selectSfOrgById(id);
        if (org == null) {
            return AjaxResult.error("未找到指定的Org配置，ID: " + id);
        }

        try {
            // 1. 确定登录主机 (优先使用自定义域名，兼容阿里云等特殊环境)
            String host = getHost(org);

            // 2. 确定使用哪个 App Key (优先使用数据库中填写的，没有则用全局默认)
            String clientId = getEffectiveClientId(org);

            // 3. 编码回调地址
            String encodedRedirectUri = URLEncoder.encode(callbackUrl, "UTF-8");

            // 4. 拼接 URL
            String authUrl = host + "/services/oauth2/authorize" +
                    "?response_type=code" +
                    "&prompt=login" +
                    "&scope=full refresh_token offline_access" +
                    "&client_id=" + clientId +
                    "&redirect_uri=" + encodedRedirectUri +
                    "&state=" + id;

            return AjaxResult.success("操作成功", authUrl);
        } catch (Exception e) {
            return AjaxResult.error("生成授权链接失败: " + e.getMessage());
        }
    }

    /**
     * 2. 回调接口
     */
    @GetMapping("/callback")
    public void callback(String code, String state, String error, String error_description, HttpServletResponse response) throws IOException {
        if (error != null) {
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write("<h1>授权失败</h1><p>" + error + ": " + error_description + "</p>");
            return;
        }

        if (code == null || state == null) {
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write("<h1>授权异常</h1><p>Missing code or state.</p>");
            return;
        }

        Long dbId = Long.parseLong(state);
        SfOrg org = sfOrgService.selectSfOrgById(dbId);

        if (org == null) {
            response.getWriter().write("Error: Org record not found");
            return;
        }

        try {
            String host = getHost(org);
            String tokenUrl = host + "/services/oauth2/token";

            // 获取当前生效的 Key 和 Secret
            String clientId = getEffectiveClientId(org);
            String clientSecret = getEffectiveClientSecret(org);

            // 换取 Token
            String result = HttpRequest.post(tokenUrl)
                    .form("grant_type", "authorization_code")
                    .form("client_id", clientId)
                    .form("client_secret", clientSecret)
                    .form("redirect_uri", callbackUrl)
                    .form("code", code)
                    .execute()
                    .body();

            JSONObject json = JSONUtil.parseObj(result);

            if (json.getStr("access_token") != null) {
                String accessToken = json.getStr("access_token");
                String refreshToken = json.getStr("refresh_token");
                String idUrl = json.getStr("id");
                String instanceUrl = json.getStr("instance_url");

                // 获取用户信息
                String identityResponse = HttpRequest.get(idUrl)
                        .header("Authorization", "Bearer " + accessToken)
                        .execute()
                        .body();

                JSONObject identityJson = JSONUtil.parseObj(identityResponse);

                // 更新数据库
                org.setAccessToken(accessToken);
                if (refreshToken != null) {
                    org.setRefreshToken(refreshToken);
                }
                org.setInstanceUrl(instanceUrl);
                org.setUsername(identityJson.getStr("username"));
                org.setOrgId(identityJson.getStr("organization_id"));

                // 注意：这里不要清空 clientId/Secret，因为如果是用户手动填的，下次刷新Token还需要用到

                sfOrgService.updateSfOrg(org);

                // 跳转回前端
                response.sendRedirect(frontendUrl + "/salesforce/org?auth=success");
            } else {
                response.setContentType("text/html;charset=utf-8");
                response.getWriter().write("<h1>Auth Failed</h1><p>" + result + "</p>");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.getWriter().write("System Error: " + e.getMessage());
        }
    }

    // ================= 辅助方法 =================

    /**
     * 获取生效的 Client ID
     * 策略：如果数据库里填了，就用数据库的；否则用配置文件的全局ID
     */
    private String getEffectiveClientId(SfOrg org) {
        if (StringUtils.isNotEmpty(org.getClientId())) {
            return org.getClientId();
        }
        return globalClientId;
    }

    /**
     * 获取生效的 Client Secret
     */
    private String getEffectiveClientSecret(SfOrg org) {
        if (StringUtils.isNotEmpty(org.getClientSecret())) {
            return org.getClientSecret();
        }
        return globalClientSecret;
    }

    /**
     * 获取登录 Host
     */
    private String getHost(SfOrg org) {
        // 1. 如果填了自定义域名 (阿里云版必须填这个)，优先使用
        if (StringUtils.isNotEmpty(org.getCustomDomain())) {
            String domain = org.getCustomDomain();
            if (!domain.startsWith("http")) {
                domain = "https://" + domain;
            }
            // 简单处理末尾斜杠
            if (domain.endsWith("/")) {
                domain = domain.substring(0, domain.length() - 1);
            }
            return domain;
        }

        // 2. 否则根据类型判断
        if ("Sandbox".equalsIgnoreCase(org.getOrgType())) {
            return "https://test.salesforce.com";
        }
        return "https://login.salesforce.com";
    }
}