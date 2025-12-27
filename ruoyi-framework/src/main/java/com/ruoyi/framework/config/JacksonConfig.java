package com.ruoyi.framework.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局配置：将 Long 类型序列化为 String
 * 解决前端 JS 精度丢失问题
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            // 把 Long 类型序列化为 String
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            // 把 long 基本类型也序列化为 String
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}