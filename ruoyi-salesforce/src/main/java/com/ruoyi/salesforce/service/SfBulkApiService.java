package com.ruoyi.salesforce.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.salesforce.domain.SfOrg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;

@Slf4j
@Service
public class SfBulkApiService {

    @Autowired
    private ISfOrgService sfOrgService;

    @Autowired
    private ISfMetadataService sfMetadataService;

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
        // 先不指定 lineEnding，看看 Salesforce 默认给什么，或者你可以尝试解开下面的注释强制指定
        // body.put("lineEnding", "CRLF");

        log.info(">>> [Step 1] 准备提交 Bulk Job, 请求参数: {}", body.toJSONString());

        String result = HttpRequest.post(url)
                .header("Authorization", "Bearer " + org.getAccessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(body.toJSONString())
                .execute()
                .body();

        // 【关键调试点】打印 Salesforce 返回的 Job 详情，请检查日志中的 columnDelimiter 和 lineEnding
        log.info(">>> [Step 2] Bulk Job 创建响应结果: {}", result);

        JSONObject json = JSONObject.parseObject(result);
        if (json.containsKey("id")) {
            return json.getString("id");
        } else {
            throw new ServiceException("创建 Bulk Job 失败: " + result);
        }
    }

    public String checkJobStatus(Long orgId, String jobId) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

        String res = HttpRequest.get(url)
                .header("Authorization", "Bearer " + org.getAccessToken())
                .execute().body();

        // 【关键调试点】轮询时再次确认 Job 的最终配置
        log.info(">>> [Step 3] Job [{}] 状态详情: {}", jobId, res);

        return JSONObject.parseObject(res).getString("state");
    }

    public String getErrorMessage(Long orgId, String jobId) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId;

        String res = HttpRequest.get(url)
                .header("Authorization", "Bearer " + org.getAccessToken())
                .execute().body();

        return JSONObject.parseObject(res).getString("errorMessage");
    }

    /**
     * 4. 下载结果
     */
    public File downloadResult(Long orgId, String jobId, String filePath) {
        SfOrg org = sfOrgService.selectSfOrgById(orgId);
        String url = org.getInstanceUrl() + "/services/data/" + API_VERSION + "/jobs/query/" + jobId + "/results";

        for (int i = 0; i < 3; i++) {
            try {
                HttpRequest request = HttpRequest.get(url)
                        .header("Authorization", "Bearer " + org.getAccessToken())
                        .header("Accept", "text/csv")
                        .timeout(60000);

                // 使用异步方法获取 Response 对象
                HttpResponse response = request.executeAsync();

                if (response.getStatus() == 401) {
                    try { sfMetadataService.refreshMetadataCache(orgId, "ApexClass"); } catch (Exception e) {}
                    org = sfOrgService.selectSfOrgById(orgId);
                    continue;
                }

                if (!response.isOk()) {
                    throw new ServiceException("下载失败 [" + response.getStatus() + "]");
                }

                File file = new File(filePath);
                // 确保父目录存在
                if (!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }

                // 【核心修复】直接将网络流 pipe 到文件，不经过 byte[] 转换
                // 这样能保证 Salesforce 给什么，文件里就是什么（包括换行符）
                long size = response.writeBody(file, null);

                log.info("文件下载成功: {}, 大小: {} bytes", file.getName(), size);
                return file;

            } catch (Exception e) {
                log.error("下载尝试 {} 失败: {}", (i + 1), e.getMessage());
                if (i == 2) throw new ServiceException("下载重试失败: " + e.getMessage());
            }
        }
        return null;
    }
}