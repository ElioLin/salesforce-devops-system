package com.ruoyi.salesforce.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.exception.ServiceException;
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
        if (deployment == null) throw new ServiceException("部署包不存在");

        // 1. 填充元数据信息 (修改人/时间)
        populateMetadataInfo(deployment.getSourceOrgId(), items);

        // 2. 入库
        for (SfDeploymentItem item : items) {
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
            ((SfMetadataServiceImpl) sfMetadataService).preloadMetadata(deployment.getSourceOrgId(), items);
        } catch (Exception e) {
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
        if (orgId == null || items == null || items.isEmpty()) return;

        try {
            Map<String, List<SfDeploymentItem>> typeMap = new HashMap<>();
            for (SfDeploymentItem item : items) {
                typeMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>()).add(item);
            }

            for (Map.Entry<String, List<SfDeploymentItem>> entry : typeMap.entrySet()) {
                String type = entry.getKey();
                List<SfDeploymentItem> currentTypeItems = entry.getValue();

                List<FileProperties> remoteList = sfMetadataService.listMetadata(orgId, type);

                Map<String, FileProperties> remoteMap = new HashMap<>();
                if (remoteList != null) {
                    for (FileProperties fp : remoteList) {
                        remoteMap.put(fp.getFullName(), fp);
                    }
                }

                for (SfDeploymentItem item : currentTypeItems) {
                    FileProperties match = remoteMap.get(item.getMemberName());
                    if (match != null) {
                        item.setLastModifiedByName(match.getLastModifiedByName());

                        // 【修复 1】日期 1970 问题修复
                        // 判断是否为有效日期（例如大于 2000-01-01），过滤掉 null 或 1970 默认值
                        // 946684800000L = 2000-01-01 00:00:00
                        if (match.getLastModifiedDate() != null && match.getLastModifiedDate().getTimeInMillis() > 946684800000L) {
                            item.setLastModifiedDate(match.getLastModifiedDate().getTime());
                        } else {
                            item.setLastModifiedDate(null); // 显式置空
                        }
                    }
                }
            }
        } catch (Exception e) {
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
        try {
            // 推送 WS 消息：开始准备
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在提取代码..."));

            com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

            log.info("开始提取代码，Org: {}", deployment.getSourceOrgId());
            byte[] zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);

            if(zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("提取代码失败：返回的ZIP包为空");
            }

            // 清洗元数据
            log.info("开始清洗元数据...");
            DeployWebSocketServer.sendMessage(deployment.getId(), buildProgressJson("Processing", "正在清洗元数据..."));
            zipBytes = MetadataCleaner.clean(zipBytes, items);
            log.info("元数据清洗完成，ZIP大小: {} bytes", zipBytes.length);

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

            SfDeployment update = new SfDeployment();
            update.setId(deployment.getId());
            update.setStatus(checkOnly ? "Validating" : "Deploying");
            update.setLastAsyncId(deployAsync.getId());
            sfDeploymentMapper.updateById(update);

            log.info("部署请求已提交，AsyncId: {}", deployAsync.getId());

            // 【关键】启动后台监控线程，轮询 SF 状态并推送 WS
            startMonitoring(deployment.getId(), deployment.getTargetOrgId(), deployAsync.getId());

        } catch(Exception e) {
            log.error("部署流程处理失败", e);
            handleDeploymentError(deployment.getId(), "流程异常: " + e.getMessage());
            DeployWebSocketServer.sendMessage(deployment.getId(), buildErrorJson(e.getMessage()));
        }
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
        // 调用 Metadata Service 获取安全 JSON
        String statusJson = sfMetadataService.checkDeployStatus(targetOrgId, processId);
        JSONObject result = JSONObject.parseObject(statusJson);
        String status = result.getString("status");
        boolean isCheckOnly = result.getBooleanValue("checkOnly");

        String finalStatus = null;
        String errorMessage = null;

        if("Succeeded".equals(status)) {
            // 【关键修改】区分验证成功和部署成功
            if(isCheckOnly) {
                finalStatus = "Validated"; // 验证成功 -> Validated
            } else {
                finalStatus = "Succeeded"; // 部署成功 -> Succeeded
                try {
                    sfMetadataService.clearCacheForOrg(targetOrgId);
                } catch(Exception e) {
                    log.warn("自动清理缓存失败: {}", e.getMessage());
                }
            }
        } else if("Failed".equals(status) || "Canceled".equals(status)) {
            finalStatus = "Failed";
            // 错误信息已包含在 statusJson 中，无需重新提取，但为了存库需要解析出来
            errorMessage = result.getString("errorMessage");
            // 如果 JSON 里没提取到顶层 errorMsg，尝试构建简要信息
            if(errorMessage == null && "Failed".equals(status)) {
                errorMessage = "部署验证失败，请查看详情。";
            }
        }

        // 更新数据库
        if(finalStatus != null) {
            SfDeployment deploy = sfDeploymentMapper.selectOne(
                    new LambdaQueryWrapper<SfDeployment>().eq(SfDeployment::getLastAsyncId, processId)
            );
            if(deploy != null) {
                boolean needUpdate = false;
                if(!finalStatus.equals(deploy.getStatus())) {
                    deploy.setStatus(finalStatus);
                    needUpdate = true;
                }
                if(errorMessage != null) {
                    if(errorMessage.length() > 9900) {
                        errorMessage = errorMessage.substring(0, 9900) + "\n...(错误信息过长已截断)";
                    }
                    // 仅当错误信息不同时更新
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
        return statusJson;
    }

    // 提取错误信息逻辑 (抽取为独立方法)
    private String extractErrorMessage(JSONObject result) {
        String errorMessage = result.getString("errorMessage");
        if(errorMessage == null) {
            JSONObject details = result.getJSONObject("details");
            if(details != null) {
                StringBuilder sb = new StringBuilder();
                boolean hasErrors = false;

                // 元数据错误
                JSONArray failures = details.getJSONArray("componentFailures");
                if(failures != null && !failures.isEmpty()) {
                    sb.append("【元数据校验失败】:\n");
                    int count = Math.min(failures.size(), 50);
                    for(int i = 0; i < count; i++) {
                        JSONObject fail = failures.getJSONObject(i);
                        sb.append(i + 1).append(". [").append(fail.getString("fileName")).append("]: ")
                                .append(fail.getString("problem")).append("\n");
                    }
                    if(failures.size() > 50) sb.append("... (更多错误未显示)\n");
                    sb.append("\n");
                    hasErrors = true;
                }

                // 单元测试错误
                if(details.containsKey("runTestResult")) {
                    JSONObject testResult = details.getJSONObject("runTestResult");
                    JSONArray testFailures = testResult.getJSONArray("failures");
                    if(testFailures != null && !testFailures.isEmpty()) {
                        sb.append("【单元测试失败】:\n");
                        int count = Math.min(testFailures.size(), 50);
                        for(int i = 0; i < count; i++) {
                            JSONObject fail = testFailures.getJSONObject(i);
                            sb.append(i + 1).append(". [").append(fail.getString("name"))
                                    .append(".").append(fail.getString("methodName")).append("]: ")
                                    .append(fail.getString("message")).append("\n");
                        }
                        if(testFailures.size() > 50) sb.append("... (更多错误未显示)\n");
                        hasErrors = true;
                    }

                    // 覆盖率警告
                    JSONArray codeWarnings = testResult.getJSONArray("codeCoverageWarnings");
                    if(codeWarnings != null && !codeWarnings.isEmpty()) {
                        sb.append("【覆盖率警告】:\n");
                        int count = Math.min(codeWarnings.size(), 50);
                        for(int i = 0; i < count; i++) {
                            JSONObject warn = codeWarnings.getJSONObject(i);
                            sb.append(i + 1).append(". ").append(warn.getString("message")).append("\n");
                        }
                        hasErrors = true;
                    }
                }

                if(hasErrors) errorMessage = sb.toString();
            }
        }
        return errorMessage != null ? errorMessage : "部署失败，但未返回具体错误信息。";
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
            // 1. 下载 ZIP (这是最耗时的，如果想优化这里，必须结合 listMetadata 预检查 + Redis)
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

                    // 【核心修改】调用增强版的 DiffUtils (含去噪逻辑)
                    String smartHash = SfMetadataDiffUtils.computeSemanticHash(entry.getName(), fileBytes);

                    resultMap.put(entry.getName(), smartHash);

                    // 【可选】在这里可以将 Hash 写入 Redis，供未来优化使用
                    // String redisKey = "sf:hash:" + orgId + ":" + entry.getName();
                    // redisCache.setCacheObject(redisKey, smartHash);
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
}