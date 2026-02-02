package com.ruoyi.salesforce.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.ruoyi.salesforce.service.ISfDeploymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Salesforce部署包 Controller
 */
@RestController
@RequestMapping("/salesforce/deployment")
public class SfDeploymentController extends BaseController {

    @Autowired
    private ISfDeploymentService sfDeploymentService;

    /**
     * 查询部署包列表
     */
    @GetMapping("/list")
    public TableDataInfo list(SfDeployment sfDeployment) {
        startPage();
        List<SfDeployment> list = sfDeploymentService.selectSfDeploymentList(sfDeployment);
        return getDataTable(list);
    }

    /**
     * 获取部署包详细信息
     */
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return AjaxResult.success(sfDeploymentService.selectSfDeploymentById(id));
    }

    /**
     * 新增部署包
     */
    @Log(title = "部署包", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SfDeployment sfDeployment) {
        sfDeployment.setCreateBy(getUsername());

        // 执行插入，MyBatis 会自动将生成的 ID 回填到 sfDeployment 对象中
        int rows = sfDeploymentService.insertSfDeployment(sfDeployment);

        // 如果插入成功，返回 ID 给前端用于跳转
        if(rows > 0) {
            // AjaxResult.success(Object data) 会将数据放入 'data' 字段
            return AjaxResult.success(sfDeployment.getId());
        }
        return AjaxResult.error();
    }

    /**
     * 修改部署包
     */
    @Log(title = "部署包", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SfDeployment sfDeployment) {
        sfDeployment.setUpdateBy(getUsername());
        return toAjax(sfDeploymentService.updateSfDeployment(sfDeployment));
    }

    /**
     * 删除部署包
     */
    @Log(title = "部署包", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(sfDeploymentService.deleteSfDeploymentByIds(ids));
    }

    // ================== 明细项管理接口 (解决 404 问题) ==================

    /**
     * 查询包内的明细列表
     */
    @GetMapping("/item/list/{deploymentId}")
    public AjaxResult listItems(@PathVariable Long deploymentId) {
        List<SfDeploymentItem> list = sfDeploymentService.selectItems(deploymentId);
        return AjaxResult.success(list);
    }

    /**
     * 添加元数据到包中
     */
    @Log(title = "部署包明细", businessType = BusinessType.INSERT)
    @PostMapping("/item/add/{deploymentId}")
    public AjaxResult addItems(@PathVariable Long deploymentId, @RequestBody List<SfDeploymentItem> items) {
        sfDeploymentService.addItems(deploymentId, items);
        return AjaxResult.success();
    }

    /**
     * 移除明细 (解决取消勾选 404 的关键)
     */
    @Log(title = "部署包明细", businessType = BusinessType.DELETE)
    @DeleteMapping("/item/{ids}")
    public AjaxResult removeItems(@PathVariable List<Long> ids) {
        sfDeploymentService.removeItems(ids);
        return AjaxResult.success();
    }

    // ================== 部署操作接口 (解决 deploy 方法缺失问题) ==================

    /**
     * 执行部署/验证
     * 前端调用：/salesforce/deployment/deploy/{id}/{checkOnly}
     * 说明：此方法调用异步 Service，会立即返回成功，前端需通过轮询查看进度
     */
    @Log(title = "执行部署", businessType = BusinessType.OTHER)
    @PostMapping("/deploy/{id}/{checkOnly}")
    public AjaxResult deployPackage(@PathVariable("id") Long id, @PathVariable("checkOnly") Boolean checkOnly) {
        try {
            // 调用之前写好的异步 Service 方法
            sfDeploymentService.deployPackage(id, checkOnly);
            return AjaxResult.success("请求已提交，正在后台处理");
        } catch(Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 检查 Salesforce 部署状态 (轮询用)
     * 前端调用：/salesforce/deployment/deploy/status/{targetOrgId}/{processId}
     */
    @GetMapping("/deploy/status/{targetOrgId}/{processId}")
    public AjaxResult checkDeployStatus(@PathVariable("targetOrgId") Long targetOrgId, @PathVariable("processId") String processId) {
        try {
            String statusJson = sfDeploymentService.checkDeployStatus(targetOrgId, processId);
            // 这里返回 msg 字段给前端解析，或者直接放在 data 里
            return AjaxResult.success(statusJson);
        } catch(Exception e) {
            return AjaxResult.error("查询状态失败: " + e.getMessage());
        }
    }

    /**
     * 执行快速部署 (Quick Deploy)
     */
    @Log(title = "快速部署", businessType = BusinessType.OTHER)
    @PostMapping("/quickDeploy/{id}")
    public AjaxResult quickDeploy(@PathVariable("id") Long id) {
        try {
            sfDeploymentService.quickDeploy(id);
            return AjaxResult.success("快速部署请求已提交");
        } catch(Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 触发状态计算
     */
    @PostMapping("/item/checkStatus/{deploymentId}")
    public AjaxResult checkStatus(@PathVariable Long deploymentId) {
        sfDeploymentService.checkDiffStatus(deploymentId);
        return AjaxResult.success("状态计算已在后台开始");
    }

    /**
     * 【新增】预览部署包内容
     */
    @GetMapping("/preview/{id}")
    public AjaxResult preview(@PathVariable("id") Long id) {
        try {
            Map<String, Object> result = sfDeploymentService.previewPackage(id);
            return AjaxResult.success(result);
        } catch(Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PostMapping("/download/{deploymentId}")
    public void download(@PathVariable("deploymentId") Long deploymentId, HttpServletResponse response) throws IOException {
        sfDeploymentService.downloadPackage(deploymentId, response);
    }

    /**
     * 【新增】取消正在进行的部署任务
     */
    @Log(title = "部署包管理", businessType = BusinessType.UPDATE)
    @PostMapping("/cancel/{deploymentId}")
    public AjaxResult cancel(@PathVariable Long deploymentId) {
        sfDeploymentService.cancelDeploymentTask(deploymentId);
        return AjaxResult.success("取消请求已提交");
    }

    /**
     * 复制/克隆部署包
     */
    @Log(title = "复制部署包", businessType = BusinessType.INSERT)
    @PostMapping("/clone/{id}")
    public AjaxResult clone(@PathVariable("id") Long id, @RequestBody SfDeployment newConfig) {
        Long newId = sfDeploymentService.cloneDeployment(id, newConfig);
        return AjaxResult.success(newId);
    }
}
