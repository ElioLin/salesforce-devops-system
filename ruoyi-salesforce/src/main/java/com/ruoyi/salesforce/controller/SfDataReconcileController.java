package com.ruoyi.salesforce.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.salesforce.domain.SfDataJob;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.domain.SfDataRunLog;
import com.ruoyi.salesforce.service.ISfDataReconcileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * Salesforce 数据一致性比对 Controller
 */
@RestController
@RequestMapping("/salesforce/reconcile")
public class SfDataReconcileController extends BaseController {

    @Autowired
    private ISfDataReconcileService reconcileService;

    // ==================== 1. 任务管理 (Job) ====================

    /**
     * 查询比对任务列表
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:list')")
    @GetMapping("/list")
    public TableDataInfo list(SfDataJob job) {
        startPage();
        List<SfDataJob> list = reconcileService.selectJobList(job);
        return getDataTable(list);
    }

    /**
     * 获取任务详细信息
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return AjaxResult.success(reconcileService.selectJobById(id));
    }

    /**
     * 新增任务
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:add')")
    @Log(title = "数据比对任务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SfDataJob job) {
        job.setCreateBy(getUsername());
        reconcileService.insertJob(job);
        // 【核心修复】直接返回新生成的 ID，前端 wizard.vue 需要用这个 ID
        return AjaxResult.success(job.getId());
    }

    /**
     * 修改任务
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:edit')")
    @Log(title = "数据比对任务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SfDataJob job) {
        job.setUpdateBy(getUsername());
        return toAjax(reconcileService.updateJob(job));
    }

    /**
     * 删除任务
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:remove')")
    @Log(title = "数据比对任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(reconcileService.deleteJobByIds(ids));
    }

    // ==================== 2. 配置管理 (Config) ====================

    /**
     * 查询某任务下的所有对象配置
     */
    @GetMapping("/config/list/{jobId}")
    public AjaxResult listConfigs(@PathVariable Long jobId) {
        List<SfDataObjConfig> list = reconcileService.selectConfigList(jobId);
        return AjaxResult.success(list);
    }

    /**
     * 批量保存/更新对象配置
     * (对应前端向导的 Step 2 & 3)
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:edit')")
    @Log(title = "比对规则配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config/batchSave/{jobId}")
    public AjaxResult batchSaveConfigs(@PathVariable Long jobId, @RequestBody List<SfDataObjConfig> configs) {
        reconcileService.batchSaveConfigs(jobId, configs);
        return AjaxResult.success();
    }

    // ==================== 3. 核心操作 (Action) ====================

    /**
     * 启动比对任务
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:run')")
    @Log(title = "执行数据比对", businessType = BusinessType.OTHER)
    @PostMapping("/run/{jobId}")
    public AjaxResult runJob(@PathVariable Long jobId) {
        try {
            reconcileService.startJob(jobId);
            return AjaxResult.success("任务已启动，请在执行日志中查看进度");
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    // ==================== 4. 日志与结果 (Logs) ====================

    /**
     * 查询执行日志列表
     */
    @GetMapping("/log/list")
    public TableDataInfo listLogs(@RequestParam("jobId") Long jobId) {
        startPage(); // 支持分页
        List<SfDataRunLog> list = reconcileService.selectLogList(jobId);
        return getDataTable(list);
    }

    /**
     * 下载差异结果 CSV
     * (Checklist #7: 结果文件下载)
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:export')")
    @GetMapping("/download/{logId}")
    public void downloadResult(@PathVariable Long logId, HttpServletResponse response) {
        try {
            String filePath = reconcileService.getResultFilePath(logId);
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

    /**
     * 在线预览比对结果 (支持分页与筛选)
     */
    @GetMapping("/preview")
    public AjaxResult previewResult(
            @RequestParam("jobId") Long jobId,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
            @RequestParam(value = "diffType", required = false) String diffType, // 筛选: 差异类型
            @RequestParam(value = "fieldName", required = false) String fieldName // 筛选: 字段名
    ) {
        try {
            // 返回结构: { total: 100, rows: [...] }
            Map<String, Object> result = reconcileService.previewCsvData(jobId, pageNum, pageSize, diffType, fieldName);
            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("读取预览文件失败: " + e.getMessage());
        }
    }
}