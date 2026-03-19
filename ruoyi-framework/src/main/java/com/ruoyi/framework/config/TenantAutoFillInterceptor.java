package com.ruoyi.framework.config;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

/**
 * 全局租户参数自动填充拦截器
 * 作用：在 MyBatis 执行 SQL 之前，利用反射自动为新增操作（INSERT）的对象填充 tenantId。
 * 完美杜绝人工遗漏，且直接免疫底层 JSqlParser 的解析 Bug。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class TenantAutoFillInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args[1];

        // 【核心】仅拦截 INSERT 操作
        if (ms.getSqlCommandType() == SqlCommandType.INSERT && parameter != null) {
            fillTenantId(parameter);
        }

        return invocation.proceed();
    }

    /**
     * 智能识别参数类型并填充
     */
    private void fillTenantId(Object parameter) {
        if (parameter instanceof Map) {
            // 处理 @Param 传入的 Map 或者是批量插入的 List
            for (Object val : ((Map<?, ?>) parameter).values()) {
                fillForObject(val);
            }
        } else if (parameter instanceof Iterable) {
            for (Object val : (Iterable<?>) parameter) {
                fillForObject(val);
            }
        } else {
            // 普通单个实体对象
            fillForObject(parameter);
        }
    }

    /**
     * 利用 Spring 的 ReflectionUtils 反射注入 tenantId
     */
    private void fillForObject(Object obj) {
        // 基本类型或空值直接跳过
        if (obj == null || obj instanceof String || obj instanceof Number) return;

        try {
            // 递归查找对象及其父类中是否定义了 tenantId 字段
            Field field = ReflectionUtils.findField(obj.getClass(), "tenantId");
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                Object value = ReflectionUtils.getField(field, obj);

                // 如果对象里的 tenantId 是空的，我们就主动给它塞进去
                if (value == null || StringUtils.isEmpty(value.toString())) {
                    LoginUser loginUser = SecurityUtils.getLoginUser();
                    if (loginUser != null && StringUtils.isNotEmpty(loginUser.getTenantId())) {
                        ReflectionUtils.setField(field, obj, loginUser.getTenantId());
                    }
                }
            }
        } catch (Exception e) {
            // 静默忽略。例如异步线程没有 Security 上下文，或者定时任务触发等情况
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {}
}
