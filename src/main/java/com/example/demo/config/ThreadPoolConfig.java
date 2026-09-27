package com.example.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.web.client.RestTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单详情并行聚合专用线程池配置
 * 生产实践原则：
 * 1. 业务隔离：严禁使用 ForkJoinPool.commonPool() 执行耗时 I/O 任务，防止耗尽公共线程导致系统雪崩；
 * 2. 线程有界：明确核心线程数、最大线程数、队列长度，严禁使用无界队列（如默认 LinkedBlockingQueue）；
 * 3. 命名规范：自定义 ThreadFactory，赋予明确的线程名前缀，方便 jstack 定位问题；
 * 4. 拒绝策略：根据业务兜底需求配置（如 CallerRunsPolicy 在过载时降级为调用方主线程串行执行，形成天然限流/背压）。
 */
@Configuration
public class ThreadPoolConfig {
    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    @Value("${order.thread-pool.core-size:8}")
    private int corePoolSize;

    @Value("${order.thread-pool.max-size:16}")
    private int maxPoolSize;

    @Value("${order.thread-pool.queue-capacity:200}")
    private int queueCapacity;

    @Value("${order.thread-pool.keep-alive-seconds:60}")
    private long keepAliveSeconds;

    @Value("${order.thread-pool.thread-name-prefix:order-detail-pool-}")
    private String threadNamePrefix;

    /**
     * 配置供远程 HTTP 调用的 RestTemplate
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * 声明核心业务执行线程池 ExecutorService
     */
    @Bean(name = "orderDetailThreadPool", destroyMethod = "shutdown")
    public ExecutorService orderDetailThreadPool() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(threadNamePrefix + index.getAndIncrement());
                // 设置为非守护线程，保证关键任务不会随主线程退出而突然中断
                thread.setDaemon(false);
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("线程池 [{}] 异常执行任务: {}", t.getName(), e.getMessage(), e));
                return thread;
            }
        };

        // 采用有界队列，容量超限时采用 CallerRunsPolicy，由调用方线程处理，起到背压保护作用
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        log.info("订单详情专用线程池初始化成功: coreSize={}, maxSize={}, queueCapacity={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }
}
