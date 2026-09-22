package com.ruoyi.salesforce.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.salesforce.domain.SfGitConfig;
import com.ruoyi.salesforce.service.impl.SfGitConfigServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/salesforce/gitConfig")
public class SfGitConfigController extends BaseController {

    @Autowired
    private SfGitConfigServiceImpl gitConfigService;

    @GetMapping("/list")
    public TableDataInfo list(SfGitConfig config) {
        startPage();
        LambdaQueryWrapper<SfGitConfig> lqw = new LambdaQueryWrapper<>();
        lqw.eq(SfGitConfig::getTenantId, SecurityUtils.getLoginUser().getTenantId());
        lqw.like(StringUtils.isNotEmpty(config.getName()), SfGitConfig::getName, config.getName());

        List<SfGitConfig> list = gitConfigService.list(lqw);

        //列表接口必须强制脱敏！防止 AES 密文流向前端被当成原密码使用
        if(list != null && !list.isEmpty()) {
            for(SfGitConfig item : list) {
                item.setCredentials("");
            }
        }
        return getDataTable(list);
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        SfGitConfig config = gitConfigService.getById(id);
        if(config != null) config.setCredentials(""); // 脱敏
        return AjaxResult.success(config);
    }

    @Log(title = "Git配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(gitConfigService.removeByIds(Arrays.asList(ids)));
    }

    @GetMapping("/current")
    public AjaxResult getCurrent() {
        SfGitConfig config = gitConfigService.getCurrentConfig(SecurityUtils.getLoginUser().getTenantId());
        if(config != null) {
            config.setCredentials(""); // 严禁将密码密文传回前端！脱敏处理
        }
        return AjaxResult.success(config);
    }

    @Log(title = "Git配置设置", businessType = BusinessType.UPDATE)
    @PostMapping("/save")
    public AjaxResult save(@RequestBody SfGitConfig config) {
        gitConfigService.saveConfig(config);
        return AjaxResult.success("配置保存成功");
    }

    @PostMapping("/testConnection")
    public AjaxResult testConnection(@RequestBody SfGitConfig config) {
        gitConfigService.testConnection(config);
        return AjaxResult.success("连通性测试通过！网络与鉴权一切正常。");
    }

    @GetMapping("/branches")
    public AjaxResult getBranches() {
        return AjaxResult.success(gitConfigService.getRemoteBranches());
    }
}
