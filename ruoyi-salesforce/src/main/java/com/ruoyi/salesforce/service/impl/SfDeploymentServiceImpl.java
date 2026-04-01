package com.ruoyi.salesforce.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentHistory;
import com.ruoyi.salesforce.domain.SfDeploymentHistoryDetail;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryDetailMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentItemMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentMapper;
import com.ruoyi.salesforce.service.ISfDeploymentService;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.utils.MetadataCleaner;
import com.ruoyi.salesforce.utils.PackageXmlBuilder;
import com.ruoyi.salesforce.utils.SfMetadataDiffUtils;
import com.ruoyi.salesforce.websocket.DeployWebSocketServer;
import com.sforce.soap.metadata.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SfDeploymentServiceImpl extends ServiceImpl<SfDeploymentMapper, SfDeployment> implements ISfDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(SfDeploymentServiceImpl.class);

    @Autowired
    private SfDeploymentMapper sfDeploymentMapper;

    @Autowired
    private SfDeploymentItemMapper sfDeploymentItemMapper;

    @Autowired
    private ISfMetadataService sfMetadataService;

    @Autowired
    private SfBackupService backupService;
    @Autowired
    private SfHistoryService historyService;

    @Autowired
    private SfRollbackService rollbackService;

    @Autowired
    private SfDeploymentItemServiceImpl sfDeploymentItemService;

    @Autowired
    private SfDeploymentHistoryMapper sfDeploymentHistoryMapper;

    @Autowired
    private SfDeploymentHistoryDetailMapper sfDeploymentHistoryDetailMapper;

    @Autowired
    private SfGitSyncService gitSyncService;

    @Autowired
    @Qualifier("deployTaskExecutor")
    private Executor deployExecutor;

    @Override
    public List<SfDeployment> selectSfDeploymentList(SfDeployment sfDeployment) {
        return this.baseMapper.selectSfDeploymentList(sfDeployment);
    }

    @Override
    public SfDeployment selectSfDeploymentById(Long id) {
        SfDeployment deployment = this.baseMapper.selectSfDeploymentById(id);
        if(deployment != null) {
            List<SfDeploymentItem> items = sfDeploymentItemMapper.selectList(
                    new LambdaQueryWrapper<SfDeploymentItem>().eq(SfDeploymentItem::getDeploymentId, id)
            );
            deployment.setItemList(items);
        }
        return deployment;
    }

    @Override
    public int insertSfDeployment(SfDeployment sfDeployment) {
        sfDeployment.setCreateTime(new Date());
        sfDeployment.setStatus("Draft");
        // 【新增】自动注入当前用户的 部门ID 和 用户ID
        if (StringUtils.isEmpty(sfDeployment.getTenantId())) {
            sfDeployment.setTenantId(SecurityUtils.getLoginUser().getTenantId());
        }
        try {
            sfDeployment.setUserId(SecurityUtils.getUserId());
            sfDeployment.setDeptId(SecurityUtils.getDeptId());
        } catch(Exception e) {
            log.warn("无法获取用户信息，可能是定时任务触发");
        }
        return sfDeploymentMapper.insert(sfDeployment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addItems(Long deploymentId, List<SfDeploymentItem> items) {
        SfDeployment deployment = sfDeploymentMapper.selectById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        checkIfLocked(deployment);

        // 1. 填充元数据信息 (修改人/时间)
        populateMetadataInfo(deployment.getSourceOrgId(), items);

        // 2. 入库
        for(SfDeploymentItem item : items) {
            item.setDeploymentId(deploymentId);
            item.setCreateTime(new Date());
            item.setAction("Add");

            // 【核心优化】不再强制设为 "Comparing"，而是使用前端传入的状态
            if(StringUtils.isEmpty(item.getDiffStatus())) {
                item.setDiffStatus("Unknown"); // 兜底
            }
            // 如果前端传了 "New"/"Changed"/"Same"，就直接存入数据库

            sfDeploymentItemMapper.insert(item);
        }

        deployment.setUpdateTime(new Date());
        int rows = sfDeploymentMapper.updateById(deployment);
        if(rows == 0) {
            throw new ServiceException("数据已发生变更(乐观锁冲突)，请刷新页面后重试");
        }

        // 3. 触发异步预取内容
        try {
            sfMetadataService.preloadMetadata(deployment.getSourceOrgId(), items, SecurityUtils.getLoginUser().getTenantId());
        } catch(Exception e) {
            log.warn("触发预取任务失败: {}", e.getMessage());
        }
    }

    /**
     * 【新增】辅助方法：从缓存或API填充元数据的修改人与修改时间
     */
    private void populateMetadataInfo(Long orgId, List<SfDeploymentItem> items) {
        if(orgId == null || items == null || items.isEmpty()) return;

        try {
            Map<String, List<SfDeploymentItem>> typeMap = new HashMap<>();
            for(SfDeploymentItem item : items) {
                typeMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>()).add(item);
            }

            for(Map.Entry<String, List<SfDeploymentItem>> entry : typeMap.entrySet()) {
                String type = entry.getKey();
                List<SfDeploymentItem> currentTypeItems = entry.getValue();

                List<FileProperties> remoteList = sfMetadataService.listMetadata(orgId, type);

                Map<String, FileProperties> remoteMap = new HashMap<>();
                if(remoteList != null) {
                    for(FileProperties fp : remoteList) {
                        remoteMap.put(fp.getFullName(), fp);
                    }
                }

                for(SfDeploymentItem item : currentTypeItems) {
                    FileProperties match = remoteMap.get(item.getMemberName());
                    if(match != null) {
                        item.setLastModifiedByName(match.getLastModifiedByName());

                        // 【修复 1】日期 1970 问题修复
                        // 判断是否为有效日期（例如大于 2000-01-01），过滤掉 null 或 1970 默认值
                        // 946684800000L = 2000-01-01 00:00:00
                        if(match.getLastModifiedDate() != null && match.getLastModifiedDate().getTimeInMillis() > 946684800000L) {
                            item.setLastModifiedDate(match.getLastModifiedDate().getTime());
                        } else {
                            item.setLastModifiedDate(null); // 显式置空
                        }
                    }
                }
            }
        } catch(Exception e) {
            log.warn("自动填充元数据修改信息失败: {}", e.getMessage());
        }
    }


    @Override
    public int updateSfDeployment(SfDeployment sfDeployment) {
        sfDeployment.setUpdateTime(new Date());
        return sfDeploymentMapper.updateById(sfDeployment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteSfDeploymentByIds(Long[] ids) {
        if(ids == null || ids.length == 0) return 0;
        List<Long> deploymentIds = Arrays.asList(ids);

        // =================================================================================
        // 阶段 1: 清理历史记录、审计明细、物理备份文件
        // =================================================================================

        // 1.1 查出这些部署包关联的所有历史记录
        List<SfDeploymentHistory> histories = sfDeploymentHistoryMapper.selectList(
                new LambdaQueryWrapper<SfDeploymentHistory>()
                        .in(SfDeploymentHistory::getDeploymentId, deploymentIds)
        );

        if(histories != null && !histories.isEmpty()) {
            List<Long> historyIds = new ArrayList<>();
            for(SfDeploymentHistory history : histories) {
                historyIds.add(history.getId());

                // 1.2 【关键】删除磁盘上的物理备份文件
                if(StringUtils.isNotEmpty(history.getBackupPath())) {
                    try {
                        File backupFile = new File(history.getBackupPath());
                        if(backupFile.exists() && backupFile.isFile()) {
                            boolean deleted = backupFile.delete();
                            if(deleted) {
                                log.info("已清理部署包删除后的关联备份文件: {}", history.getBackupPath());
                            }
                        }
                    } catch(Exception e) {
                        // 文件删除失败不应阻断数据库删除流程，仅记录日志
                        log.warn("清理备份文件失败: {} - {}", history.getBackupPath(), e.getMessage());
                    }
                }
            }

            // 1.3 批量删除历史明细 (审计日志)
            if(!historyIds.isEmpty()) {
                sfDeploymentHistoryDetailMapper.delete(
                        new LambdaQueryWrapper<SfDeploymentHistoryDetail>()
                                .in(SfDeploymentHistoryDetail::getHistoryId, historyIds)
                );

                // 1.4 批量删除历史主表
                sfDeploymentHistoryMapper.deleteBatchIds(historyIds);
            }
        }

        // =================================================================================
        // 阶段 2: 清理部署包自身数据
        // =================================================================================

        // 2.1 批量删除部署包明细 (Items)
        // 使用 delete 配合条件构造器，比循环 delete 性能更好
        sfDeploymentItemMapper.delete(
                new LambdaQueryWrapper<SfDeploymentItem>()
                        .in(SfDeploymentItem::getDeploymentId, deploymentIds)
        );

        // 2.2 批量删除部署包主表
        return sfDeploymentMapper.deleteBatchIds(deploymentIds);
    }

    @Override
    public void removeItems(List<Long> itemIds) {
        if(itemIds == null || itemIds.isEmpty()) return;

        // 【优化】校验状态。因为传入的是itemId，先查出 deploymentId
        SfDeploymentItem item = sfDeploymentItemMapper.selectById(itemIds.get(0));
        if(item != null) {
            SfDeployment deployment = sfDeploymentMapper.selectById(item.getDeploymentId());
            if(deployment != null) {
                checkIfLocked(deployment);

                // 【核心优化 3.1】移除子项也要升级主表版本
                deployment.setUpdateTime(new Date());
                int rows = sfDeploymentMapper.updateById(deployment);
                if(rows == 0) {
                    throw new ServiceException("操作失败：部署包已被其他人修改，请刷新重试");
                }
            }
        }

        sfDeploymentItemMapper.deleteBatchIds(itemIds);
    }

    private void checkIfLocked(SfDeployment deployment) {
        String s = deployment.getStatus();
        if("Processing".equals(s) || "Validating".equals(s) || "Deploying".equals(s) ||
                "Pending".equals(s) || "InProgress".equals(s) || "Queued".equals(s)) {
            throw new ServiceException("当前部署包正在执行验证或部署任务，禁止修改元数据！");
        }
    }

    @Override
    public List<SfDeploymentItem> selectItems(Long deploymentId) {
        return sfDeploymentItemMapper.selectList(
                new LambdaQueryWrapper<SfDeploymentItem>().eq(SfDeploymentItem::getDeploymentId, deploymentId)
        );
    }

    private void checkIfLocked(Long deploymentId) {
        SfDeployment d = sfDeploymentMapper.selectById(deploymentId);
        if(d != null) checkIfLocked(d);
    }


    // ================== 部署核心逻辑 ==================

    @Override
    public void deployPackage(Long deploymentId, boolean checkOnly) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        if("Processing".equals(deployment.getStatus()) || "Deploying".equals(deployment.getStatus())) {
            throw new ServiceException("当前部署包正在处理中，请勿重复操作");
        }

        List<SfDeploymentItem> items = selectItems(deploymentId);
        if(items.isEmpty()) throw new ServiceException("部署包为空，请先添加元数据");

        // ========================= 【新增 2.2 预检机制】 =========================
        // 在状态变更为 Processing 之前，同步检查环境连通性
        // 如果这里失败，直接抛出异常给前端，状态保持原样 (Draft/Validated)，不会卡死
        try {
            log.info("开始部署预检: DeploymentId={}", deploymentId);
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "开始部署预检: DeploymentId=" + deploymentId));
            // 1. 检查源环境 (拉取代码需要)
            sfMetadataService.validateOrgConnection(deployment.getSourceOrgId());
            // 2. 检查目标环境 (部署需要)
            sfMetadataService.validateOrgConnection(deployment.getTargetOrgId());
            log.info("部署预检通过");
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "部署预检通过！"));
        } catch(Exception e) {
            log.error("部署预检失败", e);
            throw new ServiceException("环境连接预检失败: " + e.getMessage() + "，请检查授权状态或网络连接。");
        }

        // 解决 "任务在提交前已被取消" 的 Bug
        // 如果当前状态是已取消或失败，说明这是一次"重试"。
        // 必须清空 lastAsyncId，防止上一轮残留的后台监控线程通过旧ID查到这条记录并覆盖状态。
        if("Canceled".equals(deployment.getStatus()) || "Failed".equals(deployment.getStatus())) {
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在清空上次的同步的lastAsyncId..."));
            deployment.setLastAsyncId(null);
        }
        // =========================================================================

        deployment.setStatus("Processing");
        deployment.setErrorMsg("");
        // 【核心优化 3.1：乐观锁检查】
        // 这里 updateById 会带上 WHERE id=? AND version=?
        // 如果在此期间有人执行了 addItems 导致 version+1，这里就会更新失败返回 0
        int rows = sfDeploymentMapper.updateById(deployment);
        if(rows == 0) {
            throw new ServiceException("部署启动失败：部署包内容或状态已被其他人修改，请刷新页面重新检查。");
        }

        // 2. 强制清空 error_msg (解决 MyBatis-Plus 忽略空字符串更新的问题)
        // 使用 UpdateWrapper 可以无视 FieldStrategy 策略，强行将字段刷为空
        sfDeploymentMapper.update(null, new LambdaUpdateWrapper<SfDeployment>()
                .eq(SfDeployment::getId, deployment.getId())
                .set(SfDeployment::getErrorMsg, "")
        );

        CompletableFuture.runAsync(() -> {
            try {
                processAsyncDeployment(deployment, items, checkOnly);
            } catch(Exception e) {
                log.error("异步部署任务异常", e);
                handleDeploymentError(deployment.getId(), "系统内部错误: " + e.getMessage());
            }
        }, deployExecutor);
    }

    // =================================================================
    // 【优化】统一部署入口：普通部署调用此方法
    // =================================================================
    private void processAsyncDeployment(SfDeployment deployment, List<SfDeploymentItem> items, boolean checkOnly) {
        // 调用重载的核心方法，rollbackFromHistoryId 传 null
        processAsyncDeploymentCore(deployment, items, checkOnly, null);
    }

    // =================================================================
    // 【核心重构】通用的异步部署处理方法 (支持 普通部署、验证、回滚)
    // =================================================================
    private void processAsyncDeploymentCore(SfDeployment deployment, List<SfDeploymentItem> items,
                                            boolean checkOnly, Long rollbackFromHistoryId) {
        Long deployId = deployment.getId();
        // 标记变量
        boolean isRollback = (rollbackFromHistoryId != null);
        String deployType = isRollback ? "Rollback" : (checkOnly ? "Validate" : "Deploy");
        String currentStatus = "Processing";
        // 1. 初始化历史记录
        // --- 节点 1: 初始化 ---
        sendStepLog(deployId, currentStatus, ">>> 任务启动: " + deployType + " (" + deployment.getTitle() + ")");
        sendStepLog(deployId, currentStatus, "正在初始化历史记录与审计追踪...");

        SfDeploymentHistory history = historyService.initHistory(deployment.getId(), deployment.getTargetOrgId(), deployType);

        SfBackupService.BackupResult backupResult = null;
        String newAsyncId = null;

        java.io.File sourceZipTemp = null;
        try {
            // [检查点]
            checkInterrupted(deployment.getId());

            byte[] zipBytes = null;

            // =========================================================
            // 分支 A：回滚模式 (生成混合包)
            // =========================================================
            if(isRollback) {
                sendStepLog(deployId, currentStatus, "检测到回滚请求，开始基于历史记录 [" + rollbackFromHistoryId + "] 构建回滚包...");
                log.info("正在生成回滚包，基于历史ID: {}", rollbackFromHistoryId);
                zipBytes = rollbackService.generateRollbackPackage(rollbackFromHistoryId);
                sendStepLog(deployId, currentStatus, "回滚包 (DestructiveChanges) 构建完成，大小: " + (zipBytes.length / 1024) + "KB");
            }
            // =========================================================
            // 分支 B：普通/验证模式 (从源环境拉取)
            // =========================================================
            else {
                // 1. 提取
                sendStepLog(deployId, currentStatus, "开始提取元数据并生成 Package Manifest (package.xml)...");
                com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

                checkInterrupted(deployment.getId());

                sendStepLog(deployId, currentStatus, "正在调用 Salesforce API 从源环境提取元数据 (Retrieve)...");
                long t1 = System.currentTimeMillis();
                zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);
                long t2 = System.currentTimeMillis();

                checkInterrupted(deployment.getId());

                if(zipBytes == null || zipBytes.length == 0) {
                    throw new RuntimeException("提取代码失败：返回的ZIP包为空");
                }
                sendStepLog(deployId, currentStatus, "元数据提取成功，耗时: " + (t2 - t1) + "ms，大小: " + (zipBytes.length / 1024) + "KB");

                // 2. 清洗
                sendStepLog(deployId, currentStatus, "正在执行元数据清洗规则 (去除无关字段)...");
                zipBytes = MetadataCleaner.clean(zipBytes, items);

                // 3. 备份 (仅当 不是验证 且 不是回滚 时执行)
                // 回滚操作本身不应再次触发备份，防止覆盖原有备份或产生脏备份
                if(!checkOnly) {
                    try {
                        sendStepLog(deployId, currentStatus, "正在启动目标环境备份 (Pre-Deployment Backup)...");
                        backupResult = backupService.performBackup(deployment.getTargetOrgId(), items);
                        sendStepLog(deployId, currentStatus, "备份完成，文件已归档: " + backupResult.getBackupFilePath());
                        log.info("备份成功，路径: {}", backupResult.getBackupFilePath());
                    } catch(Exception e) {
                        log.error("备份失败", e);
                        throw new ServiceException("备份失败，为保证安全已终止部署: " + e.getMessage());
                    }
                } else {
                    sendStepLog(deployId, currentStatus, "仅验证模式 (CheckOnly)，跳过备份步骤。");
                }
            }

            // [检查点] 上传前最后检查
            checkInterrupted(deployment.getId());

            // =========================================================
            // 统一上传与部署
            // =========================================================
            String actionText = isRollback ? "正在上传回滚包至目标环境..." : "正在上传部署包至目标环境...";
            sendStepLog(deployId, currentStatus, actionText);

            MetadataConnection targetConn = sfMetadataService.getMetadataConnection(deployment.getTargetOrgId());

            DeployOptions deployOptions = new DeployOptions();
            deployOptions.setPerformRetrieve(false);
            deployOptions.setCheckOnly(checkOnly);

            // 如果是回滚，通常建议忽略警告并在出错时回滚
            deployOptions.setRollbackOnError(true);
            if(isRollback) {
                deployOptions.setIgnoreWarnings(true);
            }

            // 处理测试级别 (逻辑通用)
            // 回滚时也需要遵循原部署包的测试策略 (如生产环境必须跑测试)
            // 1. 沙盒环境 -> 默认为 NoTestRun
            // 2. 生产环境(含Apex) -> 默认为 RunLocalTests
            // 3. 生产环境(无Apex) -> 默认为 不运行测试
            if("RunSpecifiedTests".equals(deployment.getTestLevel())) {
                deployOptions.setTestLevel(TestLevel.RunSpecifiedTests);
                if(StringUtils.isNotEmpty(deployment.getSpecifiedTests())) {
                    deployOptions.setRunTests(deployment.getSpecifiedTests().split(","));
                    sendStepLog(deployId, currentStatus, "测试策略: 指定测试类 (" + deployment.getSpecifiedTests() + ")");
                }
            } else if("RunLocalTests".equals(deployment.getTestLevel())) {
                deployOptions.setTestLevel(TestLevel.RunLocalTests);
                sendStepLog(deployId, currentStatus, "测试策略: 运行所有本地测试");
            } else {
                sendStepLog(deployId, currentStatus, "测试策略: 默认 (NoTestRun/Default)");
            }

            log.info("执行部署/回滚，Org: {}, Option: CheckOnly={}", deployment.getTargetOrgId(), checkOnly);
            AsyncResult deployAsync = targetConn.deploy(zipBytes, deployOptions);
            newAsyncId = deployAsync.getId();

            currentStatus = checkOnly ? "Validating" : "Deploying";
            sendStepLog(deployId, currentStatus, "上传成功！Salesforce 任务 ID: " + newAsyncId);
            sendStepLog(deployId, currentStatus, ">>> 进入云端处理阶段，开始轮询状态...");

            // [Race Condition Check]
            SfDeployment currentCheck = sfDeploymentMapper.selectById(deployment.getId());
            if("Canceling".equals(currentCheck.getStatus()) || "Canceled".equals(currentCheck.getStatus())) {
                log.warn("检测到任务在提交期间被取消，立即撤回: {}", newAsyncId);
                sfMetadataService.cancelDeploy(deployment.getTargetOrgId(), newAsyncId);
                throw new InterruptedException("任务在提交后立即被取消");
            }

            // 更新历史记录
            historyService.updateAsyncId(history.getId(), newAsyncId);

            // 更新部署包状态
            SfDeployment update = new SfDeployment();
            update.setId(deployment.getId());
            update.setStatus(checkOnly ? "Validating" : "Deploying");
            update.setLastAsyncId(newAsyncId);
            sfDeploymentMapper.updateById(update);

            // 将内存中的 ZIP 包写入临时文件，传递 File 对象给异步线程，而非 byte[]
            // 这样当前线程结束时，zipBytes 即可被 GC 回收
            if(zipBytes != null && zipBytes.length > 0) {
                sourceZipTemp = saveTempFile(zipBytes, "deploy_source_" + deployment.getId());
            }

            // 启动监控 (传入 rollbackFromHistoryId)
            startMonitoring(deployment.getId(), deployment.getTargetOrgId(), newAsyncId,
                    history.getId(), backupResult, items, rollbackFromHistoryId, sourceZipTemp);

        } catch(InterruptedException e) {
            log.info("任务被中断: {}", e.getMessage());
            sendStepLog(deployId, "Canceled", "!!! 任务被用户中断 !!!");
            handleDeploymentLocalCancel(deployment.getId());
            historyService.finishHistory(history.getId(), "Canceled", "用户取消");
        } catch(Exception e) {
            log.error("部署/回滚流程异常", e);
            handleDeploymentError(deployment.getId(), "流程异常: " + e.getMessage());
            sendStepLog(deployId, "Failed", "!!! 发生异常: " + e.getMessage());
            historyService.finishHistory(history.getId(), "Failed", e.getMessage());
            // 如果 sourceZipTemp 已经创建，但因为这里抛异常导致 startMonitoring 没跑，
            // 必须在这里删掉，否则会变成垃圾文件。
            if(sourceZipTemp != null && sourceZipTemp.exists()) {
                try {
                    sourceZipTemp.delete();
                    log.info("部署异常终止，已清理临时文件: {}", sourceZipTemp.getName());
                } catch(Exception ex) {
                    log.warn("清理临时文件失败", ex);
                }
            }
        }
    }

    /**
     * 【新增】辅助方法：检查任务是否被中断
     */
    private void checkInterrupted(Long deploymentId) throws InterruptedException {
        // 这里必须查库，因为 Controller 里的 cancelDeploymentTask 修改的是数据库状态
        SfDeployment current = sfDeploymentMapper.selectById(deploymentId);
        if(current != null && ("Canceling".equals(current.getStatus()) || "Canceled".equals(current.getStatus()))) {
            throw new InterruptedException("User canceled the operation");
        }
    }

    /**
     * 【新增】处理本地取消的情况（还没发给SF就取消了）
     */
    private void handleDeploymentLocalCancel(Long deploymentId) {
        SfDeployment update = new SfDeployment();
        update.setId(deploymentId);
        update.setStatus("Canceled");
        update.setErrorMsg("任务在提交到 Salesforce 之前已被取消。");
        sfDeploymentMapper.updateById(update);

        // 推送最终取消状态
        JSONObject json = new JSONObject();
        json.put("status", "Canceled");
        json.put("done", true);
        DeployWebSocketServer.sendMessage(deploymentId, json.toJSONString());
    }

    @Override
    public void quickDeploy(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        if(!"Validated".equals(deployment.getStatus()) || deployment.getLastAsyncId() == null) {
            throw new ServiceException("只有【验证成功】的部署包才能使用快速部署");
        }

        // ========================= 【新增 2.2 预检机制】 =========================
        try {
            log.info("开始快速部署预检: DeploymentId={}", deploymentId);
            // 快速部署主要依赖目标环境，源环境虽然用于审计(catch了异常)，但建议一并检查保证健康
            sfMetadataService.validateOrgConnection(deployment.getTargetOrgId());
            // 源环境可选检查，如果为了严谨可以加上
            sfMetadataService.validateOrgConnection(deployment.getSourceOrgId());
        } catch(Exception e) {
            throw new ServiceException("环境预检失败: " + e.getMessage());
        }
        // =========================================================================

        SfDeploymentHistory history = historyService.initHistory(deployment.getId(), deployment.getTargetOrgId(), "Quick");

        deployment.setStatus("Deploying");
        deployment.setErrorMsg("");
        // 【核心优化 3.1：乐观锁检查】
        int rows = sfDeploymentMapper.updateById(deployment);
        if(rows == 0) {
            throw new ServiceException("快速部署启动失败：数据已被修改，请刷新重试。");
        }

        // 2. 强制清空 error_msg
        sfDeploymentMapper.update(null, new LambdaUpdateWrapper<SfDeployment>()
                .eq(SfDeployment::getId, deployment.getId())
                .set(SfDeployment::getErrorMsg, "")
        );

        CompletableFuture.runAsync(() -> {
            SfBackupService.BackupResult backupResult = null;
            byte[] payloadZipBytes = null; // 用于 Diff
            List<SfDeploymentItem> items = selectItems(deploymentId);

            String currentStatus = "Deploying";
            Long deployId = deployment.getId();

            try {
                sendStepLog(deployId, currentStatus, ">>> 快速部署任务启动 (基于验证ID: " + deployment.getLastAsyncId() + ")");

                checkInterrupted(deployment.getId());

                // 1. 【新增步骤】为了审计和Diff，我们必须从源环境拉取一次代码作为"After"状态
                // 虽然快速部署不使用这个包上传，但记录历史需要它。
                try {
                    sendStepLog(deployId, currentStatus, "正在从源环境拉取最新元数据用于审计记录与差异比对...");
                    com.sforce.soap.metadata.Package manifest = generateManifestObject(items);
                    payloadZipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);
                    // 清洗一下，保持一致性
                    payloadZipBytes = MetadataCleaner.clean(payloadZipBytes, items);
                    sendStepLog(deployId, currentStatus, "审计数据准备完成 (大小: " + (payloadZipBytes != null ? payloadZipBytes.length / 1024 : 0) + " KB)");
                } catch(Exception e) {
                    log.warn("快速部署拉取源文件用于审计失败 (不影响部署): {}", e.getMessage());
                    sendStepLog(deployId, currentStatus, "[警告] 审计数据拉取失败，本次记录可能无法生成Diff详情: " + e.getMessage());
                }

                checkInterrupted(deployment.getId());

                // 2. 执行备份 (Before State)
                try {
                    if(items != null && !items.isEmpty()) {
                        sendStepLog(deployId, currentStatus, "正在备份目标环境...");
                        backupResult = backupService.performBackup(deployment.getTargetOrgId(), items);
                        sendStepLog(deployId, currentStatus, "备份成功: " + backupResult.getBackupFilePath());
                    } else {
                        sendStepLog(deployId, currentStatus, "部署包为空，跳过备份步骤。");
                    }
                } catch(Exception e) {
                    throw new ServiceException("备份失败: " + e.getMessage());
                }

                checkInterrupted(deployment.getId());

                // 3. 调用 Salesforce 快速部署接口
                sendStepLog(deployId, currentStatus, "正在调用 Salesforce 快速部署接口 (deployRecentValidation)...");
                log.info("开始快速部署, Org: {}, ValidationId: {}", deployment.getTargetOrgId(), deployment.getLastAsyncId());
                String newProcessId = sfMetadataService.deployRecentValidation(
                        deployment.getTargetOrgId(),
                        deployment.getLastAsyncId()
                );
                sendStepLog(deployId, currentStatus, "指令发送成功！新的任务 ID: " + newProcessId);

                historyService.updateAsyncId(history.getId(), newProcessId);

                SfDeployment update = new SfDeployment();
                update.setId(deployment.getId());
                update.setLastAsyncId(newProcessId);
                sfDeploymentMapper.updateById(update);

                // 【优化】将 payloadZipBytes 转为临时文件
                java.io.File payloadTemp = null;
                if(payloadZipBytes != null) {
                    payloadTemp = saveTempFile(payloadZipBytes, "quick_audit_" + deployment.getId());
                }

                // 4. 启动监控，传入 payloadZipBytes
                sendStepLog(deployId, currentStatus, ">>> 进入云端处理阶段，开始轮询状态...");
                startMonitoring(deployment.getId(), deployment.getTargetOrgId(), newProcessId,
                        history.getId(), backupResult, items, null, payloadTemp);

            } catch(InterruptedException e) {
                sendStepLog(deployId, "Canceled", "!!! 任务被用户中断 !!!");
                handleDeploymentLocalCancel(deployment.getId());
                historyService.finishHistory(history.getId(), "Canceled", "用户取消");
            } catch(Exception e) {
                log.error("快速部署失败", e);
                sendStepLog(deployId, "Failed", "!!! 快速部署异常: " + e.getMessage());
                handleDeploymentError(deployment.getId(), "快速部署异常: " + e.getMessage());
                historyService.finishHistory(history.getId(), "Failed", e.getMessage());
            }
        }, deployExecutor);
    }

    /**
     * 【新增】后台监控线程
     * 轮询 Salesforce 状态并推送 WebSocket，直到完成
     */
    private void startMonitoring(Long deploymentId, Long targetOrgId, String processId,
                                 Long historyId, SfBackupService.BackupResult backupResult,
                                 List<SfDeploymentItem> items,
                                 Long originalHistoryId,
                                 java.io.File sourceZipTemp) {
        CompletableFuture.runAsync(() -> {
            boolean done = false;
            long startTime = System.currentTimeMillis();
            // 状态缓存，用于去重
            String lastRemoteState = "";
            int lastCompDeployed = -1;
            int lastTestCompleted = -1;

            while(!done) {
                try {
                    // 超时保护 (1小时)
                    if(System.currentTimeMillis() - startTime > 3600 * 1000) {
                        log.error("部署监控超时，停止轮询: {}", processId);
                        // 超时也记录历史
                        historyService.finishHistory(historyId, "Failed", "系统轮询超时，请去Salesforce后台查看最终状态。");
                        break;
                    }

                    // 1. 调用 sfMetadataService 获取状态 (返回的是安全 JSON 字符串)
                    // 注意：checkDeployStatus 内部已经调用了 extractErrorMessage 生成了详细的 errorMessage
                    String statusJson = checkDeployStatus(targetOrgId, processId);

                    // 2. 推送消息给前端
//                    DeployWebSocketServer.sendMessage(deploymentId, statusJson);

                    // 3. 判断是否结束
                    JSONObject json = JSONObject.parseObject(statusJson);

                    // 2. 解析关键字段
                    String currentRemoteState = json.getString("status"); // Pending, InProgress...
                    int numCompDeployed = json.getIntValue("numberComponentsDeployed");
                    int numCompTotal = json.getIntValue("numberComponentsTotal");
                    int numTestsCompleted = json.getIntValue("numberTestsCompleted");
                    int numTestsTotal = json.getIntValue("numberTestsTotal");

                    // 3. 【核心优化】智能日志生成：只有状态变了才发 stateDetail 日志
                    // 注意：checkDeployStatus 里的 buildProgressJson 可能会把 stateDetail 写死为 null
                    // 我们这里需要根据变化动态注入 stateDetail

                    String dynamicLog = null;

                    // 场景 A: 状态变更 (如 Queued -> InProgress)
                    if(!currentRemoteState.equals(lastRemoteState)) {
                        dynamicLog = "云端状态变更: " + lastRemoteState + " -> " + currentRemoteState;
                        lastRemoteState = currentRemoteState;
                    }
                    // 场景 B: 组件部署进度变化 (每 5 个或者 100% 时通知，防止刷屏，或者每次变都通知，WebSocket 扛得住)
                    else if(numCompDeployed > lastCompDeployed && numCompTotal > 0) {
                        if(numCompDeployed == numCompTotal || numCompDeployed % 5 == 0) {
                            dynamicLog = "元数据处理进度: " + numCompDeployed + " / " + numCompTotal;
                        }
                        lastCompDeployed = numCompDeployed;
                    }
                    // 场景 C: 测试执行进度变化
                    else if(numTestsCompleted > lastTestCompleted && numTestsTotal > 0) {
                        if(numTestsCompleted == numTestsTotal || numTestsCompleted % 5 == 0) {
                            dynamicLog = "单元测试执行进度: " + numTestsCompleted + " / " + numTestsTotal;
                        }
                        lastTestCompleted = numTestsCompleted;
                    }

                    boolean isDone = json.getBooleanValue("done");
                    if (isDone) {
                        json.put("done", false); // 强制伪装成未完成，防止前端过早断开
                    }
                    if (dynamicLog != null) {
                        json.put("stateDetail", dynamicLog);
                    }
                    DeployWebSocketServer.sendMessage(deploymentId, json.toJSONString());

                    if(isDone) {
                        done = true;

                        // =========================================================
                        // 【核心优化】监控结束，解析详细错误信息并写入历史表
                        // =========================================================
                        String finalStatus = json.getString("status");

                        // 优先获取 errorMessage (这里包含了组件错误、测试失败、覆盖率警告等详细信息)
                        String errorMsg = json.getString("errorMessage");
                        // 如果 errorMessage 为空，尝试获取 errorMsg (兼容性)
                        if(StringUtils.isEmpty(errorMsg)) {
                            errorMsg = json.getString("errorMsg");
                        }

                        // 调用优化后的 finishHistory，传入详细错误信息
                        historyService.finishHistory(historyId, finalStatus, errorMsg);

                        // 如果部署成功(Succeeded)，且有备份，则保存备份明细
                        // 2. 保存备份明细 (只要部署成功 且 有备份结果，就应该保存)
                        // 修复：去掉了 && originalHistoryId != null 的条件
                        if("Succeeded".equals(finalStatus)) {
                            byte[] beforeZip = null;
                            byte[] afterZip = null;

                            try {
                                // 1. 从磁盘读取备份文件 (Before State)
                                if(backupResult != null && StringUtils.isNotEmpty(backupResult.getBackupFilePath())) {
                                    beforeZip = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(backupResult.getBackupFilePath()));
                                }

                                // 2. 从临时文件读取源文件 (After State)
                                if(sourceZipTemp != null && sourceZipTemp.exists()) {
                                    afterZip = java.nio.file.Files.readAllBytes(sourceZipTemp.toPath());
                                }

                                // 3. 计算 Diff
                                Map<String, String> diffMap = new HashMap<>();
                                if(items != null && !items.isEmpty()) {
                                    diffMap = SfMetadataDiffUtils.generateDiffMap(beforeZip, afterZip, items);
                                }

                                // 4. 保存
                                if(backupResult != null) {
                                    historyService.saveBackupAndDetails(historyId, backupResult.getBackupFilePath(),
                                            backupResult.getActionMap(), diffMap, items);
                                }
                            } catch(Exception e) {
                                log.error("读取审计文件或计算Diff失败", e);
                            }
                            // =========================================================
                            // 【里程碑 3 核心触发点】：拦截部署成功事件，启动 GitOps 流水线
                            // =========================================================
                            try {
                                SfDeployment currentDeploy = sfDeploymentMapper.selectById(deploymentId);
                                // 校验：是否开启了同步，且目标分支已填写，且当前不是"仅验证"操作
                                boolean isCheckOnlyTask = "Validate".equals(historyService.getById(historyId).getType());

                                if (!isCheckOnlyTask && currentDeploy != null
                                        && currentDeploy.getSyncGit() != null && currentDeploy.getSyncGit() == 1
                                        && StringUtils.isNotEmpty(currentDeploy.getTargetBranch())) {

                                    if(sourceZipTemp != null && sourceZipTemp.exists()) {
                                        // 触发同步（由于身处异步线程，此处同步调用，Git 日志会按顺序推送给前端）
                                        gitSyncService.syncToGit(currentDeploy, sourceZipTemp, historyId);
                                    } else {
                                        log.warn("无法执行 Git 同步：用于同步的部署包源物理文件已丢失");
                                    }
                                }
                            } catch(Exception e) {
                                // 绝不让 Git 的异常影响 Salesforce 已经成功的部署状态
                                log.error("调用 Git 引擎启动失败", e);
                            } finally {
                                // 5. 【关键】Git 使用完毕，安全清理磁盘临时文件
                                if(sourceZipTemp != null && sourceZipTemp.exists()) {
                                    sourceZipTemp.delete();
                                    log.info("部署与 Git 流程全部结束，已清理临时源文件。");
                                }
                            }
                        }

                        // 3. 如果是回滚操作成功，标记原历史记录为已回滚
                        if("Succeeded".equals(finalStatus) && originalHistoryId != null) {
                            try {
                                log.info("回滚成功，标记原历史记录 [{}] 为已回滚", originalHistoryId);
                                historyService.updateStatus(originalHistoryId, "RolledBack");
                            } catch(Exception e) {
                                log.error("更新原记录状态失败", e);
                            }
                        }
                        finalStatus = json.getString("status");
                        sendStepLog(deploymentId, finalStatus, ">>> 任务结束，最终结果: " + finalStatus);

                        // =========================================================
                        // 【核心修复】：所有任务(含Git)彻底结束后，再给前端发送真实的 done 信号
                        // =========================================================
                        JSONObject finalJson = new JSONObject();
                        finalJson.put("done", true);
                        finalJson.put("status", finalStatus);
                        DeployWebSocketServer.sendMessage(deploymentId, finalJson.toJSONString());

                        log.info("部署任务结束: {}", processId);
                    } else {
                        // 未结束，等待 2 秒
                        TimeUnit.SECONDS.sleep(2);
                        if(dynamicLog != null) {
                            json.put("stateDetail", dynamicLog);
                            DeployWebSocketServer.sendMessage(deploymentId, json.toJSONString());
                        } else {
                            DeployWebSocketServer.sendMessage(deploymentId, statusJson);
                        }
                    }

                } catch(Exception e) {
                    log.error("监控线程异常", e);
                    // 【资源释放】异常退出也要删文件
                    if(sourceZipTemp != null && sourceZipTemp.exists()) sourceZipTemp.delete();
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch(InterruptedException ignored) {
                    }
                }
            }
        }, deployExecutor);
    }

    // 为了兼容旧代码，提供一个重载方法 (普通部署调用这个)
