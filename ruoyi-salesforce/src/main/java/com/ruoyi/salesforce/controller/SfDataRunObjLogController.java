package com.ruoyi.salesforce.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.salesforce.domain.SfDataRunObjLog;
import com.ruoyi.salesforce.mapper.SfDataRunLogMapper;
import com.ruoyi.salesforce.service.ISfDataReconcileService;
import com.ruoyi.salesforce.service.ISfDataRunObjLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Map;

/**
 * 对象级运行日志 Controller
 * 对应前端 api/salesforce/dataRunObjLog.js
 */
@RestController
@RequestMapping("/salesforce/dataRunObjLog")
public class SfDataRunObjLogController extends BaseController {

    @Autowired
    private ISfDataRunObjLogService objLogService; // 【修改】注入 Service

    @Autowired
    private SfDataRunLogMapper runLogMapper; // 用于查主日志ID

    @Autowired
    private ISfDataReconcileService reconcileService;

    /**
     * 获取任务监控详情
     * 逻辑已下沉至 Service
     */
    @GetMapping("/monitor/{jobId}")
    public AjaxResult getJobMonitor(@PathVariable Long jobId) {
        Map<String, Object> data = objLogService.getMonitorData(jobId);
        return AjaxResult.success(data);
    }

    /**
     * 在线预览单个对象的比对结果 (分页)
     */
    @GetMapping("/previewObj/{objLogId}")
    public AjaxResult previewObjResult(
            @PathVariable Long objLogId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String diffType,
            @RequestParam(required = false) String fieldName) {

        // 【修改】调用 Service 的预览方法
        Map<String, Object> result = objLogService.previewCsvData(objLogId, pageNum, pageSize, diffType, fieldName);
        return AjaxResult.success(result);
    }

    /**
     * 下载单个对象的比对结果文件
     */
    @GetMapping("/downloadObj/{objLogId}")
    public void downloadObjResult(@PathVariable Long objLogId, HttpServletResponse response) throws IOException {
        SfDataRunObjLog objLog = objLogService.selectById(objLogId);
        if(objLog == null || objLog.getResultFilePath() == null) {
            response.getWriter().write("Log or File not found");
            return;
        }

        File file = new File(objLog.getResultFilePath());
        if(!file.exists()) {
            response.getWriter().write("File deleted or not found on server");
            return;
        }

        // 设置响应头
        response.setContentType("application/octet-stream");
        String fileName = "reconcile_result_" + objLog.getObjectName() + ".csv";
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));

        // 流式输出
        try(FileInputStream fis = new FileInputStream(file);
            OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while((len = fis.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
    }

    /**
     * 新增：重试/重新执行单个对象
     */
    @PostMapping("/retry/{objLogId}")
    public AjaxResult retryObjLog(@PathVariable Long objLogId) {
        reconcileService.retryObject(objLogId, SecurityUtils.getLoginUser().getTenantId());
        return AjaxResult.success("重试指令已下达");
    }
}
