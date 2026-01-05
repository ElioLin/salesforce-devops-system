package com.ruoyi.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync // 开启异步支持
public class SfThreadPoolConfig {

    @Bean(name = "sfTaskExecutor")
    public ThreadPoolTaskExecutor sfTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);   // 核心线程数
        executor.setMaxPoolSize(10);   // 最大线程数
        executor.setQueueCapacity(50); // 队列大小
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("SfReconcile-");
        // 拒绝策略：由调用者所在的线程执行（防止丢失任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}