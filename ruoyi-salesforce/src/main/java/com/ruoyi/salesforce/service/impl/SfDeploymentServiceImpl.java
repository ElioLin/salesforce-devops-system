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
import com.sforce.soap.metadata.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.codec.digest.DigestUtils;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.CompletableFuture;

@Service
public class SfDeploymentServiceImpl extends ServiceImpl<SfDeploymentMapper, SfDeployment> implements ISfDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(SfDeploymentServiceImpl.class);

    @Autowired
    private SfDeploymentMapper sfDeploymentMapper;

    @Autowired
    private SfDeploymentItemMapper sfDeploymentItemMapper;

    @Autowired
    private ISfMetadataService sfMetadataService;

    // --- 基础 CRUD 方法 (保持不变) ---
    @Override
    public List<SfDeployment> selectSfDeploymentList(SfDeployment sfDeployment) {
        return this.baseMapper.selectSfDeploymentList(sfDeployment);
    }

    @Override
    public SfDeployment selectSfDeploymentById(Long id) {
        SfDeployment deployment = this.baseMapper.selectSfDeploymentById(id);
        if(deployment != null) {
            List<SfDeploymentItem> items = sfDeploymentItemMapper.selectList(
                    new LambdaQueryWrapper<SfDeploymentItem>()
                            .eq(SfDeploymentItem::getDeploymentId, id)
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
            sfDeploymentItemMapper.insert(item);
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
                    new LambdaQueryWrapper<SfDeploymentItem>()
                            .eq(SfDeploymentItem::getDeploymentId, id)
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
                new LambdaQueryWrapper<SfDeploymentItem>()
                        .eq(SfDeploymentItem::getDeploymentId, deploymentId)
        );
    }

    // ================== 异步部署逻辑 ==================

    @Override
    public void deployPackage(Long deploymentId, boolean checkOnly) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        if("Processing".equals(deployment.getStatus()) || "Deploying".equals(deployment.getStatus())) {
            throw new ServiceException("当前部署包正在处理中，请勿重复操作");
        }

        List<SfDeploymentItem> items = selectItems(deploymentId);
        if(items.isEmpty()) throw new ServiceException("部署包为空，请先添加元数据");

        // 1. 更新状态为 Processing
        deployment.setStatus("Processing");
        deployment.setErrorMsg("");
        sfDeploymentMapper.updateById(deployment);

        // 2. 启动异步线程
        CompletableFuture.runAsync(() -> {
            try {
                processAsyncDeployment(deployment, items, checkOnly);
            } catch(Exception e) {
                log.error("异步部署任务异常", e);
                handleDeploymentError(deploymentId, "系统内部错误: " + e.getMessage());
            }
        });
    }

    private void processAsyncDeployment(SfDeployment deployment, List<SfDeploymentItem> items, boolean checkOnly) {
        try {
            // 阶段一：提取代码
            log.info("开始提取代码，Org: {}", deployment.getSourceOrgId());
            MetadataConnection sourceConn = sfMetadataService.getMetadataConnection(deployment.getSourceOrgId());

            RetrieveRequest retrieveRequest = new RetrieveRequest();
            retrieveRequest.setApiVersion(58.0);
            retrieveRequest.setUnpackaged(buildManifest(items)); // 使用内部构建方法

            AsyncResult retrieveAsync = sourceConn.retrieve(retrieveRequest);
            RetrieveResult retrieveResult = waitForRetrieveCompletion(sourceConn, retrieveAsync.getId());

            if(retrieveResult.getStatus() != RetrieveStatus.Succeeded) {
                handleDeploymentError(deployment.getId(), "提取代码失败: " + retrieveResult.getErrorMessage());
                return;
            }

            byte[] zipBytes = retrieveResult.getZipFile();
            log.info("提取成功，ZIP大小: {} bytes", zipBytes.length);

            // 阶段二：部署代码
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

            // 阶段三：更新状态为 Validating/Deploying 并记录 AsyncId
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

    // ================== 快速部署逻辑 ==================

    @Override
    public void quickDeploy(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if(deployment == null) throw new ServiceException("部署包不存在");

        // 必须是验证成功 (Succeeded) 且有 lastAsyncId
        if(!"Succeeded".equals(deployment.getStatus()) || deployment.getLastAsyncId() == null) {
            throw new ServiceException("只有【验证成功】的部署包才能使用快速部署");
        }

        // 更新状态
        deployment.setStatus("Deploying");
        deployment.setErrorMsg("");
        sfDeploymentMapper.updateById(deployment);

        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始快速部署, Org: {}, ValidationId: {}", deployment.getTargetOrgId(), deployment.getLastAsyncId());

                // 调用 Service 获取 String ID
                String newProcessId = sfMetadataService.deployRecentValidation(
                        deployment.getTargetOrgId(),
                        deployment.getLastAsyncId()
                );

                SfDeployment update = new SfDeployment();
                update.setId(deployment.getId());
                update.setLastAsyncId(newProcessId);
                sfDeploymentMapper.updateById(update);

                log.info("快速部署已提交，New AsyncId: {}", newProcessId);

            } catch(Exception e) {
                log.error("快速部署失败", e);
                handleDeploymentError(deployment.getId(), "快速部署异常: " + e.getMessage());
            }
        });
    }

    // ================== 状态检查 & 回写 (关键修复) ==================

    @Override
    public String checkDeployStatus(Long targetOrgId, String processId) throws Exception {
        // 1. 调用 Salesforce 接口查询
        String statusJson = sfMetadataService.checkDeployStatus(targetOrgId, processId);

        // 2. 【核心修复】解析状态并同步到数据库
        // 简单字符串检查，避免依赖复杂 JSON 库
        String finalStatus = null;
        if(statusJson.contains("\"status\":\"Succeeded\"")) {
            finalStatus = "Succeeded";
        } else if(statusJson.contains("\"status\":\"Failed\"") || statusJson.contains("\"status\":\"Canceled\"")) {
            finalStatus = "Failed";
        }

        // 如果是最终状态，更新数据库
        if(finalStatus != null) {
            // 根据 processId 找到对应的部署包
            SfDeployment deploy = sfDeploymentMapper.selectOne(
                    new LambdaQueryWrapper<SfDeployment>().eq(SfDeployment::getLastAsyncId, processId)
            );

            // 只有当数据库状态还不是最终状态时才更新，避免重复更新
            if(deploy != null && !finalStatus.equals(deploy.getStatus())) {
                deploy.setStatus(finalStatus);
                sfDeploymentMapper.updateById(deploy);
                log.info("已同步部署包状态: ID={}, Status={}", deploy.getId(), finalStatus);
            }
        }

        return statusJson;
    }

    // --- 辅助方法 ---
    private com.sforce.soap.metadata.Package buildManifest(List<SfDeploymentItem> items) {
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

    private RetrieveResult waitForRetrieveCompletion(MetadataConnection conn, String asyncId) throws Exception {
        int maxRetries = 60;
        for(int i = 0; i < maxRetries; i++) {
            RetrieveResult result = conn.checkRetrieveStatus(asyncId, true);
            if(result.isDone()) return result;
            Thread.sleep(2000);
        }
        throw new RuntimeException("源环境提取代码超时");
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
    public void checkDiffStatus(Long deploymentId) {
        SfDeployment deployment = selectSfDeploymentById(deploymentId);
        if (deployment == null || deployment.getTargetOrgId() == null) {
            throw new RuntimeException("请先设置目标环境");
        }

        List<SfDeploymentItem> items = selectItems(deploymentId);
        if (items.isEmpty()) return;

        // 1. 状态置为 Comparing
        for (SfDeploymentItem item : items) {
            item.setDiffStatus("Comparing");
            sfDeploymentItemMapper.updateById(item);
        }

        // 2. 异步比对
        CompletableFuture.runAsync(() -> {
            try {
                doCalculateDiff(deployment, items);
            } catch (Exception e) {
                log.error("比对失败", e);
                for (SfDeploymentItem item : items) {
                    item.setDiffStatus("Unknown");
                    sfDeploymentItemMapper.updateById(item);
                }
            }
        });
    }

    private void doCalculateDiff(SfDeployment deployment, List<SfDeploymentItem> items) throws Exception {
        com.sforce.soap.metadata.Package manifest = generateManifestObject(items);

        // 并行拉取
        CompletableFuture<Map<String, String>> sourceFuture = CompletableFuture.supplyAsync(() ->
                retrieveAndHash(deployment.getSourceOrgId(), manifest)
        );
        CompletableFuture<Map<String, String>> targetFuture = CompletableFuture.supplyAsync(() ->
                retrieveAndHash(deployment.getTargetOrgId(), manifest)
        );

        CompletableFuture.allOf(sourceFuture, targetFuture).join();

        Map<String, String> sourceHashMap = sourceFuture.get();
        Map<String, String> targetHashMap = targetFuture.get();

        for (SfDeploymentItem item : items) {
            // 【关键修复】使用更智能的查找逻辑，不仅仅靠文件名匹配
            String sourceHash = findHash(sourceHashMap, item.getMemberName());
            String targetHash = findHash(targetHashMap, item.getMemberName());

            String status;
            if (sourceHash == null) {
                status = "Invalid"; // 源环境未找到文件
            } else if (targetHash == null) {
                status = "New"; // 目标环境未找到文件
            } else if (sourceHash.equals(targetHash)) {
                status = "Same"; // 哈希一致
            } else {
                status = "Changed"; // 哈希不同
            }

            item.setDiffStatus(status);
            item.setLastCheckTime(new Date());
            sfDeploymentItemMapper.updateById(item);
        }
    }

    /**
     * 【关键修复】更智能的哈希查找
     * 解决问题：ZIP里的文件名是 MyClass.cls，但 memberName 是 MyClass，导致找不到。
     * 同时优先匹配代码文件，忽略 -meta.xml（除非只有 meta.xml）
     */
    private String findHash(Map<String, String> map, String memberName) {
        String bestMatchHash = null;

        // 1. 精确匹配 (虽然不太可能，因为zip里都有后缀)
        if (map.containsKey(memberName)) return map.get(memberName);

        // 2. 前缀匹配
        // 遍历 map 寻找文件名以 "memberName." 开头的 entry
        // 例如 memberName="MyClass", 匹配 "MyClass.cls", "MyClass.trigger", "MyClass.cls-meta.xml"
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String fileName = entry.getKey();

            // 检查文件名是否匹配 (注意：要确保是全名匹配，防止 MyClass 匹配到 MyClassTest)
            // 逻辑：文件名必须以 memberName + "." 开头
            if (fileName.startsWith(memberName + ".")) {
                // 如果还没有匹配项，先存下来
                if (bestMatchHash == null) {
                    bestMatchHash = entry.getValue();
                }

                // 优化：如果有多个文件 (如 .cls 和 .cls-meta.xml)，优先返回非 meta 文件
                // 因为通常我们关心代码内容的变更
                if (!fileName.endsWith("-meta.xml")) {
                    return entry.getValue(); // 找到代码文件，直接返回
                }
            }
        }
        return bestMatchHash;
    }

    private Map<String, String> retrieveAndHash(Long orgId, com.sforce.soap.metadata.Package manifest) {
        Map<String, String> resultMap = new HashMap<>();
        try {
            // 复用 MetadataService
            byte[] zipData = sfMetadataService.retrieveZipByManifest(orgId, manifest);
            if (zipData == null) return resultMap;

            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory() || entry.getName().endsWith("package.xml")) continue;

                    // 读取内容
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                    String md5 = DigestUtils.md5Hex(bos.toByteArray());

                    // 【关键修复】处理文件名
                    // ZIP 路径可能是 "unpackaged/classes/MyClass.cls" 或 "classes/MyClass.cls"
                    String fullPath = entry.getName();
                    // 只取最后的文件名部分
                    String simpleName = fullPath.substring(fullPath.lastIndexOf("/") + 1);

                    resultMap.put(simpleName, md5);
                }
            }
        } catch (Exception e) {
            log.warn("Org {} 拉取比对文件失败 (可能是目标环境不存在这些文件): {}", orgId, e.getMessage());
        }
        return resultMap;
    }

    private com.sforce.soap.metadata.Package generateManifestObject(List<SfDeploymentItem> items) {
        com.sforce.soap.metadata.Package manifest = new com.sforce.soap.metadata.Package();
        Map<String, List<String>> typesMap = new HashMap<>();
        for (SfDeploymentItem item : items) {
            typesMap.computeIfAbsent(item.getMetadataType(), k -> new ArrayList<>()).add(item.getMemberName());
        }
        List<PackageTypeMembers> typeMembersList = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : typesMap.entrySet()) {
            PackageTypeMembers typeMembers = new PackageTypeMembers();
            typeMembers.setName(entry.getKey());
            typeMembers.setMembers(entry.getValue().toArray(new String[0]));
            typeMembersList.add(typeMembers);
        }
        manifest.setTypes(typeMembersList.toArray(new PackageTypeMembers[0]));
        manifest.setVersion("58.0");
        return manifest;
    }
}