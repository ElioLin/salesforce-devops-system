package com.ruoyi.salesforce.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ruoyi.common.core.domain.AjaxResult;
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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Salesforce 授权认证 Controller
 */
@RestController
@RequestMapping("/system/sf")
public class SfAuthController {

    @Autowired
    private ISfOrgService sfOrgService;

    // 从配置文件读取回调地址
    @Value("${ruoyi.salesforce.callbackUrl}")
    private String callbackUrl;

    /**
     * 1. 获取授权URL
     */
    @GetMapping("/authUrl")
    public AjaxResult getAuthUrl(@RequestParam("Id") Long id) {
        SfOrg org = sfOrgService.selectSfOrgById(id);
        if (org == null) {
            return AjaxResult.error("未找到指定的Org配置，ID: " + id);
        }

        String instance = "Production".equalsIgnoreCase(org.getOrgType()) ?
                "https://login.salesforce.com" : "https://test.salesforce.com";

        try {
            // 【修正】使用 "UTF-8" 字符串，兼容 Java 8
            String encodedRedirectUri = URLEncoder.encode(callbackUrl, "UTF-8");

            String authUrl = instance + "/services/oauth2/authorize" +
                    "?response_type=code" +
                    "&prompt=login" + // 强制登录，防止串号
                    "&scope=full refresh_token offline_access" + // 获取刷新令牌
                    "&client_id=" + org.getClientId() +
                    "&redirect_uri=" + encodedRedirectUri +
                    "&state=" + id;

            return AjaxResult.success("操作成功", authUrl);
        } catch (UnsupportedEncodingException e) {
            return AjaxResult.error("生成授权链接失败: 编码异常");
        }
    }

    /**
     * 2. 回调接口
     */
    @GetMapping("/callback")
    public void callback(String code, String state, HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=utf-8");

        if (code == null || state == null) {
            response.getWriter().write("Error: Missing code or state parameter.");
            return;
        }

        Long dbId = Long.parseLong(state);
        SfOrg org = sfOrgService.selectSfOrgById(dbId);

        if (org == null) {
            response.getWriter().write("Error: Org record not found for ID " + dbId);
            return;
        }

        try {
            String instance = "Production".equalsIgnoreCase(org.getOrgType()) ?
                    "https://login.salesforce.com" : "https://test.salesforce.com";
            String tokenUrl = instance + "/services/oauth2/token";

            // 1. 换取 Token
            // 注意：这里使用 Hutool 的 HttpRequest，它会自动处理参数编码，所以 callbackUrl 直接传即可
            String result = HttpRequest.post(tokenUrl)
                    .form("grant_type", "authorization_code")
                    .form("client_id", org.getClientId())
                    .form("client_secret", org.getClientSecret())
                    .form("redirect_uri", callbackUrl) // 这里直接传原始URL
                    .form("code", code)
                    .execute()
                    .body();

            JSONObject json = JSONUtil.parseObj(result);

            if (json.getStr("access_token") != null) {
                String accessToken = json.getStr("access_token");
                String idUrl = json.getStr("id");

                // 2. 获取 refresh_token (非常重要，用于自动续期)
                String refreshToken = json.getStr("refresh_token");

                // 3. 获取用户信息
                String identityResponse = HttpRequest.get(idUrl)
                        .header("Authorization", "Bearer " + accessToken)
                        .execute()
                        .body();

                JSONObject identityJson = JSONUtil.parseObj(identityResponse);
                String username = identityJson.getStr("username");
                String orgId = identityJson.getStr("organization_id");

                // 4. 更新数据库
                org.setAccessToken(accessToken);
                // 只有显式授权才会返回 refresh_token，如果返回了就更新
                if (refreshToken != null) {
                    org.setRefreshToken(refreshToken);
                }
                org.setInstanceUrl(json.getStr("instance_url"));
                org.setUsername(username);
                org.setOrgId(orgId);

                sfOrgService.updateSfOrg(org);

                // 成功页面
                response.getWriter().write("<h1 style='color:green'>授权成功！</h1><p>您可以关闭此窗口并刷新列表。</p><script>setTimeout(function(){window.close()}, 2000);</script>");
            } else {
                response.getWriter().write("<h1>Auth Failed</h1><p>" + result + "</p>");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.getWriter().write("<h1>System Error</h1><p>" + e.getMessage() + "</p>");
        }
    }
}