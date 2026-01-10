package com.ruoyi.salesforce.service.impl;

import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.utils.PackageXmlBuilder;
import com.sforce.soap.metadata.Package;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class SfBackupService {

    @Autowired
    private ISfMetadataService sfMetadataService;

    /**
     * 备份结果内部类
     */
    @Data
    @AllArgsConstructor
    public static class BackupResult {
        private String backupFilePath;          // 备份文件落盘路径
        private Map<String, String> actionMap;  // Key: Type|Name, Value: UPDATE/CREATE
    }

    /**
     * 执行备份核心逻辑
     * @param targetOrgId 目标环境
     * @param items 本次要部署的条目
     * @return 备份结果
     */
    public BackupResult performBackup(Long targetOrgId, List<SfDeploymentItem> items) throws Exception {
        log.info("开始执行备份，TargetOrg: {}", targetOrgId);

        // 1. 构建 manifest，用于从目标环境拉取现有的代码
        Package manifest = PackageXmlBuilder.build(items);

        // 2. 调用 Retrieve 接口 (注意：这里直接复用已有的 retrieveZipByManifest)
        byte[] targetZipBytes = sfMetadataService.retrieveZipByManifest(targetOrgId, manifest);

        if (targetZipBytes == null || targetZipBytes.length == 0) {
            // 目标环境拉不到任何东西，说明所有东西都是新增的
            return new BackupResult(null, generateAllCreateAction(items));
        }

        // 3. 分析 ZIP 包，确定哪些文件是存在的 (UPDATE)，哪些是不存在的 (CREATE)
        Map<String, String> actionMap = analyzeZipContent(targetZipBytes, items);

        // 4. 将备份 ZIP 落盘存储 (保存到 profile/backup 目录下)
        String backupPath = saveBackupFile(targetZipBytes, targetOrgId);

        return new BackupResult(backupPath, actionMap);
    }

    /**
     * 分析 ZIP 内容，判断 Action
     */
    private Map<String, String> analyzeZipContent(byte[] zipBytes, List<SfDeploymentItem> items) throws IOException {
        Map<String, String> actionMap = new HashMap<>();
        // 默认先全部标记为 CREATE (假设目标环境没有)
        for (SfDeploymentItem item : items) {
            actionMap.put(getKey(item), "CREATE");
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().endsWith("package.xml")) continue;

                // 如果 ZIP 里有这个文件，说明目标环境存在，改为 UPDATE
                String fileName = entry.getName();

                // 这里做一个简单的匹配逻辑，实际可能需要根据 fileName 反推 metadataType
                // 为简化，我们只要发现 ZIP 里有文件，就尝试匹配 items
                for (SfDeploymentItem item : items) {
                    // 简单包含匹配，严谨的话需要处理路径映射
                    if (fileName.contains(item.getMemberName())) {
                        actionMap.put(getKey(item), "UPDATE");
                    }
                }
            }
        }
        return actionMap;
    }

    private Map<String, String> generateAllCreateAction(List<SfDeploymentItem> items) {
        Map<String, String> map = new HashMap<>();
        for (SfDeploymentItem item : items) {
            map.put(getKey(item), "CREATE");
        }
        return map;
    }

    private String getKey(SfDeploymentItem item) {
        return item.getMetadataType() + "|" + item.getMemberName();
    }

    private String saveBackupFile(byte[] data, Long orgId) throws IOException {
        // 模拟保存到本地磁盘，实际生产建议存 OSS
        String fileName = "backup_" + orgId + "_" + DateUtils.dateTimeNow() + ".zip";
        // 假设有个工具类可以获取基础路径，这里简化处理
        String baseDir = "/data/sf-devops/backup";
        File dir = new File(baseDir);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
        return file.getAbsolutePath();
    }
}