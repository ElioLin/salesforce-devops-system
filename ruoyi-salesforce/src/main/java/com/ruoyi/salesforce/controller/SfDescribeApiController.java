package com.ruoyi.salesforce.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.salesforce.service.ISfDescribeApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/salesforce/describe")
public class SfDescribeApiController {

    @Autowired
    private ISfDescribeApiService sfDescribeApiService;

    /**
     * 【新增】获取 Salesforce 所有业务对象列表 (用于比对选择)
     */
    @GetMapping("/objects")
    public AjaxResult getSObjects(@RequestParam("orgId") Long orgId) {
        try {
            List<Map<String, String>> list = sfDescribeApiService.getSObjectList(orgId);
            return AjaxResult.success(list);
        } catch(Exception e) {
            return AjaxResult.error("获取对象列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/fields")
    public AjaxResult getSObjects(@RequestParam("orgId") Long orgId, @RequestParam("objectName") String objectName) {
        try {
            List<Map<String, Object>> list = sfDescribeApiService.getSObjectFields(orgId, objectName);
            return AjaxResult.success(list);
        } catch(Exception e) {
            return AjaxResult.error("获取对象字段列表失败: " + e.getMessage());
        }
    }
}