//    private void startMonitoring(Long deploymentId, Long targetOrgId, String processId,
//                                 Long historyId, SfBackupService.BackupResult backupResult,
//                                 List<SfDeploymentItem> items) {
//        startMonitoring(deploymentId, targetOrgId, processId, historyId, backupResult, items, null);
//    }

    private String buildProgressJson(String status, String detail) {
        JSONObject json = new JSONObject();
        json.put("status", status);
        json.put("stateDetail", detail);
        json.put("numberComponentsTotal", 0);
        json.put("numberComponentsDeployed", 0);
        json.put("done", false);
        return json.toJSONString();
    }

    private String buildErrorJson(String msg) {
        JSONObject json = new JSONObject();
        json.put("status", "Failed");
        json.put("errorMsg", msg);
        json.put("done", true);
        return json.toJSONString();
    }

    @Override
    public String checkDeployStatus(Long targetOrgId, String processId) throws Exception {
        String statusJson;
        try {
            // 1. 调用元数据服务获取状态
            statusJson = sfMetadataService.checkDeployStatus(targetOrgId, processId);
        } catch(Exception e) {
            // 如果底层调用本身出错（如网络超时），构造一个失败的 JSON
            JSONObject errorJson = new JSONObject();
            errorJson.put("done", true);
            errorJson.put("status", "Failed");
            errorJson.put("errorMessage", "获取部署状态异常: " + e.getMessage());
            statusJson = errorJson.toString();
        }

        try {
            JSONObject result = JSONObject.parseObject(statusJson);
            String status = result.getString("status");
            boolean isCheckOnly = result.getBooleanValue("checkOnly");
            boolean isDone = result.getBooleanValue("done");

            String finalStatus = null;
            String errorMessage = null;

            // 2. 根据状态判断最终结果
            if("Succeeded".equals(status) || "Validated".equals(status)) {
                if(isCheckOnly || "Validated".equals(status)) {
                    finalStatus = "Validated";
                } else {
                    finalStatus = "Succeeded";
                    try {
                        sfMetadataService.clearCacheForOrg(targetOrgId);
                    } catch(Exception e) {
                        log.warn("自动清理缓存失败: {}", e.getMessage());
                    }
                }
            } else if("Canceled".equals(status)) {
                finalStatus = "Canceled"; // 明确设置为已取消
                errorMessage = "用户主动取消了部署任务。";
            } else if("Failed".equals(status)) {
                finalStatus = "Failed";
                // 提取详细错误信息
                errorMessage = extractErrorMessage(result);
                if(errorMessage == null || errorMessage.contains("未返回具体错误信息")) {
                    String topLevelMsg = result.getString("errorMessage");
                    if(topLevelMsg != null) {
                        errorMessage = topLevelMsg;
                    }
                }
            } else if(isDone && finalStatus == null) {
                // 防御性编程：如果 done=true 但状态不是上述几种，强制标记为 Failed (防止卡在 Processing)
                finalStatus = "Failed";
                errorMessage = "部署已结束，但在未知状态下停止: " + status;
            }

            // 3. 更新数据库 (确保无论正常流程还是异常流程，只要结束了就更新 DB)
            if(finalStatus != null) {
                SfDeployment deploy = sfDeploymentMapper.selectOne(
                        new LambdaQueryWrapper<SfDeployment>().eq(SfDeployment::getLastAsyncId, processId)
                );
                if(deploy != null) {
                    boolean needUpdate = false;
                    // 状态变更或者是最终态时，强制更新
                    if(!finalStatus.equals(deploy.getStatus())) {
                        deploy.setStatus(finalStatus);
                        needUpdate = true;
                    }
                    if(errorMessage != null) {
                        if(errorMessage.length() > 9900) {
                            errorMessage = errorMessage.substring(0, 9900) + "\n...(错误信息过长已截断)";
                        }
                        // 只要有错误信息就更新，避免覆盖为空
                        if(!errorMessage.equals(deploy.getErrorMsg())) {
                            deploy.setErrorMsg(errorMessage);
                            needUpdate = true;
                        }
                    }
                    // 【新增这段兜底逻辑】：如果任务成功或验证通过，强制剥离上一轮残留的报错信息
                    else if (isDone && ("Succeeded".equals(finalStatus) || "Validated".equals(finalStatus))) {
                        if (StringUtils.isNotEmpty(deploy.getErrorMsg())) {
                            deploy.setErrorMsg("");
                            needUpdate = true;
                            // 强制使用 UpdateWrapper 清空，防止 MyBatis-Plus updateById 策略拦截空字符串
                            sfDeploymentMapper.update(null, new LambdaUpdateWrapper<SfDeployment>()
                                    .eq(SfDeployment::getId, deploy.getId())
                                    .set(SfDeployment::getErrorMsg, "")
                            );
                        }
                    }
                    if(needUpdate) {
                        sfDeploymentMapper.updateById(deploy);
                    }
                }
            }
        } catch(Throwable t) {
            // 【核心修复】如果在解析或更新 DB 过程中发生异常，必须捕获并强制标记数据库为失败
            // 否则数据库会一直卡在 "Validating"，导致前端刷新后无限重连
            log.error("处理部署状态结果时发生系统异常", t);

            try {
                SfDeployment deploy = sfDeploymentMapper.selectOne(
                        new LambdaQueryWrapper<SfDeployment>().eq(SfDeployment::getLastAsyncId, processId)
                );
                if(deploy != null) {
                    deploy.setStatus("Failed");
                    deploy.setErrorMsg("系统处理部署结果时异常: " + t.getMessage());
                    sfDeploymentMapper.updateById(deploy);
                }
            } catch(Exception ex) {
                log.error("强制更新部署失败状态也失败了", ex);
            }

            // 重新构造一个失败的 JSON 返回给前端，确保前端也能收到结束信号
            JSONObject errorJson = new JSONObject();
            errorJson.put("done", true);
            errorJson.put("status", "Failed");
            errorJson.put("errorMessage", "系统处理异常: " + t.getMessage());
            return errorJson.toString();
        }

        return statusJson;
    }

    /**
     * 【修复】提取详细错误信息（组件错误、测试失败、覆盖率不足）
     */
    private String extractErrorMessage(JSONObject result) {
        String errorMessage = result.getString("errorMessage");

        JSONObject details = result.getJSONObject("details");
        if(details != null) {
            StringBuilder sb = new StringBuilder();
            boolean hasSpecificErrors = false;

            // 1. 元数据组件错误
            JSONArray failures = details.getJSONArray("componentFailures");
            if(failures != null && !failures.isEmpty()) {
                sb.append("【元数据校验失败】:\n");
                int count = Math.min(failures.size(), 20);
                for(int i = 0; i < count; i++) {
                    JSONObject fail = failures.getJSONObject(i);
                    sb.append(i + 1).append(". [").append(fail.getString("fileName")).append("]: ")
                            .append(fail.getString("problem")).append("\n");
                }
                if(failures.size() > 20) sb.append("... (共 ").append(failures.size()).append(" 个错误)\n");
                sb.append("\n");
                hasSpecificErrors = true;
            }

            // 2. 单元测试结果
            if(details.containsKey("runTestResult")) {
                JSONObject testResult = details.getJSONObject("runTestResult");

                // 2.1 测试用例执行失败
                JSONArray testFailures = testResult.getJSONArray("failures");
                if(testFailures != null && !testFailures.isEmpty()) {
                    sb.append("【单元测试失败】:\n");
                    int count = Math.min(testFailures.size(), 20);
                    for(int i = 0; i < count; i++) {
                        JSONObject fail = testFailures.getJSONObject(i);
                        sb.append(i + 1).append(". [").append(fail.getString("name"))
                                .append(".").append(fail.getString("methodName")).append("]: ")
                                .append(fail.getString("message")).append("\n");
                    }
                    if(testFailures.size() > 20) sb.append("... (共 ").append(testFailures.size()).append(" 个错误)\n");
                    sb.append("\n");
                    hasSpecificErrors = true;
                }

                // 2.2 【关键修复】代码覆盖率警告/错误
                JSONArray codeWarnings = testResult.getJSONArray("codeCoverageWarnings");
                if(codeWarnings != null && !codeWarnings.isEmpty()) {
                    sb.append("【代码覆盖率不足】:\n");
                    int count = Math.min(codeWarnings.size(), 20); // 稍微增加显示数量
                    for(int i = 0; i < count; i++) {
                        JSONObject warn = codeWarnings.getJSONObject(i);
                        String name = warn.getString("name");
                        String msg = warn.getString("message");

                        sb.append(i + 1).append(". ");

                        // 如果有具体的类名，显示在前面，例如: [MyClass] Test coverage of selected Apex ...
                        if(name != null && !name.equals("null") && !name.isEmpty()) {
                            sb.append("[").append(name).append("] ");
                        }

                        sb.append(msg).append("\n");
                    }
                    if(codeWarnings.size() > 20) {
                        sb.append("... (共 ").append(codeWarnings.size()).append(" 条警告)\n");
                    }
                    sb.append("\n");
                    hasSpecificErrors = true;
                }
            }

            // 如果提取到了具体错误，返回拼接后的详情
            if(hasSpecificErrors) {
                // 有时候顶层 errorMessage 包含总结性描述，建议拼在最前面
                if(errorMessage != null && !errorMessage.isEmpty()) {
                    return "【错误摘要】: " + errorMessage + "\n\n" + sb.toString();
                }
                return sb.toString();
            }
        }

        // 如果没有提取到任何 details 里的错误，直接返回顶层错误信息
        return errorMessage != null ? errorMessage : "部署验证失败，未返回具体原因。";
    }

    // ================== 差异比对逻辑 ==================

    @Override
    public void checkDiffStatus(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null || deployment.getTargetOrgId() == null) return;
        List<SfDeploymentItem> items = selectItems(deploymentId);
        if(items.isEmpty()) return;
        for(SfDeploymentItem item : items) {
            item.setDiffStatus("Comparing");
            sfDeploymentItemMapper.updateById(item);
        }
        CompletableFuture.runAsync(() -> {
            try {
                doCalculateDiff(deployment, items);
            } catch(Exception e) {
                log.error("比对失败", e);
                for(SfDeploymentItem item : items) {
                    item.setDiffStatus("Unknown");
                    sfDeploymentItemMapper.updateById(item);
                }
            }
        }, deployExecutor);
    }

    /**
     * 执行差异比对 (集成 Redis 指纹缓存)
     */
    private void doCalculateDiff(SfDeployment deployment, List<SfDeploymentItem> items) throws Exception {
        // 1. 先获取所有 Item 的元数据信息（主要是 LastModifiedDate），用于判断缓存是否命中
        // 注意：这里需要一个轻量级的 listMetadata 调用，或者如果列表页已经存了 LastModifiedDate，可以直接用数据库里的
        // 为了准确，建议批量查询一次 listMetadata 获取最新时间戳（略耗时但必要）或者假设数据库里存的是新的。
        // 此处简化：我们假设每次比对都实时去拉文件（最稳妥），但在拉取后计算哈希时做缓存。

        com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

        // 异步拉取 Source 和 Target 的 ZIP 包
        CompletableFuture<Map<String, String>> sourceFuture = CompletableFuture.supplyAsync(() ->
                retrieveAndHashMap(deployment.getSourceOrgId(), manifest)
        );
        CompletableFuture<Map<String, String>> targetFuture = CompletableFuture.supplyAsync(() ->
                retrieveAndHashMap(deployment.getTargetOrgId(), manifest)
        );

        CompletableFuture.allOf(sourceFuture, targetFuture).join();

        Map<String, String> sourceFileMap = sourceFuture.get();
        Map<String, String> targetFileMap = targetFuture.get();

        for(SfDeploymentItem item : items) {
            String sourceHash = getMetadataHash(sourceFileMap, item);

            String targetHash = getMetadataHash(targetFileMap, item);

            String status;
            if(sourceHash == null) status = "Invalid"; // 源环境没了
            else if(targetHash == null) status = "New"; // 目标环境没有
            else if(sourceHash.equals(targetHash)) status = "Same";
            else status = "Changed";

            item.setDiffStatus(status);
            item.setLastCheckTime(new Date());
            sfDeploymentItemMapper.updateById(item);
        }
    }

    /**
     * 【新增】为前端浏览器提供实时的精准物理哈希比对
     */
    @Override
    public Map<String, String> calculateTypeExactDiff(Long sourceOrgId, Long targetOrgId, String metadataType, List<String> memberNames) {
        Map<String, String> resultMap = new HashMap<>();
        if(sourceOrgId == null || targetOrgId == null || StringUtils.isEmpty(metadataType) || memberNames == null || memberNames.isEmpty()) {
            return resultMap;
        }

        try {
            // 1. 动态构造临时的 Package.xml (仅针对前端当前展示的列表)
            com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
            PackageTypeMembers typeMembers = new PackageTypeMembers();
            typeMembers.setName(metadataType);
            typeMembers.setMembers(memberNames.toArray(new String[0]));
            manifest.setTypes(new PackageTypeMembers[]{typeMembers});
            manifest.setVersion("58.0");

            // 2. 异步并发拉取源环境和目标环境的真实物理文件 (ZIP) 并流式计算 Hash
            CompletableFuture<Map<String, String>> sourceFuture = CompletableFuture.supplyAsync(() ->
                    retrieveAndHashMap(sourceOrgId, manifest)
            );
            CompletableFuture<Map<String, String>> targetFuture = CompletableFuture.supplyAsync(() ->
                    retrieveAndHashMap(targetOrgId, manifest)
            );

            // 等待双端拉取与哈希计算完成
            CompletableFuture.allOf(sourceFuture, targetFuture).join();

            Map<String, String> sourceFileMap = sourceFuture.get();
            Map<String, String> targetFileMap = targetFuture.get();

            // 3. 遍历比对真实 Hash，完全抛弃时间戳
            for(String name : memberNames) {
                // 借助一个临时的 Item 对象来复用已有的 getMetadataHash 算法 (支持 LWC, Folder 等复杂类型)
                SfDeploymentItem tempItem = new SfDeploymentItem();
                tempItem.setMetadataType(metadataType);
                tempItem.setMemberName(name);

                String sourceHash = getMetadataHash(sourceFileMap, tempItem);
                String targetHash = getMetadataHash(targetFileMap, tempItem);

                String status;
                if(sourceHash == null) {
                    status = "Invalid"; // 源环境被删
                } else if(targetHash == null) {
                    status = "New";     // SFoA 中不存在
                } else if(sourceHash.equals(targetHash)) {
                    status = "Same";    // Hash 完美一致
                } else {
                    status = "Changed"; // 物理内容确实发生了改变
                }

                resultMap.put(name, status);
            }
        } catch (Exception e) {
            log.error("精准差异比对异常", e);
            throw new RuntimeException("底层 Hash 提取失败: " + e.getMessage());
        }

        return resultMap;
    }

    /**
     * 【优化】获取元数据文件的哈希值
     * 修复 CustomLabel 和 MatchingRule 识别为 Invalid 的问题
     */
    private String getMetadataHash(Map<String, String> fileMap, SfDeploymentItem item) {
        String type = item.getMetadataType();
        String name = item.getMemberName();

        // 1. 【核心修复】CustomLabel 特殊处理
        // 原因：API 总是返回 labels/CustomLabels.labels，文件名不包含具体的 Label Name
        if("CustomLabel".equals(type)) {
            // 只要 ZIP 包里有 CustomLabels.labels 文件，就认为该 Label 存在
            // 注意：这里使用的是整个文件的哈希。如果包里有多个 Label，其中一个变了，所有 Label 都会显示 Changed。
            // 这是文件级比对的局限性，但比显示 Invalid 要好。
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith("labels/CustomLabels.labels")) {
                    return entry.getValue();
                }
            }
            // 如果没找到文件，说明目标环境里连 CustomLabels.labels 都没有，那就是 New/Invalid
            return null;
        }

        // 2. 处理对象子元素 (Field, ValidationRule, MatchingRule 等)
        if(isObjectChild(type)) {
            String parentName = name.contains(".") ? name.split("\\.")[0] : name;
            // 匹配 objects/Account.object
            String searchKey = "objects/" + parentName + ".object";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith(searchKey)) return entry.getValue();
            }
        }

        // 3. 处理工作流子元素 (WorkflowRule 等)
        if(isWorkflowChild(type)) {
            String parentName = name.contains(".") ? name.split("\\.")[0] : name;
            String searchKey = "workflows/" + parentName + ".workflow";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith(searchKey)) return entry.getValue();
            }
        }

        // 4. 处理 LWC/Aura Bundle (文件夹哈希)
        if(isBundleType(type)) {
            List<String> hashes = new ArrayList<>();
            String bundleFolder = "/" + name + "/";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().contains(bundleFolder)) hashes.add(entry.getValue());
            }
            if(!hashes.isEmpty()) {
                Collections.sort(hashes);
                return DigestUtils.md5Hex(String.join("", hashes));
            }
        }

        // 5. 通用匹配逻辑 (匹配文件名包含 MemberName 的文件)
        for(Map.Entry<String, String> entry : fileMap.entrySet()) {
            String fileName = entry.getKey();
            // 精确匹配文件名部分
            if(fileName.contains("/" + name + ".") || fileName.startsWith(name + ".")) {
                // 排除 -meta.xml 元数据描述文件 (通常我们比对的是代码主体)
                if(!fileName.endsWith("-meta.xml")) return entry.getValue();
            }
        }

        // 6. 模糊匹配兜底
        for(Map.Entry<String, String> entry : fileMap.entrySet()) {
            if(entry.getKey().contains(name)) return entry.getValue();
        }
        return null;
    }

    /**
     * 【优化】判断是否为 CustomObject 的子元素
     * 补充 MatchingRule
     */
    private boolean isObjectChild(String type) {
        return Arrays.asList(
                "CustomField", "WebLink", "ValidationRule", "RecordType", "ListView",
                "FieldSet", "CompactLayout", "BusinessProcess", "Index", "SharingReason",
                "MatchingRule" // 【新增】匹配规则也是存在于 .object 文件中的
        ).contains(type);
    }

    private boolean isWorkflowChild(String type) {
        return Arrays.asList("WorkflowRule", "WorkflowAlert", "WorkflowFieldUpdate", "WorkflowOutboundMessage", "WorkflowTask").contains(type);
    }

    private boolean isBundleType(String type) {
        return "LightningComponentBundle".equals(type) || "AuraDefinitionBundle".equals(type);
    }

    private Map<String, String> retrieveAndHashMap(Long orgId, com.sforce.soap.metadata.Package manifest) {
        Map<String, String> resultMap = new HashMap<>();
        try {
            byte[] zipData = sfMetadataService.retrieveZipByManifest(orgId, manifest);
            if(zipData == null) return resultMap;

            try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
                ZipEntry entry;
                while((entry = zis.getNextEntry()) != null) {
                    if(entry.isDirectory() || entry.getName().endsWith("package.xml")) continue;
                    String fileName = entry.getName();

                    // ========================= 【优化 2.3 流式哈希】 =========================
                    String smartHash;
                    // 如果不是文本文件（例如 StaticResource, Binary），直接流式计算 MD5
                    // 避免将大文件读入 ByteArrayOutputStream
                    if(!isTextFile(fileName)) {
                        // 使用 CloseShieldInputStream 防止 DigestUtils 关闭 zis
                        // 注意：需确保引入 org.apache.commons.io.input.CloseShieldInputStream 或自行实现
                        // 若没有 commons-io，可简单包装：
                        java.io.FilterInputStream shield = new java.io.FilterInputStream(zis) {
                            @Override
                            public void close() throws IOException {
                            }
                        };
                        smartHash = DigestUtils.md5Hex(shield);
                    } else {
                        // 文本文件需要读出来做语义哈希
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int len;
                        while((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                        byte[] fileBytes = bos.toByteArray();

                        if(fileName.endsWith(".page") || fileName.endsWith(".component")) {
                            smartHash = DigestUtils.md5Hex(fileBytes);
                        } else {
                            try {
                                smartHash = SfMetadataDiffUtils.computeSemanticHash(fileName, fileBytes);
                            } catch(Throwable e) {
                                smartHash = DigestUtils.md5Hex(fileBytes);
                            }
                        }
                    }
                    // =====================================================================

                    resultMap.put(fileName, smartHash);
                }
            }
        } catch(Exception e) {
            log.warn("Org {} 拉取比对文件失败: {}", orgId, e.getMessage());
        }
        return resultMap;
    }

    private com.sforce.soap.metadata.Package generateManifestObject(List<SfDeploymentItem> items) {
        com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
        Map<String, List<String>> typesMap = new HashMap<>();
        for(SfDeploymentItem item : items) {
            typesMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>()).add(item.getMemberName());
        }
        List<PackageTypeMembers> typeMembersList = new ArrayList<>();
        for(Map.Entry<String, List<String>> entry : typesMap.entrySet()) {
            PackageTypeMembers typeMembers = new PackageTypeMembers();
            typeMembers.setName(entry.getKey());
            typeMembers.setMembers(entry.getValue().toArray(new String[0]));
            typeMembersList.add(typeMembers);
        }
        manifest.setTypes(typeMembersList.toArray(new PackageTypeMembers[0]));
        manifest.setVersion("58.0");
        return manifest;
    }

    private void handleDeploymentError(Long id, String errorMsg) {
        SfDeployment update = new SfDeployment();
        update.setId(id);
        update.setStatus("Failed");
        if(errorMsg != null && errorMsg.length() > 500) {
            errorMsg = errorMsg.substring(0, 500) + "...";
        }
        update.setErrorMsg(errorMsg);
        sfDeploymentMapper.updateById(update);
    }

    @Override
    public void downloadPackage(Long deploymentId, HttpServletResponse response) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        List<SfDeploymentItem> items = selectItems(deploymentId);
        if(items.isEmpty()) throw new ServiceException("部署包为空，无法下载");
        try {
            com.sforce.soap.metadata.Package manifest = PackageXmlBuilder.build(items);
            byte[] zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);
            zipBytes = MetadataCleaner.clean(zipBytes, items);
            response.reset();
            response.setContentType("application/octet-stream");
            response.setCharacterEncoding("utf-8");
            String fileName = "deploy_pkg_" + deploymentId + "_" + System.currentTimeMillis() + ".zip";
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);
            response.getOutputStream().write(zipBytes);
        } catch(Exception e) {
            log.error("下载部署包失败", e);
            throw new ServiceException("生成下载文件失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> previewPackage(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");
        List<SfDeploymentItem> items = selectItems(deploymentId);
        if(items.isEmpty()) throw new ServiceException("部署包为空，请先添加元数据");
        com.sforce.soap.metadata.Package manifest = PackageXmlBuilder.build(items);
        byte[] zipBytes;
        try {
            zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);
        } catch(Exception e) {
            throw new ServiceException("生成预览包失败: " + e.getMessage());
        }
        if(zipBytes == null || zipBytes.length == 0) throw new ServiceException("源环境返回的部署包为空");

        zipBytes = MetadataCleaner.clean(zipBytes, items);

        List<String> fileList = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>();
        String packageXmlContent = "";
        try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if(!entry.isDirectory()) {
                    fileList.add(name);
                    if(isTextFile(name)) {
                        byte[] contentBytes = readStream(zis);
                        if(contentBytes.length < 1024 * 1024) {
                            String content = new String(contentBytes, StandardCharsets.UTF_8);
                            fileContents.put(name, content);
                            if(name.endsWith("package.xml")) packageXmlContent = content;
                        } else {
                            fileContents.put(name, "(文件过大，请下载查看)");
                        }
                    } else {
                        fileContents.put(name, "(二进制文件，不支持在线预览)");
                    }
                }
            }
        } catch(Exception e) {
            throw new ServiceException("解析部署包内容失败: " + e.getMessage());
        }
        Collections.sort(fileList);
        Map<String, Object> result = new HashMap<>();
        result.put("files", fileList);
        result.put("fileContents", fileContents);
        result.put("packageXml", packageXmlContent);
        result.put("size", zipBytes.length);
        return result;
    }

    private boolean isTextFile(String name) {
        if(StringUtils.isEmpty(name)) return false;
        String n = name.toLowerCase();

        // 1. 核心修复：添加 .flexipage
        // 2. 扩展支持：添加 .tab (CustomTab), .app (CustomApp), .quickAction, .remoteSite 等
        return n.endsWith(".xml")
                || n.endsWith(".cls")
                || n.endsWith(".trigger")
                || n.endsWith(".page")       // Visualforce Page
                || n.endsWith(".component")  // Visualforce Component
                || n.endsWith(".flexipage")  // 【修复点】Lightning Page
                || n.endsWith(".object")
                || n.endsWith(".field")
                || n.endsWith(".layout")
                || n.endsWith(".profile")
                || n.endsWith(".permissionset")
                || n.endsWith(".tab")        // 【建议补充】Custom Tab
                || n.endsWith(".app")        // 【建议补充】Custom App
                || n.endsWith(".quickaction")// 【建议补充】Quick Action
                || n.endsWith(".remotesite") // 【建议补充】Remote Site Setting
                || n.endsWith(".group")      // 【建议补充】Public Group
                || n.endsWith(".queue")      // 【建议补充】Queue
                || n.endsWith(".role")       // 【建议补充】Role
                || n.endsWith(".js")
                || n.endsWith(".css")
                || n.endsWith(".html")
                || n.endsWith(".txt")
                || n.endsWith(".json")
                || n.endsWith(".labels")
                || n.endsWith(".workflow")
                || n.endsWith(".flow")
                || n.endsWith(".svg");       // SVG 虽然是图片，但本质是 XML 文本，也可以预览
    }

    private byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        return out.toByteArray();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelDeploymentTask(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        String status = deployment.getStatus();

        // 定义哪些状态表示任务已经在 Salesforce 端运行了
        // 注意：Processing 不在这里面，Processing 被视为本地阶段
        List<String> remoteRunningStatus = Arrays.asList(
                "Validating", "Deploying", "Queued", "Pending", "InProgress"
        );

        // 1. 设置中间状态 "Canceling"
        // 这一步非常关键，它充当了“本地中断信号”，异步线程中的 checkInterrupted 会检测到这个状态变化
        deployment.setStatus("Canceling");
        sfDeploymentMapper.updateById(deployment);

        // 推送 UI 反馈
        DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Canceling", "正在执行取消操作..."));

        // 2. 分情况处理
        if(remoteRunningStatus.contains(status)) {
            // 【情况 A】：任务已在 Salesforce 运行，且 DB 里的 lastAsyncId 是当前任务的 ID
            if(StringUtils.isNotEmpty(deployment.getLastAsyncId())) {
                try {
                    log.info("触发远程取消，ID: {}", deployment.getLastAsyncId());
                    sfMetadataService.cancelDeploy(deployment.getTargetOrgId(), deployment.getLastAsyncId());
                } catch(Exception e) {
                    log.error("远程取消失败", e);
                    // 即使远程调用报错，我们依然维持 Canceling 状态，
                    // 让监控线程(startMonitoring)去最终确认任务是否真的停了，或者超时自动判定失败
                }
            }
        } else {
            // 【情况 B】：状态是 Processing (或者其他)，说明处于本地阶段
            // 此时 DB 里的 lastAsyncId 可能是上一次任务的旧 ID，绝对不能用来取消！
            log.info("任务处于本地准备阶段 ({})，仅执行本地中断，不调用 SF 接口", status);

            // 这里不需要做其他操作了，因为上面已经 setStatus("Canceling")
            // 异步线程 processAsyncDeployment 中的 checkInterrupted() 会捕获到这个变化并自动停止
        }
    }

    @Override
    public void executeRollback(Long originalHistoryId) {
        // 1. 查出原历史记录和部署包
        SfDeploymentHistory originalHistory = historyService.getById(originalHistoryId);
        if(originalHistory == null) throw new ServiceException("历史记录不存在");

        SfDeployment deployment = selectSfDeploymentById(originalHistory.getDeploymentId());

        // ========================= 【新增 2.2 预检机制】 =========================
        try {
            log.info("开始回滚预检: HistoryId={}", originalHistoryId);
            // 回滚必须保证目标环境可连接
            sfMetadataService.validateOrgConnection(deployment.getTargetOrgId());
        } catch(Exception e) {
            throw new ServiceException("回滚预检失败: 目标环境连接异常 (" + e.getMessage() + ")");
        }
        // =========================================================================

        // 2. 更新状态为 Deploying
        deployment.setStatus("Deploying");
        deployment.setErrorMsg("");
        // 【核心优化 3.1：乐观锁检查】
        int rows = sfDeploymentMapper.updateById(deployment);
        if(rows == 0) {
            throw new ServiceException("回滚启动失败：部署包状态已被变更，请刷新重试。");
        }

        // 3. 异步调用核心流程 (items 传 null，因为回滚包是基于历史生成的，不需要实时 items)
        CompletableFuture.runAsync(() -> {
            try {
                processAsyncDeploymentCore(deployment, null, false, originalHistoryId);
            } catch(Exception e) {
                // 兜底异常处理（虽然 Core 里面也有 try-catch，但这层是为了防止 invoke 本身出错）
                log.error("回滚启动失败", e);
                handleDeploymentError(deployment.getId(), "回滚启动异常: " + e.getMessage());
            }
        }, deployExecutor);
    }

    /**
     * 【新增 2.3】将字节数组写入临时文件，释放内存压力
     */
    private java.io.File saveTempFile(byte[] data, String prefix) {
        if(data == null || data.length == 0) return null;
        try {
            java.io.File tempFile = java.io.File.createTempFile(prefix + "_", ".zip");
            try(java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                fos.write(data);
            }
            return tempFile;
        } catch(IOException e) {
            log.error("创建临时文件失败", e);
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long cloneDeployment(Long originalId, SfDeployment newConfig) {
        // 1. 查询原主表信息
        SfDeployment original = sfDeploymentMapper.selectById(originalId);
        if(original == null) {
            throw new ServiceException("原部署包不存在");
        }

        // 2. 查询原明细列表
        List<SfDeploymentItem> originalItems = selectItems(originalId);
        if(originalItems == null || originalItems.isEmpty()) {
            throw new ServiceException("原部署包没有元数据，无法复制");
        }

        // 3. 构建新部署包对象
        SfDeployment newDeploy = new SfDeployment();

        // 【优化】主表也可以使用 copyProperties，然后重置关键字段
        BeanUtils.copyProperties(original, newDeploy);

        // 3.1 应用用户的新配置 & 重置主表关键字段
        newDeploy.setId(null); // 【重要】ID必须置空，让数据库自增
        newDeploy.setTitle(newConfig.getTitle());
        newDeploy.setSourceOrgId(newConfig.getSourceOrgId());
        newDeploy.setTargetOrgId(newConfig.getTargetOrgId());
        newDeploy.setDemandNo(newConfig.getDemandNo());
        newDeploy.setDemandPersonnel(newConfig.getDemandPersonnel());
        newDeploy.setDeployType(newConfig.getDeployType());

        newDeploy.setStatus("Draft");
        newDeploy.setCreateBy(SecurityUtils.getUsername());
        newDeploy.setCreateTime(new Date());
        newDeploy.setUpdateTime(new Date());
        newDeploy.setVersion(0L); // 重置乐观锁
        newDeploy.setErrorMsg("");
        newDeploy.setLastAsyncId(null);
        // 【新增】克隆出来的新数据，归属权归当前操作人
        newDeploy.setUserId(SecurityUtils.getUserId());
        newDeploy.setDeptId(SecurityUtils.getDeptId());
        newDeploy.setTenantId(SecurityUtils.getLoginUser().getTenantId());
        // 4. 插入主表
        sfDeploymentMapper.insert(newDeploy);
        Long newDeploymentId = newDeploy.getId();

        // 5. 构建明细项列表
        List<SfDeploymentItem> newItems = new ArrayList<>(originalItems.size());

        for(SfDeploymentItem originalItem : originalItems) {
            SfDeploymentItem newItem = new SfDeploymentItem();

            // 【核心优化 1】使用 BeanUtils 进行全量属性复制
            // 这样以后如果 SfDeploymentItem 加了新字段，这里会自动带过去，不用改代码
            BeanUtils.copyProperties(originalItem, newItem);

            // 【核心优化 2】手动重置/覆盖那些“不应该被复制”或“需要变更”的字段
            newItem.setId(null); // 【关键】置空ID，否则会主键冲突或更新原数据
            newItem.setDeploymentId(newDeploymentId); // 关联到新主表
            newItem.setCreateTime(new Date());

            // 重置状态信息
            newItem.setAction("Add");
            newItem.setDiffStatus("Unknown");
//            newItem.setLastModifiedByName("");
//            newItem.setLastModifiedDate(null);

            newItems.add(newItem);
        }

        if(!newItems.isEmpty()) {
            sfDeploymentItemService.saveBatch(newItems);
        }

        return newDeploymentId;
    }

    /**
     * 发送实时部署步骤日志到前端 WebSocket
     *
     * @param deploymentId 部署包ID
     * @param status       当前大状态 (Processing, Deploying...)
     * @param message      详细日志内容
     */
    private void sendStepLog(Long deploymentId, String status, String message) {
        // 1. 发送 WebSocket 消息
        String jsonMsg = buildProgressJson(status, message);
        DeployWebSocketServer.sendMessage(deploymentId, jsonMsg);

        // 2. 同时打印到后端控制台，方便运维排查
        log.info("[Deploy-{}] {}", deploymentId, message);
    }
}
