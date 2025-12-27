package com.ruoyi.salesforce.controller;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.ISfOrgService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;

import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * Salesforce环境管理Controller
 *
 * @author ruoyi
 * @date 2025-12-25
 */
@RestController
@RequestMapping("/salesforce/org")
public class SfOrgController extends BaseController
{
    @Autowired
    private ISfOrgService sfOrgService;

    /**
     * 查询Salesforce环境管理列表
     */
    @PreAuthorize("@ss.hasPermi('salesforce:org:list')")
    @GetMapping("/list")
    public TableDataInfo list(SfOrg sfOrg)
    {
        startPage();
        List<SfOrg> list = sfOrgService.selectSfOrgList(sfOrg);
        return getDataTable(list);
    }

    /**
     * 导出Salesforce环境管理列表
     */
    @PreAuthorize("@ss.hasPermi('salesforce:org:export')")
    @Log(title = "Salesforce环境管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SfOrg sfOrg)
    {
        List<SfOrg> list = sfOrgService.selectSfOrgList(sfOrg);
        ExcelUtil<SfOrg> util = new ExcelUtil<SfOrg>(SfOrg.class);
        util.exportExcel(response, list, "Salesforce环境管理数据");
    }

    /**
     * 获取Salesforce环境管理详细信息
     */
    @PreAuthorize("@ss.hasPermi('salesforce:org:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(sfOrgService.selectSfOrgById(id));
    }

    /**
     * 新增Salesforce环境管理
     */
    @PreAuthorize("@ss.hasPermi('salesforce:org:add')")
    @Log(title = "Salesforce环境管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SfOrg sfOrg)
    {
        return toAjax(sfOrgService.insertSfOrg(sfOrg));
    }

    /**
     * 修改Salesforce环境管理
     */
    @PreAuthorize("@ss.hasPermi('salesforce:org:edit')")
    @Log(title = "Salesforce环境管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SfOrg sfOrg)
    {
        return toAjax(sfOrgService.updateSfOrg(sfOrg));
    }

    /**
     * 删除Salesforce环境管理
     */
    @PreAuthorize("@ss.hasPermi('salesforce:org:remove')")
    @Log(title = "Salesforce环境管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(sfOrgService.deleteSfOrgByIds(ids));
    }
}
