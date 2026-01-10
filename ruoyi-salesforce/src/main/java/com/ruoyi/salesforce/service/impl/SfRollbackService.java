package com.ruoyi.salesforce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfDeploymentHistoryDetail;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryDetailMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.utils.PackageXmlBuilder;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SfRollbackService {

    @Autowired
    private SfDeploymentHistoryMapper historyMapper;
    @Autowired
    private SfDeploymentHistoryDetailMapper detailMapper;
    // 移除 ISfMetadataService，不再这里调用部署

    /**
     * 【优化】仅构建回滚包，不执行部署
     * @return 包含 destructiveChanges.xml 的混合回滚包字节数组
     */
    public byte[] generateRollbackPackage(Long historyId) throws IOException {
        // 1. 获取历史记录
        SfDeploymentHistory history = historyMapper.selectById(historyId);
        if (history == null || history.getBackupPath() == null) {
            throw new ServiceException("无法找到该记录的备份文件，无法回滚");
        }

        File backupFile = new File(history.getBackupPath());
        if (!backupFile.exists()) {
            throw new ServiceException("备份文件已丢失: " + history.getBackupPath());
        }

        // 2. 读取备份文件内容 (旧代码)
        byte[] backupZipBytes = FileUtils.readFileToByteArray(backupFile);

        // 3. 查询明细，找出哪些是 CREATE (本次新增) 的
        List<SfDeploymentHistoryDetail> details = detailMapper.selectList(
                new LambdaQueryWrapper<SfDeploymentHistoryDetail>().eq(SfDeploymentHistoryDetail::getHistoryId, historyId)
        );

        List<Map<String, String>> destructiveItems = new ArrayList<>();
        for (SfDeploymentHistoryDetail detail : details) {
            if ("CREATE".equals(detail.getAction())) {
                Map<String, String> item = new HashMap<>();
                item.put("type", detail.getMetadataType());
                item.put("name", detail.getMemberName());
                destructiveItems.add(item);
            }
        }

        // 4. 构造混合包并返回
        return PackageXmlBuilder.addDestructiveChanges(backupZipBytes, destructiveItems);
    }
}