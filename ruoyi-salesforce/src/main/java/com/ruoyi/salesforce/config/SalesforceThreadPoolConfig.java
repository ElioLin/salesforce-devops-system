package com.ruoyi.salesforce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Salesforce 模块专用线程池配置
 * 实现核心业务与辅助业务的线程隔离，防止长任务阻塞短任务
 */
@Configuration
public class SalesforceThreadPoolConfig {

    /**
     * 1. 部署专用线程池 (重任务)
     * 用于：部署、验证、回滚、快速部署
     * 特点：任务耗时长(IO密集)，允许等待，核心线程数稍多
     */
    @Bean(name = "deployTaskExecutor")
    public Executor deployTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);  // 核心线程数 (同时也意味着支持10个并发部署)
        executor.setMaxPoolSize(50);   // 最大线程数 (突发流量)
        executor.setQueueCapacity(200); // 队列容量
        executor.setKeepAliveSeconds(300);
        executor.setThreadNamePrefix("sf-deploy-");
        // 拒绝策略：如果队列满了，由调用者线程执行 (CallerRunsPolicy)，防止任务丢失，但会变慢
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 2. 元数据处理线程池 (轻任务)
     * 用于：元数据预加载、差异比对、列表查询
     * 特点：任务较多但单个耗时较短，响应要求快
     */
    @Bean(name = "metaTaskExecutor")
    public Executor metaTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(100); // 支持较高的并发读取
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("sf-meta-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 3. 数据比对专用线程池 (高 IO / 大内存任务)
     * 用于：单对象比对启动、单对象重试操作
     * 特点：严格限制并发数，超出部分进入队列排队，保护服务器内存和 SF Bulk API 限制
     */
    @Bean(name = "reconcileTaskExecutor")
    public Executor reconcileTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：同时最多允许 5 个对象在进行比对（可根据你的服务器配置微调）
        executor.setCorePoolSize(5);
        // 最大线程数：遇到突发也最多只能跑 10 个
        executor.setMaxPoolSize(10);
        // 队列容量：如果点选了超过 10 个对象，剩下的全部放在队列里排队 (前端状态显示 WAITING)
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sf-recon-");
        // 拒绝策略：如果连队列100都塞满了，由调用者线程处理（防止任务丢失）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
