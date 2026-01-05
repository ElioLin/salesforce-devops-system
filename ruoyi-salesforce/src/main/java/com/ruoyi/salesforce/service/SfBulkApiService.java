package com.ruoyi.salesforce.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.service.ISfOrgService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Salesforce Bulk API V2 服务
 * 特性：内置 Token 过期自动重试机制
 */
@Slf4j
@Service
public class SfBulkApiService {

    @Autowired
    private ISfOrgService sfOrgService;

    @Autowired
    private ISfMetadataService sfMetadataService; // 复用它来进行 Token 刷新

    private static final String API_VERSION = "v58.0";

    /**
     * 1. 提交查询任务
     */
    public String submitQueryJob(Long orgId, String soql) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query";

        JSONObject body = new JSONObject();
        body.put("operation", "query");
        body.put("query", soql);
        body.put("contentType", "CSV");
        body.put("lineEnding", "LF"); // 强制 Linux 换行符，避免跨系统比对问题

        String response = sendRequestWithRetry(orgId, url, "POST", body.toJSONString());
        return JSON.parseObject(response).getString("id");
    }

    /**
     * 2. 检查任务状态
     */
    public String checkJobStatus(Long orgId, String jobId) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

        String response = sendRequestWithRetry(orgId, url, "GET", null);
        return JSON.parseObject(response).getString("state");
    }

    /**
     * 【新增】获取任务错误信息 (修复爆红问题)
     */
    public String getErrorMessage(Long orgId, String jobId) {
        try {
            SfOrg org = sfOrgService.selectSfOrgById(orgId);
            String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

            String response = sendRequestWithRetry(orgId, url, "GET", null);
            JSONObject json = JSON.parseObject(response);
            return json.getString("errorMessage");
        } catch (Exception e) {
            log.error("获取Bulk Job错误信息失败", e);
            return "无法获取详细错误: " + e.getMessage();
        }
    }

    /**
     * 3. 流式下载结果文件 (直接写入磁盘，不占内存)
     */
    public File downloadResult(Long orgId, String jobId, String savePath) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId + "/results";

        log.info("开始下载 Bulk 结果, JobId: {}, 路径: {}", jobId, savePath);
        File destFile = new File(savePath);
        FileUtil.touch(destFile);

        // 这里我们手动实现一次 Retry 逻辑
        for (int i = 0; i < 2; i++) {
            try {
                HttpRequest.get(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .header("Accept", "text/csv")
                        .timeout(300000) // 5分钟超时
                        .execute()
                        .writeBody(destFile);
                return destFile;
            } catch (Exception e) {
                // 如果是第一次失败，尝试刷新 Token
                if (i == 0) {
                    log.warn("下载失败，尝试刷新 Token 后重试: {}", e.getMessage());
                    try {
                        // 借用此方法触发 Token 刷新逻辑 (因为它内部有重试和刷新机制)
                        sfMetadataService.refreshMetadataCache(orgId, "ApexClass");
                    } catch (Exception ex) {
                        log.warn("Token 刷新尝试过程中出现异常 (可忽略): {}", ex.getMessage());
                    }

                    // 重新获取 Org 对象以获得最新的 AccessToken
                    org = sfOrgService.selectSfOrgById(orgId);
                } else {
                    throw new ServiceException("下载结果失败: " + e.getMessage());
                }
            }
        }
        return destFile;
    }

    /**
     * 通用请求发送 (带 401 重试)
     */
    private String sendRequestWithRetry(Long orgId, String url, String method, String body) {
        // 获取最新 Org 信息
        SfOrg org = sfOrgService.selectSfOrgById(orgId);

        for (int i = 0; i < 2; i++) {
            try {
                HttpRequest request = "POST".equals(method) ? HttpRequest.post(url) : HttpRequest.get(url);
                request.header("Authorization", "Bearer " + org.getAccessToken())
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .timeout(60000);

                if (body != null) request.body(body);

                HttpResponse response = request.execute();
                String resBody = response.body();

                // 判定 Token 失效
                if (response.getStatus() == 401 || (resBody != null && resBody.contains("INVALID_SESSION_ID"))) {
                    log.warn("Token 失效 (OrgId={})，正在刷新...", orgId);

                    // 【修复】执行刷新逻辑
                    try {
                        // 使用 refreshMetadataCache 作为一个轻量级的副作用调用来触发内部的 executeWithRetry -> refreshAccessToken
                        sfMetadataService.refreshMetadataCache(orgId, "ApexClass");
                    } catch (Exception ex) {
                        // 忽略异常，只要 Token 刷新了就行
                    }

                    // 重新获取 Org 对象以拿到新 Token
                    org = sfOrgService.selectSfOrgById(orgId);

                    continue; // 刷新后进入下一次循环重试
                }

                if (!response.isOk()) {
                    throw new ServiceException("API请求失败 [" + response.getStatus() + "]: " + resBody);
                }
                return resBody;

            } catch (ServiceException se) {
                throw se;
            } catch (Exception e) {
                throw new ServiceException("网络请求异常: " + e.getMessage());
            }
        }
        throw new ServiceException("请求失败，已达最大重试次数");
    }
}