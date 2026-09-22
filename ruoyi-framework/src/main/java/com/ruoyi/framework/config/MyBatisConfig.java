package com.ruoyi.framework.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.SpringBootVFS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;
import com.ruoyi.common.utils.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.apache.ibatis.plugin.Interceptor;
// 请将这些 import 追加到你文件顶部的 import 区域
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

/**
 * Mybatis支持*匹配扫描包
 *
 * @author ruoyi
 */
@Configuration
public class MyBatisConfig {
    @Autowired
    private Environment env;

    static final String DEFAULT_RESOURCE_PATTERN = "**/*.class";

    public static String setTypeAliasesPackage(String typeAliasesPackage) {
        ResourcePatternResolver resolver = (ResourcePatternResolver) new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        List<String> allResult = new ArrayList<String>();
        try {
            for(String aliasesPackage : typeAliasesPackage.split(",")) {
                List<String> result = new ArrayList<String>();
                aliasesPackage = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                        + ClassUtils.convertClassNameToResourcePath(aliasesPackage.trim()) + "/" + DEFAULT_RESOURCE_PATTERN;
                Resource[] resources = resolver.getResources(aliasesPackage);
                if(resources != null && resources.length > 0) {
                    MetadataReader metadataReader = null;
                    for(Resource resource : resources) {
                        if(resource.isReadable()) {
                            metadataReader = metadataReaderFactory.getMetadataReader(resource);
                            try {
                                result.add(Class.forName(metadataReader.getClassMetadata().getClassName()).getPackage().getName());
                            } catch(ClassNotFoundException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                if(result.size() > 0) {
                    HashSet<String> hashResult = new HashSet<String>(result);
                    allResult.addAll(hashResult);
                }
            }
            if(allResult.size() > 0) {
                typeAliasesPackage = String.join(",", (String[]) allResult.toArray(new String[0]));
            } else {
                throw new RuntimeException("mybatis typeAliasesPackage 路径扫描错误,参数typeAliasesPackage:" + typeAliasesPackage + "未找到任何包");
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
        return typeAliasesPackage;
    }

    public Resource[] resolveMapperLocations(String[] mapperLocations) {
        ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<Resource>();
        if(mapperLocations != null) {
            for(String mapperLocation : mapperLocations) {
                try {
                    Resource[] mappers = resourceResolver.getResources(mapperLocation);
                    resources.addAll(Arrays.asList(mappers));
                } catch(IOException e) {
                    // ignore
                }
            }
        }
        return resources.toArray(new Resource[resources.size()]);
    }

    /**
     * 配置 MyBatis-Plus 拦截器 (包含多租户隔离与乐观锁)
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ==========================================================
        // 1. 实例化多租户 SaaS 隔离拦截器 (注意：必须放在其他拦截器的最前面！)
        // ==========================================================
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor(new TenantLineHandler() {

            @Override
            public Expression getTenantId() {
                try {
                    // 从 SecurityUtils 提取当前登录人的公司 tenantId
                    LoginUser loginUser = SecurityUtils.getLoginUser();
                    if (loginUser != null && loginUser.getTenantId() != null) {
                        return new StringValue(loginUser.getTenantId());
                    }
                } catch (Exception e) {
                    // 兜底：未登录情况（系统底层调用），安全忽略
                }
                return new StringValue("000000"); // 默认主租户
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                try {
                    LoginUser loginUser = SecurityUtils.getLoginUser();
                    // 【上帝视角特权】超级管理员 (admin) 直接跳过隔离，查看全盘数据
                    if (loginUser != null && loginUser.getUser() != null && loginUser.getUser().isAdmin()) {
                        return true;
                    }
                } catch (Exception e) {
                    // 【幽灵特权】未登录时放行（确保登录验证的 SQL 能正常执行）
                    return true;
                }

                String lowerTableName = tableName.toLowerCase();

                // 【业务隔离区】Salesforce 业务表，强制隔离
                if (lowerTableName.startsWith("sf_")) {
                    return false;
                }

                // 【系统隔离区】若依核心用户表，强制隔离
                List<String> sysIsolateTables = Arrays.asList("sys_user", "sys_dept", "sys_role");
                if (sysIsolateTables.contains(lowerTableName)) {
                    return false;
                }

                // 【公共共享区】字典、菜单等系统级公用表，放行不隔离
                return true;
            }
        });

        // 将多租户拦截器装载进主炮塔 (必须排第一)
        interceptor.addInnerInterceptor(tenantInterceptor);

        // ==========================================================
        // 2. 添加原有的乐观锁插件 (必须排在多租户之后)
        // ==========================================================
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 如果后续要加分页插件，请加在这里：
        // interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        String typeAliasesPackage = env.getProperty("mybatis.typeAliasesPackage");
        String mapperLocations = env.getProperty("mybatis.mapperLocations");
        String configLocation = env.getProperty("mybatis.configLocation");

        // 【关键修改】这里改成了 MybatisSqlSessionFactoryBean
        MybatisSqlSessionFactoryBean sessionFactory = new MybatisSqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);

        // ... 中间的代码基本不变 (加载 typeAliases 等)
        sessionFactory.setTypeAliasesPackage(typeAliasesPackage);

        // 加载 mapper xml
        sessionFactory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(mapperLocations));

        // 加载 mybatis-config.xml
        sessionFactory.setConfigLocation(new DefaultResourceLoader().getResource(configLocation));

        Interceptor[] plugins = new Interceptor[]{
                new TenantAutoFillInterceptor(), // 1. 先执行参数自动填充
                mybatisPlusInterceptor()         // 2. 再执行 MP 的多租户 SQL 拦截和并发锁拦截
        };

        sessionFactory.setPlugins(plugins);

        return sessionFactory.getObject();
    }
}
