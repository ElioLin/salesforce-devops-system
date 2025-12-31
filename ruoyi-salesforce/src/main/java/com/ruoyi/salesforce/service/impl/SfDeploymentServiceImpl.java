package com.ruoyi.salesforce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfDeploymentItem;
import com.ruoyi.salesforce.mapper.SfDeploymentItemMapper;
import com.ruoyi.salesforce.mapper.SfDeploymentMapper;
import com.ruoyi.salesforce.service.ISfDeploymentService;
import com.ruoyi.salesforce.service.ISfMetadataService;
import com.ruoyi.salesforce.utils.PackageXmlBuilder;
import com.ruoyi.salesforce.utils.MetadataCleaner;
import com.sforce.soap.metadata.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;

import javax.servlet.http.HttpServletResponse;

@Service
public class SfDeploymentServiceImpl extends ServiceImpl<SfDeploymentMapper, SfDeployment> implements ISfDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(SfDeploymentServiceImpl.class);

    @Autowired
    private SfDeploymentMapper sfDeploymentMapper;

    @Autowired
    private SfDeploymentItemMapper sfDeploymentItemMapper;

    @Autowired
    private ISfMetadataService sfMetadataService;

    // ================== 基础 CRUD (保持不变) ==================

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

    /**
     * 【优化】添加元数据后，自动触发差异计算
     */
    @Override
    @Transactional
    public void addItems(Long deploymentId, List<SfDeploymentItem> items) {
        for(SfDeploymentItem item : items) {
            item.setDeploymentId(deploymentId);
            item.setCreateTime(new Date());
            item.setAction("Add");
            item.setDiffStatus("Comparing"); // 初始状态设为正在计算
            sfDeploymentItemMapper.insert(item);
        }

        // 【新增】添加完成后，立即异步触发差异计算
        checkDiffStatus(deploymentId);
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
        sfDeploymentItemMapper.deleteBatchIds(itemIds);
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

        // 更新状态
        deployment.setStatus("Processing");
        deployment.setErrorMsg("");
        sfDeploymentMapper.updateById(deployment);

        // 异步执行
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
            // 1. 构建清单对象
            com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

            // 2. 从源环境拉取 ZIP
            log.info("开始提取代码，Org: {}", deployment.getSourceOrgId());
            byte[] zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);

            if(zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("提取代码失败：返回的ZIP包为空");
            }
            log.info("提取成功，ZIP大小: {} bytes", zipBytes.length);

            // ========================================================
            // 【核心优化】调用 MetadataCleaner 进行深度清洗
            // 涵盖 Profile, PermissionSet 和 RecordType
            // ========================================================
            log.info("开始清洗元数据 (Profile/PermSet/RecordType)...");

            // 新的 MetadataCleaner，并传入 items
            zipBytes = MetadataCleaner.clean(zipBytes, items);

            log.info("元数据清洗完成，准备部署，新ZIP大小: {} bytes", zipBytes.length);
            // ========================================================

            // 3. 部署到目标环境
            log.info("开始部署，Org: {}", deployment.getTargetOrgId());
            MetadataConnection targetConn = sfMetadataService.getMetadataConnection(deployment.getTargetOrgId());

            DeployOptions deployOptions = new DeployOptions();
            deployOptions.setPerformRetrieve(false);
            deployOptions.setRollbackOnError(true);
            deployOptions.setCheckOnly(checkOnly);

            // 测试级别配置
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

            // 4. 更新数据库状态
            SfDeployment update = new SfDeployment();
            update.setId(deployment.getId());
            update.setStatus(checkOnly ? "Validating" : "Deploying");
            update.setLastAsyncId(deployAsync.getId());
            sfDeploymentMapper.updateById(update);

            log.info("部署请求已提交，AsyncId: {}", deployAsync.getId());

        } catch(Exception e) {
            log.error("部署流程处理失败", e);
            handleDeploymentError(deployment.getId(), "流程异常: " + e.getMessage());
        }
    }

    @Override
    public void quickDeploy(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        if(!"Succeeded".equals(deployment.getStatus()) || deployment.getLastAsyncId() == null) {
            throw new ServiceException("只有【验证成功】的部署包才能使用快速部署");
        }

        deployment.setStatus("Deploying");
        deployment.setErrorMsg("");
        sfDeploymentMapper.updateById(deployment);

        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始快速部署, Org: {}, ValidationId: {}", deployment.getTargetOrgId(), deployment.getLastAsyncId());
                String newProcessId = sfMetadataService.deployRecentValidation(
                        deployment.getTargetOrgId(),
                        deployment.getLastAsyncId()
                );
                SfDeployment update = new SfDeployment();
                update.setId(deployment.getId());
                update.setLastAsyncId(newProcessId);
                sfDeploymentMapper.updateById(update);
            } catch(Exception e) {
                log.error("快速部署失败", e);
                handleDeploymentError(deployment.getId(), "快速部署异常: " + e.getMessage());
            }
        });
    }

    @Override
    public String checkDeployStatus(Long targetOrgId, String processId) throws Exception {
        // 1. 获取 JSON 状态
        String statusJson = sfMetadataService.checkDeployStatus(targetOrgId, processId);

        // 2. 解析 JSON
        JSONObject result = JSONObject.parseObject(statusJson);
        String status = result.getString("status");

        String finalStatus = null;
        String errorMessage = null;

        if("Succeeded".equals(status)) {
            finalStatus = "Succeeded";
        } else if("Failed".equals(status) || "Canceled".equals(status)) {
            finalStatus = "Failed";

            // ================= 错误信息提取优化 (开始) =================
            // 1. 优先获取顶层错误信息
            errorMessage = result.getString("errorMessage");

            // 2. 如果没有顶层错误，深入 details 查找具体的元数据或测试错误
            if(errorMessage == null) {
                JSONObject details = result.getJSONObject("details");
                if(details != null) {
                    StringBuilder sb = new StringBuilder();
                    boolean hasErrors = false;

                    // --- A. 处理组件/元数据部署失败 (componentFailures) ---
                    JSONArray failures = details.getJSONArray("componentFailures");
                    if(failures != null && !failures.isEmpty()) {
                        sb.append("【元数据校验失败】:\n");

                        // 【优化】数量限制提升至 50 条
                        int count = Math.min(failures.size(), 50);
                        for(int i = 0; i < count; i++) {
                            JSONObject fail = failures.getJSONObject(i);
                            String fileName = fail.getString("fileName");
                            String problem = fail.getString("problem");
                            String lineNumber = fail.getString("lineNumber");

                            // 格式: 1. [classes/MyClass.cls] (Line:10): 变量未定义...
                            sb.append(i + 1).append(". [").append(fileName).append("]");
                            if(lineNumber != null) {
                                sb.append(" (Line:").append(lineNumber).append(")");
                            }
                            sb.append(": ").append(problem).append("\n");
                        }
                        if(failures.size() > 50) {
                            sb.append("... (还有 ").append(failures.size() - 50).append(" 个元数据错误未显示)\n");
                        }
                        sb.append("\n"); // 分类之间空一行
                        hasErrors = true;
                    }

                    // --- B. 处理单元测试运行失败 (runTestResult) ---
                    if(details.containsKey("runTestResult")) {
                        JSONObject testResult = details.getJSONObject("runTestResult");

                        // B1. 测试断言失败 (failures)
                        JSONArray testFailures = testResult.getJSONArray("failures");
                        if(testFailures != null && !testFailures.isEmpty()) {
                            sb.append("【单元测试失败】:\n");

                            // 【优化】数量限制提升至 50 条
                            int count = Math.min(testFailures.size(), 50);
                            for(int i = 0; i < count; i++) {
                                JSONObject fail = testFailures.getJSONObject(i);
                                String className = fail.getString("name");
                                String methodName = fail.getString("methodName");
                                String message = fail.getString("message");

                                // 格式: 1. [MyTestClass.testMethod]: Expected: 10, Actual: 0
                                sb.append(i + 1).append(". [").append(className).append(".").append(methodName).append("]: ")
                                        .append(message).append("\n");
                            }
                            if(testFailures.size() > 50) {
                                sb.append("... (还有 ").append(testFailures.size() - 50).append(" 个测试失败未显示)\n");
                            }
                            sb.append("\n");
                            hasErrors = true;
                        }

                        // B2. 代码覆盖率警告 (重点优化部分)
                        JSONArray codeWarnings = testResult.getJSONArray("codeCoverageWarnings");
                        if(codeWarnings != null && !codeWarnings.isEmpty()) {
                            sb.append("【代码覆盖率警告】:\n");
                            // 覆盖率警告通常比较重要，建议多显示一些
                            int count = Math.min(codeWarnings.size(), 50);
                            for(int i = 0; i < count; i++) {
                                JSONObject warn = codeWarnings.getJSONObject(i);
                                String name = warn.getString("name"); // 获取具体的类名
                                String msg = warn.getString("message"); // 获取具体信息

                                sb.append(i + 1).append(". ");
                                // 【新增】如果有具体的类名，将其拼接到错误信息前
                                if(name != null && !name.isEmpty() && !"null".equals(name)) {
                                    sb.append("Class [").append(name).append("]: ");
                                }
                                sb.append(msg).append("\n");
                            }
                            if(codeWarnings.size() > 50) {
                                sb.append("... (还有 ").append(codeWarnings.size() - 50).append(" 个覆盖率警告未显示)\n");
                            }
                            hasErrors = true;
                        }
                    }

                    if(hasErrors) {
                        errorMessage = sb.toString();
                    }
                }
            }

            if(errorMessage == null) {
                errorMessage = "部署失败 (状态: Failed)，但未返回具体的错误详情。请前往 Salesforce 部署状态页面查看。";
            }
            // ================= 错误信息提取优化 (结束) =================
        }

        // 3. 更新数据库
        if(finalStatus != null) {
            SfDeployment deploy = sfDeploymentMapper.selectOne(
                    new LambdaQueryWrapper<SfDeployment>().eq(SfDeployment::getLastAsyncId, processId)
            );
            if(deploy != null) {
                // 只有状态变化，或者有错误信息需要更新时才执行 update
                if(!finalStatus.equals(deploy.getStatus()) || errorMessage != null) {
                    deploy.setStatus(finalStatus);
                    if(errorMessage != null) {
                        // 【优化】数据库字段已扩容到 10000，这里截断阈值设为 9900 (预留缓冲)
                        if(errorMessage.length() > 9900) {
                            errorMessage = errorMessage.substring(0, 9900) + "\n...(错误信息过长已截断)";
                        }
                        deploy.setErrorMsg(errorMessage);
                    }
                    sfDeploymentMapper.updateById(deploy);
                }
            }
        }
        return statusJson;
    }

    // ================== 差异比对逻辑 (新增) ==================

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

    private void doCalculateDiff(SfDeployment deployment, List<SfDeploymentItem> items) throws Exception {
        com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

        // 并行从源和目标环境拉取 ZIP
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
            // 【关键】智能获取该元数据的 Hash
            String sourceHash = getMetadataHash(sourceFileMap, item);
            String targetHash = getMetadataHash(targetFileMap, item);

            String status;
            if(sourceHash == null) {
                status = "Invalid"; // 源环境没有，说明可能被删了或者名字错了
            } else if(targetHash == null) {
                status = "New"; // 目标环境没有，是新增
            } else if(sourceHash.equals(targetHash)) {
                status = "Same"; // Hash一致，无变化
            } else {
                status = "Changed"; // Hash不同，有变更
            }

            item.setDiffStatus(status);
            item.setLastCheckTime(new Date());
            sfDeploymentItemMapper.updateById(item);
        }
    }

    /**
     * 【核心修复】根据元数据类型和名称，从文件 Map 中找到对应的 Hash
     */
    private String getMetadataHash(Map<String, String> fileMap, SfDeploymentItem item) {
        String type = item.getMetadataType();
        String name = item.getMemberName();

        // 1. 对象子类型 (ValidationRule, RecordType, WebLink 等)
        // 映射文件: objects/Account.object
        if(isObjectChild(type)) {
            String parentName = name.contains(".") ? name.split("\\.")[0] : name;
            String searchKey = "objects/" + parentName + ".object";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith(searchKey)) return entry.getValue();
            }
        }

        // 2. 工作流子类型 (WorkflowRule 等)
        // 映射文件: workflows/Account.workflow
        if(isWorkflowChild(type)) {
            String parentName = name.contains(".") ? name.split("\\.")[0] : name;
            String searchKey = "workflows/" + parentName + ".workflow";
            for(Map.Entry<String, String> entry : fileMap.entrySet()) {
                if(entry.getKey().endsWith(searchKey)) return entry.getValue();
            }
        }

        // 3. Bundle 类型
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

        // 4. 标准文件类型 (ApexClass 等)
        for(Map.Entry<String, String> entry : fileMap.entrySet()) {
            String fileName = entry.getKey();
            // 简单匹配：文件名包含元数据名 (排除 -meta.xml 优先)
            if(fileName.contains("/" + name + ".") || fileName.startsWith(name + ".")) {
                if(!fileName.endsWith("-meta.xml")) return entry.getValue();
            }
        }
        // 兜底匹配
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

    /**
     * 拉取 ZIP 并返回 {文件名: MD5} 的 Map
     */
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

                    String md5 = DigestUtils.md5Hex(bos.toByteArray());
                    // 存储全路径文件名，例如: objects/Account.object, lwc/cmp/cmp.js
                    resultMap.put(entry.getName(), md5);
                }
            }
        } catch(Exception e) {
            log.warn("Org {} 拉取比对文件失败: {}", orgId, e.getMessage());
        }
        return resultMap;
    }

    /**
     * 智能匹配文件名与元数据名
     * memberName: MyClass
     * map keys: classes/MyClass.cls, classes/MyClass.cls-meta.xml
     */
    private String findHash(Map<String, String> map, String memberName) {
        String bestMatch = null;
        for(Map.Entry<String, String> entry : map.entrySet()) {
            String fileName = entry.getKey();
            // 匹配逻辑：文件名包含 memberName
            // 更严谨的逻辑：fileName.startsWith(memberName + ".")
            if(fileName.contains(memberName)) {
                if(bestMatch == null) bestMatch = entry.getValue();
                // 优先取非 meta 文件
                if(!fileName.endsWith("-meta.xml")) {
                    return entry.getValue();
                }
            }
        }
        return bestMatch;
    }

    private Map<String, String> retrieveAndHash(Long orgId, com.sforce.soap.metadata.Package manifest) {
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

                    String md5 = DigestUtils.md5Hex(bos.toByteArray());
                    // 存入 Map, Key 为文件名 (去掉目录前缀)
                    String name = entry.getName();
                    String simpleName = name.substring(name.lastIndexOf("/") + 1);
                    resultMap.put(simpleName, md5);
                }
            }
        } catch(Exception e) {
            log.warn("Org {} 拉取比对文件失败: {}", orgId, e.getMessage());
        }
        return resultMap;
    }

    // ================== 辅助方法 ==================

    /**
     * 【重要】构建 Salesforce Package 对象
     * 支持混合部署：将不同类型的元数据分类放入 PackageTypeMembers
     */
    private com.sforce.soap.metadata.Package generateManifestObject(List<SfDeploymentItem> items) {
        com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
        Map<String, List<String>> typesMap = new HashMap<>();

        // 1. 分组
        for(SfDeploymentItem item : items) {
            typesMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>()).add(item.getMemberName());
        }

        // 2. 构建数组
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

    // ================== 【新增】下载部署包功能 ==================
    @Override
    public void downloadPackage(Long deploymentId, HttpServletResponse response) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        List<SfDeploymentItem> items = selectItems(deploymentId);

        if(items.isEmpty()) throw new ServiceException("部署包为空，无法下载");

        try {
            // 1. 构建清单 & 拉取
            com.sforce.soap.metadata.Package manifest = PackageXmlBuilder.build(items);
            byte[] zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);

            // 2. 执行清洗 (确保下载的内容与部署内容完全一致)
            zipBytes = MetadataCleaner.clean(zipBytes, items);

            // 3. 设置响应头
            response.reset();
            response.setContentType("application/octet-stream");
            response.setCharacterEncoding("utf-8");
            String fileName = "deploy_pkg_" + deploymentId + "_" + System.currentTimeMillis() + ".zip";
            response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

            // 4. 写出流
            response.getOutputStream().write(zipBytes);

        } catch(Exception e) {
            log.error("下载部署包失败", e);
            throw new ServiceException("生成下载文件失败: " + e.getMessage());
        }
    }

    // ================== 【修改】预览功能 (增强：返回文件内容) ==================
    @Override
    public Map<String, Object> previewPackage(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        List<SfDeploymentItem> items = selectItems(deploymentId);
        if(items.isEmpty()) throw new ServiceException("部署包为空，请先添加元数据");

        com.sforce.soap.metadata.Package manifest = PackageXmlBuilder.build(items);

        byte[] zipBytes;
        try {
            log.info("正在生成预览包，源环境: {}", deployment.getSourceOrgId());
            zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);
        } catch(Exception e) {
            throw new ServiceException("生成预览包失败: " + e.getMessage());
        }

        if(zipBytes == null || zipBytes.length == 0) {
            throw new ServiceException("源环境返回的部署包为空");
        }

        // 1. 执行清洗
        zipBytes = MetadataCleaner.clean(zipBytes, items);

        // 2. 解析 ZIP 结构并读取文本文件内容
        List<String> fileList = new ArrayList<>();
        Map<String, String> fileContents = new HashMap<>(); // 文件名 -> 内容
        String packageXmlContent = "";

        try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if(!entry.isDirectory()) {
                    fileList.add(name);

                    // 判断是否为文本文件，如果是则读取内容以便前端展示
                    if(isTextFile(name)) {
                        byte[] contentBytes = readStream(zis);
                        // 限制大小 (例如 1MB)，防止前端卡死
                        if(contentBytes.length < 1024 * 1024) {
                            String content = new String(contentBytes, StandardCharsets.UTF_8);
                            fileContents.put(name, content);

                            // 顺便定位 package.xml
                            if(name.endsWith("package.xml")) {
                                packageXmlContent = content;
                            }
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
        result.put("fileContents", fileContents); // 返回文件内容 Map
        result.put("packageXml", packageXmlContent);
        result.put("size", zipBytes.length);

        return result;
    }

    // 辅助：判断是否为可预览的文本文件
    private boolean isTextFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".xml") || n.endsWith(".cls") || n.endsWith(".trigger") ||
                n.endsWith(".page") || n.endsWith(".component") || n.endsWith(".object") ||
                n.endsWith(".field") || n.endsWith(".layout") || n.endsWith(".profile") ||
                n.endsWith(".permissionset") || n.endsWith(".js") || n.endsWith(".css") ||
                n.endsWith(".html") || n.endsWith(".txt") || n.endsWith(".json") ||
                n.endsWith(".labels") || n.endsWith(".workflow") || n.endsWith(".flow");
    }

    /**
     * 辅助方法：手动生成 XML 字符串 (作为兜底方案)
     */
    private String generateXmlStringFromManifest(List<SfDeploymentItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Package xmlns=\"http://soap.sforce.com/2006/04/metadata\">\n");

        Map<String, List<String>> typesMap = new HashMap<>();
        for(SfDeploymentItem item : items) {
            typesMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>()).add(item.getMemberName());
        }

        for(Map.Entry<String, List<String>> entry : typesMap.entrySet()) {
            sb.append("    <types>\n");
            for(String member : entry.getValue()) {
                sb.append("        <members>").append(member).append("</members>\n");
            }
            sb.append("        <name>").append(entry.getKey()).append("</name>\n");
            sb.append("    </types>\n");
        }

        sb.append("    <version>58.0</version>\n");
        sb.append("</Package>");
        return sb.toString();
    }

    // 辅助流读取 (保持不变)
    private byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        return out.toByteArray();
    }
}