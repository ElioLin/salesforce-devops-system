package com.ruoyi.salesforce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfDeploymentHistoryDetail;
import com.ruoyi.salesforce.domain.vo.SfAuditVo;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryDetailMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.service.ISfDeploymentService;
import com.ruoyi.salesforce.service.impl.SfRollbackService;
import com.ruoyi.salesforce.utils.PackageXmlBuilder;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Salesforce 部署历史与回滚 Controller
 */
@RestController
@RequestMapping("/salesforce/deployment")
public class SfDeploymentHistoryController extends BaseController {

    @Autowired
    private SfDeploymentHistoryMapper historyMapper;

    @Autowired
    private SfDeploymentHistoryDetailMapper detailMapper;

    @Autowired
    private SfRollbackService rollbackService;

    @Autowired
    private ISfDeploymentService sfDeploymentService;

    /**
     * 获取指定部署包的历史记录列表
     */
    @GetMapping("/history/list/{deploymentId}")
    public AjaxResult listHistory(@PathVariable Long deploymentId) {
        List<SfDeploymentHistory> list = historyMapper.selectList(
                new LambdaQueryWrapper<SfDeploymentHistory>()
                        .eq(SfDeploymentHistory::getDeploymentId, deploymentId)
                        .orderByDesc(SfDeploymentHistory::getStartTime) // 按时间倒序
        );
        return AjaxResult.success(list);
    }

    /**
     * 获取某次历史记录的变更明细
     */
    @GetMapping("/history/{historyId}/details")
    public AjaxResult getHistoryDetails(@PathVariable Long historyId) {
        List<SfDeploymentHistoryDetail> details = detailMapper.selectList(
                new LambdaQueryWrapper<SfDeploymentHistoryDetail>()
                        .eq(SfDeploymentHistoryDetail::getHistoryId, historyId)
        );
        return AjaxResult.success(details);
    }

    /**
     * 下载备份文件
     */
    @Log(title = "下载备份文件", businessType = BusinessType.EXPORT)
    @PostMapping("/history/download/{historyId}")
    public void downloadBackup(@PathVariable Long historyId, HttpServletResponse response) {
        SfDeploymentHistory history = historyMapper.selectById(historyId);
        if(history == null || history.getBackupPath() == null) {
            throw new ServiceException("备份记录不存在");
        }

        File file = new File(history.getBackupPath());
        if(!file.exists()) {
            throw new ServiceException("备份文件已丢失，路径: " + history.getBackupPath());
        }

        try {
            String fileName = "backup_" + history.getDeploymentId() + "_" + history.getId() + ".zip";
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8.toString()));
            response.setHeader("Content-Length", String.valueOf(file.length()));

            // 将文件内容写入响应流
            FileUtils.copyFile(file, response.getOutputStream());
        } catch(IOException e) {
            throw new ServiceException("文件下载失败: " + e.getMessage());
        }
    }

    /**
     * 执行回滚操作
     */
    @Log(title = "回滚部署", businessType = BusinessType.UPDATE)
    @PostMapping("/rollback/{historyId}")
    public AjaxResult rollback(@PathVariable Long historyId) {
        try {
            // 【修改】调用 DeploymentService 的 executeRollback
            // 它内部会处理打包、部署、监控、WS推送全流程
            sfDeploymentService.executeRollback(historyId);

            return AjaxResult.success("回滚请求已在后台启动，请留意部署进度条");
        } catch(Exception e) {
            e.printStackTrace();
            return AjaxResult.error("回滚启动失败: " + e.getMessage());
        }
    }

    /**
     * 【新增】元数据审计搜索
     */
    @GetMapping("/audit/list")
    public TableDataInfo listAudit(@RequestParam(required = false) String type,
                                   @RequestParam(required = false) String name) {
        startPage();
        List<SfAuditVo> list = historyMapper.selectAuditList(type, name);
        return getDataTable(list);
    }

    // 简单的解析工具方法 (也可以复用 SfDeploymentServiceImpl 里的)
    private Map<String, Object> parseZipBytes(byte[] zipBytes) throws IOException {
        List<String> fileList = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    fileList.add(entry.getName());
                    // 只读取文本文件前 10KB 用于预览
                    if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".cls") /*等*/) {
                        // ... 读取逻辑 ...
                    }
                }
            }
        }
        // ... 返回结构同 previewPackage ...
        Map<String, Object> map = new HashMap<>();
        map.put("files", fileList);
        return map;
    }

    /**
     * 【优化】预览历史备份包
     */
    @GetMapping("/history/preview/{historyId}")
    public AjaxResult previewBackup(@PathVariable Long historyId) {
        SfDeploymentHistory history = historyMapper.selectById(historyId);
        if (history == null || StringUtils.isEmpty(history.getBackupPath())) {
            return AjaxResult.error("备份文件记录不存在");
        }

        try {
            File file = new File(history.getBackupPath());
            if (!file.exists()) return AjaxResult.error("物理备份文件已丢失: " + history.getBackupPath());

            // 1. 读取文件
            byte[] zipBytes = FileUtils.readFileToByteArray(file);

            // 2. 【调用公共工具类解析】
            Map<String, Object> result = PackageXmlBuilder.parseZipForPreview(zipBytes);

            return AjaxResult.success(result);
        } catch (Exception e) {
            return AjaxResult.error("预览解析失败: " + e.getMessage());
        }
    }
}
