package com.example.demo.config;

import io.lettuce.core.ReadFrom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

/**
 * Redis 主从读写分离配置 (Lettuce + ReadFrom)
 *
 * 核心原理：
 * 1. Lettuce 客户端内置对主从/哨兵/集群拓扑的命令路由机制；
 * 2. 通过设置 ReadFrom.REPLICA_PREFERRED（优先从节点读）：
 *    - 所有读命令（GET, MGET, HGET, SMEMBERS 等）被自动路由至从节点分流；
 *    - 当所有从节点宕机或网络分区时，读请求自动回退到主节点，避免服务不可用；
 *    - 所有写命令（SET, DEL, HSET, EXPIRE 等）始终路由至主节点，保证数据一致性；
 * 3. 适用场景：高频读、低频写的基础缓存（如品牌、商品类目、系列列表、配置字典等）。
 */
@Configuration
public class RedisMasterSlaveConfig {
    private static final Logger log = LoggerFactory.getLogger(RedisMasterSlaveConfig.class);

    @Value("${demo.redis.master.host:127.0.0.1}")
    private String masterHost;

    @Value("${demo.redis.master.port:6379}")
    private int masterPort;

    /**
     * 方式一：适用于生产环境已有哨兵（Sentinel）或原生主从，
     * 通过 LettuceClientConfigurationBuilderCustomizer 注入 ReadFrom 策略。
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        return clientConfigurationBuilder -> {
            // 设置优先从从节点读取（如果从节点不可用，自动回退到主节点）
            clientConfigurationBuilder.readFrom(ReadFrom.REPLICA_PREFERRED);
            log.info("【Redis配置】已为 Lettuce 启用读写分离策略: ReadFrom.REPLICA_PREFERRED");
        };
    }

    /**
     * 方式二：静态主从多节点拓扑连接工厂（适用于未配置哨兵时的多节点主从实例演示）
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "demo.redis.read-write-split.enabled", havingValue = "true", matchIfMissing = false)
    public RedisConnectionFactory masterReplicaConnectionFactory() {
        log.info("【Redis 配置】正在初始化静态主从拓扑连接工厂，主节点：{}:{}", masterHost, masterPort);

        // 1. 构建主从拓扑：1 个主节点和 2 个从节点
        RedisStaticMasterReplicaConfiguration topology =
                new RedisStaticMasterReplicaConfiguration(masterHost, masterPort);

        // 模拟配置两个只读从节点
        topology.addNode("127.0.0.1", 6380);
        topology.addNode("127.0.0.1", 6381);


        /**
         * ReadFrom.REPLICA_PREFERRED 的含义：
         *
         * 优先选择从节点执行读操作；
         * 当两个从节点全部宕机或网络分区时，自动回退到主节点执行读操作。
         */

        // 2. 设置读写分离路由规则
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                // 核心路由规则：优先分流至从库
                .readFrom(ReadFrom.REPLICA_PREFERRED) //核心策略：优先从从库读取
                .build();

        return new LettuceConnectionFactory(topology, clientConfig);
    }

    /**
     * 通用 RedisTemplate 配置（采用 JSON 序列化，便于查看与兼容）
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
