package com.ruoyi.salesforce.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.salesforce.domain.SfOrg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
public class SfBulkApiService {

    @Autowired
    private ISfOrgService sfOrgService;

    @Autowired private ISfAuthService sfAuthService;

    private static final String API_VERSION = "v58.0";

    /**
     * 1. 提交查询任务 (支持自动 Token 续期)
     */
    public String submitQueryJob(Long orgId, String soql) {
        try {
            // 使用 sfMetadataService 的重试机制包裹业务逻辑
            return sfAuthService.executeWithRetry(orgId, () -> {
                // 每次重试都重新获取 Org (确保拿到最新的 Token)
                SfOrg org = sfOrgService.selectSfOrgById(orgId);
                String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query";

                JSONObject body = new JSONObject();
                body.put("operation", "query");
                body.put("query", soql);
                body.put("contentType", "CSV");

                // 获取完整 Response 以检查状态码
                HttpResponse response = HttpRequest.post(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .body(body.toJSONString())
                        .execute();

                String result = response.body();

                // 【关键优化】主动检查 Token 失效，抛出特定异常触发重试
                if(response.getStatus() == 401 || result.contains("INVALID_SESSION_ID")) {
                    throw new Exception("INVALID_SESSION_ID: Session expired");
                }

                // 检查其他错误
                if(!response.isOk()) {
                    // 如果返回的是错误数组 [{"message":"..."}]，直接抛出内容
                    throw new Exception("Create Bulk Job Failed: " + result);
                }

                // 解析成功结果
                JSONObject json = JSONObject.parseObject(result);
                return json.getString("id");
            });
        } catch(Exception e) {
            log.error("提交 Bulk Job 异常", e);
            throw new ServiceException("提交比对任务失败: " + e.getMessage());
        }
    }

    /**
     * 2. 检查任务状态 (支持自动 Token 续期)
     */
    public String checkJobStatus(Long orgId, String jobId) {
        try {
            return sfAuthService.executeWithRetry(orgId, () -> {
                SfOrg org = sfOrgService.selectSfOrgById(orgId);
                String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

                HttpResponse response = HttpRequest.get(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .execute();

                String result = response.body();

                if(response.getStatus() == 401 || result.contains("INVALID_SESSION_ID")) {
                    throw new Exception("INVALID_SESSION_ID");
                }

                if(!response.isOk()) {
                    throw new Exception("Get Job Status Failed: " + result);
                }

                return JSONObject.parseObject(result).getString("state");
            });
        } catch(Exception e) {
            // 这里不抛出 ServiceException，允许返回 null 或 error 状态供调用方判断
            log.error("获取 Job 状态异常", e);
            return "Failed";
        }
    }

    /**
     * 3. 获取错误信息 (支持自动 Token 续期)
     */
    public String getErrorMessage(Long orgId, String jobId) {
        try {
            return sfAuthService.executeWithRetry(orgId, () -> {
                SfOrg org = sfOrgService.selectSfOrgById(orgId);
                String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

                HttpResponse response = HttpRequest.get(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .execute();

                String result = response.body();
                if(response.getStatus() == 401 || result.contains("INVALID_SESSION_ID")) {
                    throw new Exception("INVALID_SESSION_ID");
                }
                return JSONObject.parseObject(result).getString("errorMessage");
            });
        } catch(Exception e) {
            return e.getMessage();
        }
    }

    /**
     * 4. 下载结果 (支持自动 Token 续期)
     */
    public File downloadResult(Long orgId, String jobId, String filePath) {
        try {
            return sfAuthService.executeWithRetry(orgId, () -> {
                SfOrg org = sfOrgService.selectSfOrgById(orgId);
                String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId + "/results";

                // 使用 executeAsync 获取流，但也需要检查状态
                HttpResponse response = HttpRequest.get(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .header("Accept", "text/csv")
                        .timeout(60000) // 1分钟超时
                        .executeAsync();

                if(response.getStatus() == 401) {
                    throw new Exception("INVALID_SESSION_ID");
                }

                if(!response.isOk()) {
                    // 读取错误体
                    String err = response.body(); // 注意：流被读取后可能无法再次读取，但这里是错误情况无所谓
                    if(err.contains("INVALID_SESSION_ID")) {
                        throw new Exception("INVALID_SESSION_ID");
                    }
                    throw new Exception("下载失败 Code: " + response.getStatus());
                }

                File file = new File(filePath);
                if(!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }

                // 流式写入文件
                long size = response.writeBody(file, null);
                log.info("Bulk 结果下载成功: {}, 大小: {} bytes", file.getName(), size);
                return file;
            });
        } catch(Exception e) {
            log.error("下载结果文件异常", e);
            throw new ServiceException("下载结果失败: " + e.getMessage());
        }
    }

    /**
     * 5. 获取任务处理的记录数 (用于计算进度)
     */
    public int getJobRecordCount(Long orgId, String jobId) {
        try {
            return sfAuthService.executeWithRetry(orgId, () -> {
                SfOrg org = sfOrgService.selectSfOrgById(orgId);
                String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

                HttpResponse response = HttpRequest.get(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .execute();

                if(response.isOk()) {
                    JSONObject json = JSONObject.parseObject(response.body());
                    // Bulk API V2 返回 numberRecordsProcessed
                    return json.getIntValue("numberRecordsProcessed");
                }
                return 0;
            });
        } catch(Exception e) {
            log.warn("获取Job行数失败，进度条可能不准确: {}", e.getMessage());
            return 0;
        }
    }
}