package com.ruoyi.salesforce.service.impl;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfDeploymentHistoryDetail;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryDetailMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class SfHistoryService {

    @Autowired
    private SfDeploymentHistoryMapper historyMapper;
    @Autowired
    private SfDeploymentHistoryDetailMapper detailMapper;

    /**
     * 1. 初始化一条历史记录 (Processing)
     */
    @Transactional
    public SfDeploymentHistory initHistory(Long deploymentId, Long orgId, String type) {
        SfDeploymentHistory history = new SfDeploymentHistory();
        history.setDeploymentId(deploymentId);
        history.setOrgId(orgId);
        history.setType(type);
        history.setStatus("Processing");
        history.setStartTime(new Date());

        // 【核心优化：审计字段赋值】
        // 在主线程中获取用户信息，防止后续异步操作拿不到
        try {
            String username = SecurityUtils.getUsername();
            history.setCreateBy(username);
            history.setUpdateBy(username);
        } catch(Exception e) {
            // 如果是在定时任务或无登录环境下触发，可能会报错，给个默认值
            history.setCreateBy("System");
        }
        history.setCreateTime(new Date());

        historyMapper.insert(history);
        return history;
    }

    /**
     * 2. 异步任务拿到 AsyncId 后更新
     */
    public void updateAsyncId(Long historyId, String asyncId) {
        SfDeploymentHistory update = new SfDeploymentHistory();
        update.setId(historyId);
        update.setDeployAsyncId(asyncId);
        historyMapper.updateById(update);
    }

    /**
     * 3. 部署结束，更新最终状态
     */
    public void finishHistory(Long historyId, String status, String errorMsg) {
        SfDeploymentHistory update = new SfDeploymentHistory();
        update.setId(historyId);
        update.setStatus(status);
        update.setEndTime(new Date());
        update.setUpdateTime(new Date());

        // 【核心优化：保存错误信息】
        if(errorMsg != null) {
            // 防止数据库字段长度溢出，截取一下（假设数据库是 text 类型）
            if(errorMsg.length() > 6000) {
                update.setErrorMsg(errorMsg.substring(0, 6000) + "...(日志过长截断)");
            } else {
                update.setErrorMsg(errorMsg);
            }
        } else {
            // 如果成功，可以清空之前的错误信息
            update.setErrorMsg("");
        }

        historyMapper.updateById(update);
    }

    /**
     * 4. 保存备份信息和明细 (仅在部署成功或部分成功且有备份时调用)
     */
    @Transactional
    public void saveBackupAndDetails(Long historyId, String backupPath,
                                     Map<String, String> actionMap,
                                     List<SfDeploymentItem> items) {
        // 更新备份路径
        SfDeploymentHistory history = new SfDeploymentHistory();
        history.setId(historyId);
        history.setBackupPath(backupPath);
        historyMapper.updateById(history);

        // 插入明细
        if(items != null && actionMap != null) {
            for(SfDeploymentItem item : items) {
                String key = item.getMetadataType() + "|" + item.getMemberName();
                String action = actionMap.getOrDefault(key, "UPDATE"); // 默认 Update

                SfDeploymentHistoryDetail detail = new SfDeploymentHistoryDetail();
                detail.setHistoryId(historyId);
                detail.setMetadataType(item.getMetadataType());
                detail.setMemberName(item.getMemberName());
                detail.setAction(action);
                detailMapper.insert(detail);
            }
        }
    }

    /**
     * 【新增】单纯更新状态 (用于回滚后标记原记录)
     */
    public void updateStatus(Long historyId, String newStatus) {
        SfDeploymentHistory update = new SfDeploymentHistory();
        update.setId(historyId);
        update.setStatus(newStatus);
        historyMapper.updateById(update);
    }

    // 顺便加一个 getById 方便调用
    public SfDeploymentHistory getById(Long id) {
        return historyMapper.selectById(id);
    }

    @Transactional
    public void saveBackupAndDetails(Long historyId, String backupPath,
                                     Map<String, String> actionMap,
                                     Map<String, String> diffMap, // <--- 新增参数
                                     List<SfDeploymentItem> items) {
        try {
            // 1. 更新主表备份路径
            SfDeploymentHistory history = new SfDeploymentHistory();
            history.setId(historyId);
            history.setBackupPath(backupPath);
            historyMapper.updateById(history);

            // 2. 插入明细
            if(items != null) {
                for(SfDeploymentItem item : items) {
                    String key = item.getMetadataType() + "|" + item.getMemberName();
                    String action = actionMap != null ? actionMap.getOrDefault(key, "UPDATE") : "UPDATE";

                    // 获取 Diff
                    String diff = diffMap != null ? diffMap.get(key) : null;

                    // 【核心优化 1】超长文本防御性截断
                    // 设定阈值：1000000 字符 (约 1MB - 2MB，视编码而定)，远小于 MySQL 默认 Packet 限制
                    // 这样既能保存绝大多数文件的完整差异，又能防止极端大文件搞挂数据库连接
                    if(diff != null && diff.length() > 1000000) {
                        diff = diff.substring(0, 1000000) + "\n\n... (Diff content too large, truncated for safety) ...";
                    }

                    SfDeploymentHistoryDetail detail = new SfDeploymentHistoryDetail();
                    detail.setHistoryId(historyId);
                    detail.setMetadataType(item.getMetadataType());
                    detail.setMemberName(item.getMemberName());
                    detail.setAction(action);
                    detail.setDiffContent(diff); // <--- 保存 Diff

                    detailMapper.insert(detail);
                }
            }
        } catch(Exception e) {
            // 【核心优化 2】异常隔离
            // 仅仅是保存历史明细失败，绝对不能抛出异常去影响主部署流程的状态更新
            // 这里只打印日志，吞掉异常
            System.err.println("保存部署历史明细/Diff失败 (非关键错误): " + e.getMessage());
            e.printStackTrace();
        }
    }
}
