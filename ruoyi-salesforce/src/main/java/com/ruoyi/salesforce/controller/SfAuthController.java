package com.ruoyi.salesforce.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Salesforce 授权认证 Controller
 */
@RestController
@RequestMapping("/system/sf")
public class SfAuthController {

    @Autowired
    private ISfOrgService sfOrgService;

    /**
     * 1. 获取授权URL
     * 前端点击“去授权”按钮时调用此接口，返回 Salesforce 的 OAuth 登录地址
     * http://localhost:8080/system/sf/authUrl?Id=1
     * @param id 数据库中 sf_org 表的主键ID
     * @return 包含跳转 URL 的 JSON
     */
    @GetMapping("/authUrl")
    public AjaxResult getAuthUrl(@RequestParam("Id") Long id) {
        // 1. 查询数据库中的 Org 配置信息
        SfOrg org = sfOrgService.selectSfOrgById(id);
        if (org == null) {
            return AjaxResult.error("未找到指定的Org配置，ID: " + id);
        }

        // 2. 判断环境类型，决定使用 login.salesforce.com (生产) 还是 test.salesforce.com (沙盒)
        // 假设数据库存的是 "Production" 或 "Sandbox"
        String instance = "Production".equalsIgnoreCase(org.getOrgType()) ?
                "https://login.salesforce.com" : "https://test.salesforce.com";

        // 3. 拼接 OAuth 2.0 授权地址
        // 这里的 redirect_uri 必须和 Salesforce Connected App 里配置的完全一致
        String redirectUri = "http://localhost:8080/system/sf/callback";

        String authUrl = instance + "/services/oauth2/authorize" +
                "?response_type=code" +
                "&client_id=" + org.getClientId() +
                "&redirect_uri=" + redirectUri +
                "&state=" + id; // 将 orgId 作为 state 透传，以便回调时知道是更新哪条记录

        return AjaxResult.success("操作成功", authUrl);
    }

    /**
     * 2. 回调接口
     * Salesforce 登录成功后，会自动跳转回这个地址，并携带 code 和 state
     *
     * @param code Salesforce 返回的授权码
     * @param state 我们之前透传过去的 orgId
     * @param response 用于页面重定向
     */
    @GetMapping("/callback")
    public void callback(String code, String state, HttpServletResponse response) throws IOException {
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

        // --- 步骤 1: 换取 Token ---
        String instance = "Production".equalsIgnoreCase(org.getOrgType()) ?
                "https://login.salesforce.com" : "https://test.salesforce.com";
        String tokenUrl = instance + "/services/oauth2/token";
        String redirectUri = "http://localhost:8080/system/sf/callback";

        String result = HttpRequest.post(tokenUrl)
                .form("grant_type", "authorization_code")
                .form("client_id", org.getClientId())
                .form("client_secret", org.getClientSecret())
                .form("redirect_uri", redirectUri)
                .form("code", code)
                .execute()
                .body();

        JSONObject json = JSONUtil.parseObj(result);

        // --- 步骤 2: 获取用户信息 (新增逻辑) ---
        if (json.getStr("access_token") != null) {
            String accessToken = json.getStr("access_token");
            String idUrl = json.getStr("id"); // 类似于: https://login.salesforce.com/id/00D.../005...

            // 【关键】调用 Identity API 获取 Username 和 OrgId
            // 拿着 Access Token 去访问这个 idUrl
            String identityResponse = HttpRequest.get(idUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .execute()
                    .body();

            JSONObject identityJson = JSONUtil.parseObj(identityResponse);

            // 提取关键信息
            String username = identityJson.getStr("username");       // 用户名
            String orgId = identityJson.getStr("organization_id");   // Org ID (00D开头)
            // String userId = identityJson.getStr("user_id");       // User ID (可选)

            // --- 步骤 3: 更新数据库 ---
            org.setAccessToken(accessToken);
            org.setRefreshToken(json.getStr("refresh_token"));
            org.setInstanceUrl(json.getStr("instance_url"));

            // 填入刚才获取的身份信息
            org.setUsername(username);
            org.setOrgId(orgId);

            sfOrgService.updateSfOrg(org);

            // 成功跳转
            response.sendRedirect("http://localhost:80/salesforce/org?msg=success");
        } else {
            // 失败处理
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write("<h1>Auth Failed</h1><p>" + result + "</p>");
        }
    }
}
