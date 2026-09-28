package com.example.demo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 3 在线接口文档配置
 * 访问地址：http://localhost:8080/doc.html
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring Boot 3.x 性能优化与架构实战 API")
                        .version("v1.0.0")
                        .description("本工程演示订单、Redis 与 C 端商城三组机制：\n\n" +
                                "1. **订单详情查询性能优化**：将多个服务串行调用改为自定义隔离线程池 + CompletableFuture 并行查询，耗时由约 1s 降至约 300~400ms。\n" +
                                "2. **Redis 主从读写分离**：基于 Spring Boot 3 + Lettuce 配置 ReadFrom.REPLICA_PREFERRED，读请求分流至从节点、写/删请求走主节点，缓解主节点读压力。\n" +
                                "3. **C 端商城三点机制**：商品详情互斥缓存、普通访问与刷新分流限流、Bearer 令牌保护刷新入口，以及下单 Redisson 限流。")
                        .contact(new Contact().name("架构优化实践组").email("arch@example.com"))
                        .license(new License().name("Apache 2.0")));
    }
}
