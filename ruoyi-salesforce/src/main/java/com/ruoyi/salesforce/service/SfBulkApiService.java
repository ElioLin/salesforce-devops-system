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

    @Autowired
    private ISfAuthService sfAuthService;

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
     * 4. 下载结果 (支持自动 Token 续期，并支持大文件 Locator 自动分批下载与合并)
     */
    public File downloadResult(Long orgId, String jobId, String filePath) {
        try {
            return sfAuthService.executeWithRetry(orgId, () -> {
                SfOrg org = sfOrgService.selectSfOrgById(orgId);
                String baseUrl = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId + "/results";

                File file = new File(filePath);
                if(!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }

                // 【关键修复 1】防脏数据：由于我们即将使用追加模式，如果存在上次失败残留的文件，必须先物理删除
                if(file.exists()) {
                    file.delete();
                }

                String locator = null;
                boolean isFirstChunk = true;

                // 【关键修复 2】使用追加模式 (append = true) 打开文件流，应对多批次分片组合
                try(java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
                    java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos)) {

                    do {
                        String url = baseUrl;
                        if(locator != null && !"null".equalsIgnoreCase(locator)) {
                            url += "?locator=" + locator;
                        }

                        log.info("开始下载 Bulk 结果数据块 (Locator: {}), URL: {}", locator, url);

                        HttpResponse response = HttpRequest.get(url)
                                .header("Authorization", "Bearer " + org.getAccessToken())
                                .header("Accept", "text/csv")
                                .timeout(120000) // 放宽到 2 分钟超时，应对大文件传输
                                .executeAsync();

                        if(response.getStatus() == 401) {
                            throw new Exception("INVALID_SESSION_ID");
                        }

                        if(!response.isOk()) {
                            // 注意：调用 .body() 后流会被消耗，用来记录错误日志
                            String err = response.body();
                            if(err != null && err.contains("INVALID_SESSION_ID")) {
                                throw new Exception("INVALID_SESSION_ID");
                            }
                            throw new Exception("下载失败 Code: " + response.getStatus() + ", Msg: " + err);
                        }

                        // 提取下一页的游标 (Salesforce 在没有下一页时会返回 "null" 字符串或不返回该Header)
                        locator = response.header("Sforce-Locator");

                        // 【性能压榨优化】：使用 BufferedInputStream 包裹原生网络流，极大地加速跳过表头和数据搬运的过程
                        try (java.io.InputStream rawIs = response.bodyStream();
                             java.io.BufferedInputStream is = new java.io.BufferedInputStream(rawIs)) {

                            if (isFirstChunk) {
                                // 第一批次：毫无保留，全量写入（包含最顶部的 CSV 表头）
                                byte[] buffer = new byte[8192];//现代操作系统的磁盘块（Block Size）和内存页（Page Size）通常是 4KB 或 8KB。将缓冲区设置为 8KB，刚好能与操作系统的底层机制完美对齐，达到吞吐量与内存占用的最佳平衡。
                                int bytesRead;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    bos.write(buffer, 0, bytesRead);
                                }
                                isFirstChunk = false;
                            } else {
                                // 后续批次：字节级跳过第一行表头
                                int b;
                                boolean headerSkipped = false;
                                while ((b = is.read()) != -1) {
                                    if (b == '\n') {
                                        headerSkipped = true;
                                        break;
                                    }
                                }

                                // 精准跳过表头后，再将剩余的业务数据块全速泵入文件
                                if (headerSkipped) {
                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    while ((bytesRead = is.read(buffer)) != -1) {
                                        bos.write(buffer, 0, bytesRead);
                                    }
                                }
                            }
                            bos.flush();
                        }
                    } while(locator != null && !"null".equalsIgnoreCase(locator) && !locator.trim().isEmpty());
                }

                log.info("Bulk 结果全部分块下载并无缝合并完成: {}, 最终文件大小: {} bytes", file.getName(), file.length());
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
