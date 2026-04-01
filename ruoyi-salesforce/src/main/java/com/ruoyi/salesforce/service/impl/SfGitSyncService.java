package com.ruoyi.salesforce.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.salesforce.domain.SfDeployment;
import com.ruoyi.salesforce.domain.SfGitConfig;
import com.ruoyi.salesforce.mapper.SfDeploymentHistoryMapper;
import com.ruoyi.salesforce.websocket.DeployWebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class SfGitSyncService {

    @Autowired
    private SfGitConfigServiceImpl gitConfigService;

    @Autowired
    private SfDeploymentHistoryMapper historyMapper;

    @Value("${ruoyi.salesforce.GIT_AES_KEY:SfDevOpsGitToken}")
    private String AES_KEY;
    private AES aes;
    @Autowired
    private com.ruoyi.system.service.ISysUserService userService;

    @PostConstruct
    public void initAes() {
        if(AES_KEY == null || AES_KEY.length() != 16) {
            AES_KEY = "SfDevOpsGitToken";
        }
        this.aes = SecureUtil.aes(AES_KEY.getBytes());
    }

    public void syncToGit(SfDeployment deployment, File sourceZip, Long historyId) {
        log.info(">>>> 开始启动 Git 自动化同步流水线 | 部署包: {}", deployment.getTitle());
        sendLog(deployment.getId(), ">>> 触发 GitOps 同步流水线，目标分支: " + deployment.getTargetBranch());

        SfGitConfig config = gitConfigService.getCurrentConfig(deployment.getTenantId());
        if(config == null || config.getIsActive() == 0) {
            sendLog(deployment.getId(), "Git 同步已跳过：全局未配置或已停用。");
            return;
        }

        String realToken = aes.decryptStr(config.getCredentials());
        UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider("OAUTH2", realToken);

        File workspace = new File(System.getProperty("java.io.tmpdir"), "sf_git_workspace_" + deployment.getId() + "_" + System.currentTimeMillis());
        if(workspace.exists()) {
            FileUtil.del(workspace);
        }
        workspace.mkdirs();

        Git git = null;
        try {
            sendLog(deployment.getId(), "正在拉取 (Clone) 远程仓库分支...");
            long t1 = System.currentTimeMillis();
            CloneCommand cloneCmd = Git.cloneRepository()
                    .setURI(config.getRepoUrl())
                    .setCredentialsProvider(credentials)
                    .setDirectory(workspace)
                    .setBranch(deployment.getTargetBranch())
                    .setCloneAllBranches(false);

            git = cloneCmd.call();
            sendLog(deployment.getId(), "克隆完成，耗时: " + (System.currentTimeMillis() - t1) + "ms");

            sendLog(deployment.getId(), "正在提取部署包元数据并覆盖至 Git 工作区...");
            extractZipToWorkspace(sourceZip, workspace);

            String authorName = deployment.getCreateBy() != null ? deployment.getCreateBy() : "SfDevOpsBot";
            String authorEmail = "sf-devops-bot@local.domain";
            try {
                // 根据账号名查询 RuoYi 系统的真实用户实体
                com.ruoyi.common.core.domain.entity.SysUser sysUser = userService.selectUserByUserName(authorName);
                if (sysUser != null && com.ruoyi.common.utils.StringUtils.isNotEmpty(sysUser.getEmail())) {
                    authorEmail = sysUser.getEmail(); // 拿到真实邮箱！
                } else {
                    // 如果用户在 RuoYi 里没填邮箱，用公司默认域名做一个伪装兜底
                    authorEmail = "yijian.lin@runner-corp.com.cn";
                }
            } catch (Exception e) {
                log.warn("无法获取用户 {} 的真实邮箱，将使用兜底配置", authorName);
            }

            PersonIdent authorIdent = new PersonIdent(authorName, authorEmail, new Date(), java.util.TimeZone.getDefault());

            sendLog(deployment.getId(), "正在扫描文件变更 (Status)...");
            git.add().addFilepattern(".").call();

            // 【核心修复】：检查是否有真正的文件变化，防止产生空提交或被 gitignore 屏蔽
            org.eclipse.jgit.api.Status status = git.status().call();
            if(status.isClean()) {
                sendLog(deployment.getId(), "✅ 智能跳过：仓库内容无变化或被 .gitignore 忽略，无需提交。");
                updateHistoryGitStatus(historyId, "NoChange", "Success", "无代码变更");
                return;
            }

            sendLog(deployment.getId(), "正在生成 Git Commit (Author: " + authorName + ")...");
            String commitMsg = String.format("feat(deploy): 自动同步部署包 [%s]\n\nDemand No: %s\nType: %s",
                    deployment.getTitle(), deployment.getDemandNo(), deployment.getDeployType());

            org.eclipse.jgit.revwalk.RevCommit commit = git.commit()
                    .setAuthor(authorIdent)
                    .setCommitter(authorIdent)
                    .setMessage(commitMsg)
                    .call();

            String commitHash = commit.getName().substring(0, 8);
            sendLog(deployment.getId(), "Commit 生成成功 (" + commitHash + ")，正在推送到远程仓库...");

            // 【核心修复】：明确指定推送的目标分支
            Iterable<org.eclipse.jgit.transport.PushResult> pushResults = git.push()
                    .setCredentialsProvider(credentials)
                    .add(deployment.getTargetBranch())
                    .call();

            for(org.eclipse.jgit.transport.PushResult pushResult : pushResults) {
                for(org.eclipse.jgit.transport.RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                    if(update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK ||
                            update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) {
                        sendLog(deployment.getId(), "✅ Git 代码同步成功！(Commit: " + commitHash + ")");
                        updateHistoryGitStatus(historyId, commitHash, "Success", "同步成功");
                    } else {
                        throw new RuntimeException("推送被拒绝，状态: " + update.getStatus() + "，原因: " + update.getMessage());
                    }
                }
            }
        } catch(Exception e) {
            log.error("Git 同步流水线异常", e);
            sendLog(deployment.getId(), "❌ Git 同步失败: " + e.getMessage());
            updateHistoryGitStatus(historyId, null, "Failed", e.getMessage());
        } finally {
            if(git != null) git.close();
            try {
                FileUtil.del(workspace);
                log.info("Git 临时工作区清理完毕: {}", workspace.getAbsolutePath());
            } catch(Exception e) {
                log.warn("Git 工作区清理失败", e);
            }
        }
    }

    private void extractZipToWorkspace(File zipFile, File workspace) throws Exception {
        // 【核心修复】：智能探测 Salesforce DX 目录结构，防止文件被扔在根目录导致 gitignore 拦截
        File sfdxDir = new File(workspace, "force-app/main/default");
        File srcDir = new File(workspace, "src");
        String basePath = "";
        if(sfdxDir.exists()) {
            basePath = "force-app/main/default/";
        } else if(srcDir.exists()) {
            basePath = "src/";
        }

        try(ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while((entry = zis.getNextEntry()) != null) {
                if(entry.isDirectory() || entry.getName().endsWith("package.xml")) continue;

                String name = entry.getName();
                int firstSlash = name.indexOf('/');
                if(firstSlash != -1 && firstSlash < name.length() - 1) {
                    name = name.substring(firstSlash + 1);
                }

                File destFile = new File(workspace, basePath + name);
                destFile.getParentFile().mkdirs();

                try(FileOutputStream fos = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    private void sendLog(Long deploymentId, String detail) {
        log.info("[GitSync-{}] {}", deploymentId, detail); // 【双通道】强力输出到后端控制台
        JSONObject json = new JSONObject();
        json.put("status", "Succeeded");
        json.put("stateDetail", detail);
        json.put("done", false);
        DeployWebSocketServer.sendMessage(deploymentId, json.toJSONString());
    }

    private void updateHistoryGitStatus(Long historyId, String commitHash, String status, String logMsg) {
        try {
            historyMapper.updateGitStatus(historyId, commitHash, status, logMsg);
        } catch(Exception e) {
            log.warn("无法回写 Git 状态，请忽略此错误或补充 Mapper XML 配置", e);
        }
    }
}
