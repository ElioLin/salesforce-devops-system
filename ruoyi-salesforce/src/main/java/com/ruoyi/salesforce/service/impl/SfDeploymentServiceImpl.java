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
    @Transactional
    public void addItems(Long deploymentId, List<SfDeploymentItem> items) {
        for(SfDeploymentItem item : items) {
            item.setDeploymentId(deploymentId);
            item.setCreateTime(new Date());
            item.setAction("Add");
            item.setDiffStatus("Comparing");
            sfDeploymentItemMapper.insert(item);
        }
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
            com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

            log.info("开始提取代码，Org: {}", deployment.getSourceOrgId());
            byte[] zipBytes = sfMetadataService.retrieveZipByManifest(deployment.getSourceOrgId(), manifest);

            if(zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("提取代码失败：返回的ZIP包为空");
            }

            // 清洗元数据
            log.info("开始清洗元数据...");
            zipBytes = MetadataCleaner.clean(zipBytes, items);
            log.info("元数据清洗完成，ZIP大小: {} bytes", zipBytes.length);

            // 部署
            log.info("开始部署，Org: {}", deployment.getTargetOrgId());
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

    /**
     * 【核心修改】检查部署状态并在成功后清理缓存
     */
    @Override
    public String checkDeployStatus(Long targetOrgId, String processId) throws Exception {
        String statusJson = sfMetadataService.checkDeployStatus(targetOrgId, processId);
        JSONObject result = JSONObject.parseObject(statusJson);
        String status = result.getString("status");
        // 【关键】获取本次请求是否为 CheckOnly (验证模式)
        boolean isCheckOnly = result.getBooleanValue("checkOnly");

        String finalStatus = null;
        String errorMessage = null;

        if("Succeeded".equals(status)) {
            finalStatus = "Succeeded";

            // ================= 【新增】 自动清理 Redis 缓存 =================
            // 只有当部署真正成功（非验证模式，或验证模式也可选清理）时，才需要清理
            // 这里我们简单处理：只要 Salesforce 返回 Succeeded，就清理目标环境缓存
            if(!isCheckOnly) {
                try {
                    log.info("检测到部署成功 (非验证)，正在清理 Org [{}] 的元数据缓存...", targetOrgId);
                    sfMetadataService.clearCacheForOrg(targetOrgId);
                } catch(Exception e) {
                    log.warn("自动清理缓存失败: {}", e.getMessage());
                }
            } else {
                log.info("验证成功 (CheckOnly=true)，无需清理元数据缓存。");
            }
            // ==============================================================

        } else if("Failed".equals(status) || "Canceled".equals(status)) {
            finalStatus = "Failed";
            errorMessage = extractErrorMessage(result);
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
                    // 截断过长日志
                    if(errorMessage.length() > 9900) {
                        errorMessage = errorMessage.substring(0, 9900) + "\n...(错误信息过长已截断)";
                    }
                    deploy.setErrorMsg(errorMessage);
                    needUpdate = true;
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

    private void doCalculateDiff(SfDeployment deployment, List<SfDeploymentItem> items) throws Exception {
        com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

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
            if(sourceHash == null) status = "Invalid";
            else if(targetHash == null) status = "New";
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
                    String md5 = DigestUtils.md5Hex(bos.toByteArray());
                    resultMap.put(entry.getName(), md5);
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