package com.ruoyi.salesforce.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.salesforce.domain.SfDataRunLog;
import com.ruoyi.salesforce.service.ISfDataRunLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;

@RestController
@RequestMapping("/salesforce/runLog")
public class SfDataRunLogController extends BaseController {

    @Autowired
    private ISfDataRunLogService sfDataRunLogService;
    /**
     * 查询执行日志列表
     */
    @GetMapping("/list")
    public TableDataInfo listLogs(@RequestParam("jobId") Long jobId) {
        startPage(); // 支持分页
        List<SfDataRunLog> list = sfDataRunLogService.selectLogList(jobId);
        return getDataTable(list);
    }

    /**
     * 下载差异结果 CSV
     * (Checklist #7: 结果文件下载)
     */
    @GetMapping("/download/{logId}")
    public void downloadResult(@PathVariable Long logId, HttpServletResponse response) {
        try {
            String filePath = sfDataRunLogService.getResultFilePath(logId);
            // 调用若依通用的下载工具，或者简单的文件流输出
            // 这里为了简单演示，使用通用下载逻辑
            response.setContentType("application/vnd.ms-excel");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-Disposition", "attachment;filename=diff_result_" + logId + ".csv");
            com.ruoyi.common.utils.file.FileUtils.writeBytes(filePath, response.getOutputStream());
        } catch (Exception e) {
            logger.error("下载文件失败", e);
        }
    }
}
