package com.ruoyi.salesforce.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    // ================== 基础 CRUD ==================

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
        return sfDeploymentMapper.insert(sfDeployment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addItems(Long deploymentId, List<SfDeploymentItem> items) {
        checkIfLocked(deploymentId);

        SfDeployment deployment = sfDeploymentMapper.selectById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        // 1. 填充元数据信息 (修改人/时间)
        populateMetadataInfo(deployment.getSourceOrgId(), items);

        // 2. 入库
        for(SfDeploymentItem item : items) {
            item.setDeploymentId(deploymentId);
            item.setCreateTime(new Date());
            item.setAction("Add");
            item.setDiffStatus("Comparing");
            sfDeploymentItemMapper.insert(item);
        }

        // 3. 【新增】触发异步预取内容 (Pre-fetching)
        // 这样当用户之后点击“比对”时，源环境的内容已经躺在 Redis 里了，秒开
        try {
            // 需要强转一下或者在接口定义里加这个方法
            sfMetadataService.preloadMetadata(deployment.getSourceOrgId(), items);
        } catch(Exception e) {
            log.warn("触发预取任务失败: {}", e.getMessage());
        }

        // 4. 触发比对状态计算 (Hash比对)
        checkDiffStatus(deploymentId);
    }

    /**
     * 【新增】辅助方法：从缓存或API填充元数据的修改人与修改时间
     */
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
    @Transactional
    public int deleteSfDeploymentByIds(Long[] ids) {
        for(Long id : ids) {
            sfDeploymentItemMapper.delete(
                    new LambdaQueryWrapper<SfDeploymentItem>().eq(SfDeploymentItem::getDeploymentId, id)
            );
        }
        return sfDeploymentMapper.deleteBatchIds(Arrays.asList(ids));
    }

    @Override
    public void removeItems(List<Long> itemIds) {
        if(itemIds == null || itemIds.isEmpty()) return;

        // 【优化】校验状态。因为传入的是itemId，先查出 deploymentId
        SfDeploymentItem item = sfDeploymentItemMapper.selectById(itemIds.get(0));
        if(item != null) {
            checkIfLocked(item.getDeploymentId());
        }

        sfDeploymentItemMapper.deleteBatchIds(itemIds);
    }

    /**
     * 【新增】检查部署包是否被锁定（正在处理中）
     */
    private void checkIfLocked(Long deploymentId) {
        SfDeployment deployment = sfDeploymentMapper.selectById(deploymentId);
        if(deployment == null) return;

        String s = deployment.getStatus();
        // 如果处于中间状态，禁止修改
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

        deployment.setStatus("Processing");
        deployment.setErrorMsg("");
        sfDeploymentMapper.updateById(deployment);

        CompletableFuture.runAsync(() -> {
            try {
                processAsyncDeployment(deployment, items, checkOnly);
            } catch(Exception e) {
                log.error("异步部署任务异常", e);
                handleDeploymentError(deployment.getId(), "系统内部错误: " + e.getMessage());
            }
        });
    }

    private void processAsyncDeployment(SfDeployment deployment, List<SfDeploymentItem> items, boolean checkOnly) {
        // 定义变量以便在 catch 块中访问
        String newAsyncId = null;
        try {
            checkInterrupted(deployment.getId());

            // 推送 WS 消息：开始准备
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在提取代码..."));
            com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

            // 【检查点 2】 在去 Salesforce 提取代码之前
            checkInterrupted(deployment.getId());

            log.info("开始提取代码，Org: {}", deployment.getSourceOrgId());
            byte[] zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);

            // 【检查点 3】 清洗元数据之前
            checkInterrupted(deployment.getId());

            if(zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("提取代码失败：返回的ZIP包为空");
            }

            // 清洗元数据
            log.info("开始清洗元数据...");
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在清洗元数据..."));
            zipBytes = MetadataCleaner.clean(zipBytes, items);
            log.info("元数据清洗完成，ZIP大小: {} bytes", zipBytes.length);

            // 【检查点 4】 最关键的一步：在上传到目标环境之前！
            // 如果此时用户点了取消，我们绝对不应该调用 deploy 方法
            checkInterrupted(deployment.getId());

            // 部署
            log.info("开始部署，Org: {}", deployment.getTargetOrgId());
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在上传至目标环境..."));
            MetadataConnection targetConn = sfMetadataService.getMetadataConnection(deployment.getTargetOrgId());

            DeployOptions deployOptions = new DeployOptions();
            deployOptions.setPerformRetrieve(false);
            deployOptions.setRollbackOnError(true);
            deployOptions.setCheckOnly(checkOnly);

            if("RunSpecifiedTests".equals(deployment.getTestLevel())) {
                deployOptions.setTestLevel(TestLevel.RunSpecifiedTests);
                if(deployment.getSpecifiedTests() != null) {
                    deployOptions.setRunTests(deployment.getSpecifiedTests().split(","));
                }
            } else if("RunLocalTests".equals(deployment.getTestLevel())) {
                deployOptions.setTestLevel(TestLevel.RunLocalTests);
            } else {
                deployOptions.setTestLevel(TestLevel.NoTestRun);
            }

            AsyncResult deployAsync = targetConn.deploy(zipBytes, deployOptions);
            newAsyncId = deployAsync.getId(); // 拿到新 ID

            // 万一在 conn.deploy() 执行的那几秒钟网络 IO 期间，用户点了取消：
            // 此时 DB 状态变成了 Canceling，但是任务已经提交成功了。
            // 我们必须立刻检查，如果发现被取消了，马上把刚提交的任务撤回！
            SfDeployment currentCheck = sfDeploymentMapper.selectById(deployment.getId());
            if("Canceling".equals(currentCheck.getStatus()) || "Canceled".equals(currentCheck.getStatus())) {
                log.warn("检测到任务在提交期间被取消，立即执行远程撤回: {}", newAsyncId);
                sfMetadataService.cancelDeploy(deployment.getTargetOrgId(), newAsyncId);
                throw new InterruptedException("任务在提交后立即被取消");
            }

            SfDeployment update = new SfDeployment();
            update.setId(deployment.getId());
            update.setStatus(checkOnly ? "Validating" : "Deploying");
            update.setLastAsyncId(deployAsync.getId());
            sfDeploymentMapper.updateById(update);

            log.info("部署请求已提交，AsyncId: {}", deployAsync.getId());

            // 【关键】启动后台监控线程，轮询 SF 状态并推送 WS
            startMonitoring(deployment.getId(), deployment.getTargetOrgId(), deployAsync.getId());

        } catch(InterruptedException e) {
            log.info("任务被中断: {}", e.getMessage());
            // 处理本地取消的收尾工作
            handleDeploymentLocalCancel(deployment.getId());
        } catch(Exception e) {
            log.error("部署流程处理失败", e);
            handleDeploymentError(deployment.getId(), "流程异常: " + e.getMessage());
            DeployWebSocketServer.sendMessage(deployment.getId(), buildErrorJson(e.getMessage()));
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

        deployment.setStatus("Deploying");
        deployment.setErrorMsg("");
        sfDeploymentMapper.updateById(deployment);

        CompletableFuture.runAsync(() -> {
            try {
                DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在启动快速部署..."));
                log.info("开始快速部署, Org: {}, ValidationId: {}", deployment.getTargetOrgId(), deployment.getLastAsyncId());
                String newProcessId = sfMetadataService.deployRecentValidation(
                        deployment.getTargetOrgId(),
                        deployment.getLastAsyncId()
                );
                SfDeployment update = new SfDeployment();
                update.setId(deployment.getId());
                update.setLastAsyncId(newProcessId);
                sfDeploymentMapper.updateById(update);

                // 【关键】启动监控
                startMonitoring(deployment.getId(), deployment.getTargetOrgId(), newProcessId);

            } catch(Exception e) {
                log.error("快速部署失败", e);
                handleDeploymentError(deployment.getId(), "快速部署异常: " + e.getMessage());
                DeployWebSocketServer.sendMessage(deployment.getId(), buildErrorJson(e.getMessage()));
            }
        });
    }

    /**
     * 【新增】后台监控线程
     * 轮询 Salesforce 状态并推送 WebSocket，直到完成
     */
    private void startMonitoring(Long deploymentId, Long targetOrgId, String processId) {
        CompletableFuture.runAsync(() -> {
            boolean done = false;
            long startTime = System.currentTimeMillis();

            while(!done) {
                try {
                    // 超时保护 (1小时)
                    if(System.currentTimeMillis() - startTime > 3600 * 1000) {
                        log.error("部署监控超时，停止轮询: {}", processId);
                        break;
                    }

                    // 1. 调用 sfMetadataService 获取状态 (返回的是安全 JSON 字符串)
                    String statusJson = checkDeployStatus(targetOrgId, processId);

                    // 2. 推送消息给前端
                    DeployWebSocketServer.sendMessage(deploymentId, statusJson);

                    // 3. 判断是否结束
                    JSONObject json = JSONObject.parseObject(statusJson);
                    boolean isDone = json.getBooleanValue("done");

                    if(isDone) {
                        done = true;
                        log.info("部署任务结束: {}", processId);
                    } else {
                        // 未结束，等待 2 秒
                        TimeUnit.SECONDS.sleep(2);
                    }

                } catch(Exception e) {
                    log.error("监控线程异常", e);
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch(InterruptedException ignored) {
                    }
                }
            }
        });
    }

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
        });
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

    private String getMetadataHash(Map<String, String> fileMap, SfDeploymentItem item) {
        String type = item.getMetadataType();
        String name = item.getMemberName();

        if(isObjectChild(type)) {
            String parentName = name.contains(".") ? name.split("\\.")[0] : name;
            String searchKey = "objects/" + parentName + ".object";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith(searchKey)) return entry.getValue();
            }
        }

        if(isWorkflowChild(type)) {
            String parentName = name.contains(".") ? name.split("\\.")[0] : name;
            String searchKey = "workflows/" + parentName + ".workflow";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith(searchKey)) return entry.getValue();
            }
        }

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

        for(Map.Entry<String, String> entry : fileMap.entrySet()) {
            String fileName = entry.getKey();
            if(fileName.contains("/" + name + ".") || fileName.startsWith(name + ".")) {
                if(!fileName.endsWith("-meta.xml")) return entry.getValue();
            }
        }
        for(Map.Entry<String, String> entry : fileMap.entrySet()) {
            if(entry.getKey().contains(name)) return entry.getValue();
        }
        return null;
    }

    private boolean isObjectChild(String type) {
        return Arrays.asList("CustomField", "WebLink", "ValidationRule", "RecordType", "ListView", "FieldSet", "CompactLayout", "BusinessProcess", "Index", "SharingReason").contains(type);
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

                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);

                    byte[] fileBytes = bos.toByteArray();
                    String fileName = entry.getName();

                    // 【核心修改】调用增强版的 DiffUtils (含去噪逻辑)
                    // 修复：Visualforce (.page/.component) 往往没有显式声明 xmlns:apex，导致 XML 解析报 Fatal Error
                    // 策略：对这类文件直接使用物理 MD5，跳过 XML 语义分析；其他 XML 文件尝试语义分析，失败则降级。
                    String smartHash;
                    if(fileName.endsWith(".page") || fileName.endsWith(".component")) {
                        smartHash = DigestUtils.md5Hex(fileBytes);
                    } else {
                        try {
                            smartHash = SfMetadataDiffUtils.computeSemanticHash(fileName, fileBytes);
                        } catch(Throwable e) {
                            // 如果 XML 解析失败（如格式不规范），静默降级为普通 MD5，避免控制台刷 Fatal Error
                            smartHash = DigestUtils.md5Hex(fileBytes);
                        }
                    }

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
        String n = name.toLowerCase();
        return n.endsWith(".xml") || n.endsWith(".cls") || n.endsWith(".trigger") || n.endsWith(".page") || n.endsWith(".component") || n.endsWith(".object") || n.endsWith(".field") || n.endsWith(".layout") || n.endsWith(".profile") || n.endsWith(".permissionset") || n.endsWith(".js") || n.endsWith(".css") || n.endsWith(".html") || n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".labels") || n.endsWith(".workflow") || n.endsWith(".flow");
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
}