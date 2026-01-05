package com.ruoyi.framework.config; // 或者是你的配置包路径

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.ruoyi.common.utils.SecurityUtils; // RuoYi 获取当前用户工具类
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import java.util.Date;

@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "updateTime", Date.class, new Date());
        try {
            // 尝试获取当前登录用户名，如果是在异步线程(如定时任务)可能获取不到，需捕获异常
            String username = SecurityUtils.getUsername();
            this.strictInsertFill(metaObject, "createBy", String.class, username);
            this.strictInsertFill(metaObject, "updateBy", String.class, username);
        } catch (Exception e) {
            // 异步任务或无上下文时，默认填 sys
            this.strictInsertFill(metaObject, "createBy", String.class, "sys");
            this.strictInsertFill(metaObject, "updateBy", String.class, "sys");
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
        try {
            String username = SecurityUtils.getUsername();
            this.strictUpdateFill(metaObject, "updateBy", String.class, username);
        } catch (Exception e) {
            this.strictUpdateFill(metaObject, "updateBy", String.class, "sys");
        }
    }
}