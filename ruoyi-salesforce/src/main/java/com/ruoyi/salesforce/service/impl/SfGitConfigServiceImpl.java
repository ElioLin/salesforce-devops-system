package com.ruoyi.salesforce.service.impl;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfGitConfig;
import com.ruoyi.salesforce.mapper.SfGitConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Service
public class SfGitConfigServiceImpl extends ServiceImpl<SfGitConfigMapper, SfGitConfig> {

    // 【极其重要】：企业级加密秘钥，务必放入 Nacos 或 application.yml 中，此处仅为示例
    @Value("${ruoyi.salesforce.GIT_AES_KEY}")
    private String AES_KEY;
    private AES aes;

    @PostConstruct
    public void initAes() {
        // AES 秘钥长度强校验（必须是 16、24 或 32 位）
        if(StringUtils.isEmpty(AES_KEY) || AES_KEY.length() != 16) {
            log.warn("⚠️ 警告：Git 配置的 AES 秘钥未配置或长度不等于 16 位，系统将强制使用安全默认秘钥！");
            AES_KEY = "SfDevOpsGitToken"; // 刚好16个字符
        }
        // 安全初始化
        this.aes = SecureUtil.aes(AES_KEY.getBytes());
        log.info("✅ Git 凭证加密组件 (AES) 初始化成功！");
    }

    /**
     * 获取当前租户的 Git 配置 (通常限制一个租户一条有效记录)
     */
    public SfGitConfig getCurrentConfig(String tenantId) {
        if(StringUtils.isEmpty(tenantId)) return null;
        List<SfGitConfig> list = this.list(new LambdaQueryWrapper<SfGitConfig>()
                .eq(SfGitConfig::getTenantId, tenantId)
                .eq(SfGitConfig::getIsActive, 1));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 保存或更新，并强制加密 Token，同时完善审计字段
     */
    public void saveConfig(SfGitConfig config) {
        config.setTenantId(SecurityUtils.getLoginUser().getTenantId());

        // 如果密码框传了值，说明用户更新了密码，进行 AES 加密存储
        if(StringUtils.isNotEmpty(config.getCredentials())) {
            config.setCredentials(aes.encryptHex(config.getCredentials()));
        } else {
            // 如果为空，说明前端没改密码，保持数据库原样
            config.setCredentials(null);
        }

        if(config.getId() == null) {
            config.setCreateBy(SecurityUtils.getUsername());
            config.setCreateTime(new java.util.Date());
        }

        config.setUpdateBy(SecurityUtils.getUsername());
        config.setUpdateTime(new java.util.Date());

        this.saveOrUpdate(config);
    }

    /**
     * 【核心黑科技】：使用 JGit 瞬间测试 Git 仓库连通性
     */
    public boolean testConnection(SfGitConfig config) {
        String testUrl = config.getRepoUrl();
        String testToken = config.getCredentials();

        // 如果传过来的是空（表示没修改密码），则去数据库拉取密文解密
        if(StringUtils.isEmpty(testToken) && config.getId() != null) {
            SfGitConfig dbConfig = this.getById(config.getId());
            testToken = aes.decryptStr(dbConfig.getCredentials());
        }

        try {
            log.info("开始测试 Git 连通性，Target URL: {}", testUrl);
            LsRemoteCommand lsRemote = Git.lsRemoteRepository().setRemote(testUrl);

            // 适配 GitLab / GitHub 的 Personal Access Token
            if("TOKEN".equals(config.getAuthType())) {
                // GitLab / GitHub 通常可以使用任意用户名搭配 Token
                lsRemote.setCredentialsProvider(new UsernamePasswordCredentialsProvider("OAUTH2", testToken));
            }

            // 发起远程查询，如果能拿到远端引用(refs)，说明网络与鉴权全部通过！
            lsRemote.call();
            log.info("Git 连通性测试通过！");
            return true;
        } catch(Exception e) {
            log.error("Git 连通性测试失败", e);
            throw new ServiceException("连接失败，请检查仓库地址或网络环境！详细错误: " + e.getMessage());
        }
    }

    /**
     * 【新增】：获取当前 Git 仓库的所有远程分支 (按需懒加载使用)
     */
    public List<String> getRemoteBranches() {
        SfGitConfig config = getCurrentConfig(SecurityUtils.getLoginUser().getTenantId());
        if(config == null || config.getIsActive() == 0) {
            return new java.util.ArrayList<>();
        }

        // 解密真实 Token
        String realToken = aes.decryptStr(config.getCredentials());

        try {
            // JGit 获取远程引用，极速响应，不下载任何代码文件
            java.util.Collection<org.eclipse.jgit.lib.Ref> refs = Git.lsRemoteRepository()
                    .setRemote(config.getRepoUrl())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("OAUTH2", realToken))
                    .setHeads(true) // 核心：只抓取 branch (即 heads)，过滤掉 tag 和其他无用信息
                    .call();

            // 将 refs/heads/master 规整为 master 并提取为 List 返回
            return refs.stream()
                    .map(ref -> ref.getName().replace("refs/heads/", ""))
                    .collect(java.util.stream.Collectors.toList());
        } catch(Exception e) {
            log.error("获取远程 Git 分支失败", e);
            throw new ServiceException("连通 Git 仓库获取分支失败，请检查配置或网络！");
        }
    }
}
