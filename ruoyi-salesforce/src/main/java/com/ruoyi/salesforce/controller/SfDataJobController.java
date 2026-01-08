package com.ruoyi.salesforce.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.salesforce.domain.SfDataJob;
import com.ruoyi.salesforce.service.ISfDataJobService;
import com.ruoyi.salesforce.service.ISfDataReconcileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/salesforce/dataJob")
public class SfDataJobController extends BaseController {
    @Autowired
    private ISfDataJobService jobService;

    /**
     * 查询比对任务列表
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:list')")
    @GetMapping("/list")
    public TableDataInfo list(SfDataJob job) {
        startPage();
        List<SfDataJob> list = jobService.selectJobList(job);
        return getDataTable(list);
    }

    /**
     * 获取任务详细信息
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return AjaxResult.success(jobService.selectJobById(id));
    }

    /**
     * 新增任务
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:add')")
    @Log(title = "数据比对任务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SfDataJob job) {
        job.setCreateBy(getUsername());
        jobService.insertJob(job);
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
        return toAjax(jobService.updateJob(job));
    }

    /**
     * 删除任务
     */
    @PreAuthorize("@ss.hasPermi('salesforce:reconcile:remove')")
    @Log(title = "数据比对任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(jobService.deleteJobByIds(ids));
    }
}
