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
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam("orgId") Long orgId,
                              @RequestParam("type") String type,
                              @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
                              @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
                              @RequestParam(value = "keyword", required = false) String keyword) { // 支持搜索
        try {
            // 1. 调用 Service 获取【全部】数据
            // (注意：因为SF API限制，我们必须先全拿下来，再在内存里分)
            List<FileProperties> allList = sfMetadataService.listMetadata(orgId, type);

            // 2. 内存过滤 (如果有搜索关键词)
            if (StringUtils.isNotEmpty(keyword)) {
                allList = allList.stream()
                        .filter(item -> item.getFullName().toLowerCase().contains(keyword.toLowerCase()))
                        .collect(Collectors.toList());
            }

            // 3. 内存分页计算 (核心逻辑)
            int total = allList.size();
            // 计算开始索引: (第几页 - 1) * 每页几条
            int fromIndex = (pageNum - 1) * pageSize;
            // 计算结束索引: 也就是取 min(理论结束位置, 总长度)，防止越界
            int toIndex = Math.min(fromIndex + pageSize, total);

            List<FileProperties> pageList;
            if (fromIndex > total) {
                // 如果请求的页码超出了范围，返回空
                pageList = new ArrayList<>();
            } else {
                // 截取子列表
                pageList = allList.subList(fromIndex, toIndex);
            }

            // 4. 封装成若依标准表格对象返回
            TableDataInfo rspData = new TableDataInfo();
            rspData.setCode(0);
            rspData.setMsg("查询成功");
            rspData.setRows(pageList); // 只返回这一页的数据
            rspData.setTotal(total);   // 告诉前端总共有多少条
            return rspData;

        } catch (Exception e) {
            e.printStackTrace();
            // 失败时返回空表格
            TableDataInfo rspData = new TableDataInfo();
            rspData.setCode(500);
            rspData.setMsg("查询失败：" + e.getMessage());
            return rspData;
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
}
