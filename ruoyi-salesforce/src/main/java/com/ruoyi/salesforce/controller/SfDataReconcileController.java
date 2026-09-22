package com.ruoyi.salesforce.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
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


    // ==================== 3. 核心操作 (Action) ====================

    /**
     * 启动比对任务
     */
    @Log(title = "执行数据比对", businessType = BusinessType.OTHER)
    @PostMapping("/run/{jobId}")
    public AjaxResult runJob(@PathVariable Long jobId) {
        try {
            reconcileService.runJob(jobId, SecurityUtils.getLoginUser().getTenantId());
            return AjaxResult.success("任务已启动，请在执行日志中查看进度");
        } catch(Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 【新增】启动单个全新的比对对象
     */
    @Log(title = "启动单对象比对", businessType = BusinessType.OTHER)
    @PostMapping("/runObj/{jobId}/{configId}")
    public AjaxResult runSingleNewObject(@PathVariable Long jobId, @PathVariable Long configId) {
        try {
            reconcileService.runSingleNewObject(jobId, configId, SecurityUtils.getLoginUser().getTenantId());
            return AjaxResult.success("该对象的比对任务已成功启动");
        } catch (Exception e) {
            return AjaxResult.error("启动失败: " + e.getMessage());
        }
    }

    /**
     * 停止整个比对任务
     */
    @Log(title = "停止数据比对", businessType = BusinessType.OTHER)
    @PostMapping("/stop/{jobId}")
    public AjaxResult stopJob(@PathVariable Long jobId) {
        reconcileService.stopJob(jobId);
        return AjaxResult.success("整个任务的停止指令已下达");
    }

    /**
     * 【新增】停止单个对象的比对任务
     */
    @Log(title = "停止单对象比对", businessType = BusinessType.OTHER)
    @PostMapping("/stopObj/{objLogId}")
    public AjaxResult stopObject(@PathVariable Long objLogId) {
        reconcileService.stopObject(objLogId);
        return AjaxResult.success("该对象的停止指令已下达");
    }
}
