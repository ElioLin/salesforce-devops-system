package com.ruoyi.salesforce.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.StringUtils;

import com.ruoyi.salesforce.domain.vo.SfDiffVo;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.sforce.soap.metadata.FileProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/sf/meta")
public class SfMetadataController {

    @Autowired
    private ISfMetadataService sfMetadataService;

    // 测试接口：http://localhost:8080/system/sf/meta/listApex?orgId=1
    @GetMapping("/listApex")
    public AjaxResult listApex(@RequestParam("orgId") Long orgId) {
        try {
            List<String> list = sfMetadataService.testConnection(orgId);
            return AjaxResult.success("连接成功！查询到的Apex类：", list);
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("连接失败：" + e.getMessage());
        }
    }

    /**
     * 获取元数据列表 (支持分页)
     * URL: /system/sf/meta/list?orgId=1&type=ApexClass&pageNum=1&pageSize=10
     */
    /**
     * 【优化】获取元数据列表
     * 这里的 listMetadata 现在会优先读取缓存，速度极快
     */
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam("orgId") Long orgId,
                              @RequestParam("type") String type,
                              @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
                              @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                              @RequestParam(value = "keyword", required = false) String keyword) {
        try {
            // 调用 Service (优先读缓存)
            List<FileProperties> allList = sfMetadataService.listMetadata(orgId, type);

            // 内存搜索
            if (StringUtils.isNotEmpty(keyword)) {
                allList = allList.stream()
                        .filter(item -> item.getFullName().toLowerCase().contains(keyword.toLowerCase()))
                        .collect(Collectors.toList());
            }

            // 内存分页
            int total = allList.size();
            int fromIndex = (pageNum - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, total);
            List<FileProperties> pageList = (fromIndex > total) ? new ArrayList<>() : allList.subList(fromIndex, toIndex);

            TableDataInfo rspData = new TableDataInfo();
            rspData.setCode(0);
            rspData.setRows(pageList);
            rspData.setTotal(total);
            return rspData;
        } catch (Exception e) {
            TableDataInfo rsp = new TableDataInfo();
            rsp.setCode(500);
            rsp.setMsg(e.getMessage());
            return rsp;
        }
    }

    /**
     * 获取代码内容
     * URL: /system/sf/meta/retrieve?orgId=1&type=ApexClass&name=MyClass
     */
    @GetMapping("/retrieve")
    public AjaxResult retrieve(@RequestParam("orgId") Long orgId,
                               @RequestParam("type") String type,
                               @RequestParam("name") String name) {
        try {
            String content = sfMetadataService.retrieveMetadata(orgId, type, name);
            return AjaxResult.success("获取成功", content);
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("获取失败: " + e.getMessage());
        }
    }

    /**
     * 代码差异比对
     * URL: /system/sf/meta/compare?sourceOrgId=1&targetOrgId=2&type=ApexClass&name=MyClass
     */
    @GetMapping("/compare")
    public AjaxResult compare(@RequestParam("sourceOrgId") Long sourceOrgId,
                              @RequestParam("targetOrgId") Long targetOrgId,
                              @RequestParam("type") String type,
                              @RequestParam("name") String name) {
        try {
            SfDiffVo diff = sfMetadataService.compareMetadata(sourceOrgId, targetOrgId, type, name);
            return AjaxResult.success(diff);
        } catch (Exception e) {
            return AjaxResult.error("比对失败: " + e.getMessage());
        }
    }

    /**
     * 【新增】获取所有支持的元数据类型
     */
    @GetMapping("/types")
    public AjaxResult getTypes(@RequestParam("orgId") Long orgId) {
        try {
            List<String> types = sfMetadataService.getAllMetadataTypes(orgId);
            return AjaxResult.success(types);
        } catch (Exception e) {
            return AjaxResult.error("获取类型失败: " + e.getMessage());
        }
    }

    /**
     * 【新增】强制从 Salesforce 同步 (刷新缓存)
     */
    @GetMapping("/sync")
    public AjaxResult sync(@RequestParam("orgId") Long orgId, @RequestParam("type") String type) {
        try {
            // 强制刷新并返回新列表的条数
            List<FileProperties> list = sfMetadataService.refreshMetadataCache(orgId, type);
            return AjaxResult.success("同步成功，共获取 " + list.size() + " 条数据");
        } catch (Exception e) {
            return AjaxResult.error("同步失败: " + e.getMessage());
        }
    }

    /**
     * 【新增】同步元数据类型到系统字典
     * 字典类型 Key: sys_salesforce_metadata_type
     */
    @GetMapping("/syncDict")
    public AjaxResult syncDict(@RequestParam("orgId") Long orgId) {
        try {
            sfMetadataService.syncMetadataToDict(orgId);
            // 清除字典缓存，确保前端能立即拉取到最新数据
            // DictUtils.clearDictCache(); // 如果你的项目封装了 DictUtils 可以调用这个
            return AjaxResult.success("同步成功！请前往[系统管理-字典管理]查看 sys_salesforce_metadata_type");
        } catch (Exception e) {
            return AjaxResult.error("同步失败: " + e.getMessage());
        }
    }
}
