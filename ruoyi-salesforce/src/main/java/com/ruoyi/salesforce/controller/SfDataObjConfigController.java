package com.ruoyi.salesforce.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.salesforce.domain.SfDataObjConfig;
import com.ruoyi.salesforce.service.ISfDataObjConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/salesforce/objConfig")
public class SfDataObjConfigController extends BaseController {
    @Autowired
    private ISfDataObjConfigService sfDataObjConfigService;

    /**
     * 查询某任务下的所有对象配置
     */
    @GetMapping("/list/{jobId}")
    public AjaxResult listConfigs(@PathVariable Long jobId) {
        List<SfDataObjConfig> list = sfDataObjConfigService.selectConfigList(jobId);
        return AjaxResult.success(list);
    }

    /**
     * 批量保存/更新对象配置
     * (对应前端向导的 Step 2 & 3)
     */
    @Log(title = "比对规则配置", businessType = BusinessType.UPDATE)
    @PostMapping("/batchSave/{jobId}")
    public AjaxResult batchSaveConfigs(@PathVariable Long jobId, @RequestBody List<SfDataObjConfig> configs) {
        sfDataObjConfigService.batchSaveConfigs(jobId, configs);
        return AjaxResult.success();
    }
}
