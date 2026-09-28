# Spring Boot 3.x 性能优化与主从读写分离接口测试文档

本文档提供完整的测试步骤与 cURL 调用命令，方便直接复制到 **Apifox** 中使用（Apifox 支持直接通过快捷键 `Ctrl + V` 粘贴 cURL 快捷创建接口）。

---

## 一、服务启动说明

在项目根目录 `f:\myProjects\exerciseErpJava` 下打开终端，执行以下命令启动 Spring Boot 服务：

```bash
mvn spring-boot:run
```

或者使用打包好的 jar 包启动：
```bash
java -jar target/exerciseErpJava-0.0.1-SNAPSHOT.jar
```

- 服务默认端口：`8080`
- 基础路径：`http://localhost:8080`
- **Knife4j 在线接口文档**：[http://localhost:8080/doc.html](http://localhost:8080/doc.html)
- **OpenAPI 3 JSON 规范地址**：[http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

> **说明**：服务启动、商品缓存、商品访问与刷新限流、下单限流都需要 Redis。商品缓存优化演示走 `GET /api/demo/products/{id}`；渠道普通访问走 `GET /api/demo/channel/products/{id}`；刷新走 `POST /api/demo/channel/products/{id}/refresh`，要求服务端环境变量 `MALL_REFRESH_TOKEN` 与请求头 `Authorization: Bearer <token>` 匹配。`/api/demo/orders/submit` 使用独立的 Redisson 限流，多个应用实例共享 10 秒内最多 2 次的额度。单机 Redis 的启动方式和应用参数见 [MALL_THREE_POINTS_CURL.md](MALL_THREE_POINTS_CURL.md)；主从读写分离场景则需启动配置中的主从节点。

---

## 二、Apifox 两种极速导入方法

### 方式 1：一键同步整个项目（推荐）
1. 启动服务后，打开 Apifox 项目，进入 **项目设置 -> 导入数据 -> URL 导入**；
2. 填入 OpenAPI 规范 URL：`http://localhost:8080/v3/api-docs`；
3. 点击 **确定导入**，Apifox 将自动解析并生成本项目所有的优化前、优化后及对比接口，包含完整参数、注释与数据模型！

### 方式 2：粘贴单条 cURL 导入
1. 在 Apifox 界面中，点击新建接口或快捷键 `Ctrl + N`；
2. 直接在 URL 地址栏按 `Ctrl + V` 粘贴本文档中的任意一条 `curl` 命令，Apifox 会自动解析为结构化请求。

---

## 三、场景一：订单详情查询性能优化测试（1000ms -> 300~400ms）

### 接口 1：【优化前】串行查询接口
- **接口说明**：按顺序依次调用客户（300ms）、商品（400ms）、物流（300ms）服务，总耗时累加约 1000ms。
- **请求方式**：`GET`
- **请求 URL**：`http://localhost:8080/api/order/before?orderId=1001`
- **cURL 命令**：
```bash
curl --location --request GET 'http://localhost:8080/api/order/before?orderId=1001'
```
- **预期响应数据（耗时 `queryCostTimeMs` 约为 1000ms）**：
```json
{
  "orderId": 1001,
  "orderSn": "ORD202609220001",
  "totalAmount": 1068.00,
  "orderStatus": "PAID",
  "createTime": "2026-09-21T15:00:00",
  "customerInfo": {
    "customerId": 8888,
    "customerName": "张三",
    "memberLevel": "VIP3",
    "phone": "13800001234"
  },
  "productItems": [
    {
      "skuId": 1001,
      "skuName": "智能降噪蓝牙耳机",
      "brandId": 501,
      "brandName": "Sony 索尼",
      "price": 999.00,
      "quantity": 1
    },
    {
      "skuId": 1002,
      "skuName": "原装耳机保护壳",
      "brandId": 501,
      "brandName": "Sony 索尼",
      "price": 69.00,
      "quantity": 1
    }
  ],
  "logisticsInfo": {
    "trackingNumber": "SF10928374829",
    "expressCompany": "顺丰速运",
    "shippingAddress": "北京市海淀区中关村南大街 1 号",
    "status": "运输中",
    "deliveryTime": "2026-09-22T10:00:00"
  },
  "queryCostTimeMs": 1018
}
```

---

### 接口 2：【优化后】并行查询接口
- **接口说明**：自定义隔离线程池 + `CompletableFuture` 并行组装，耗时取决于最慢子任务 max(300, 400, 300) 约 400ms。
- **请求方式**：`GET`
- **请求 URL**：`http://localhost:8080/api/order/after?orderId=1001`
- **cURL 命令**：
```bash
curl --location --request GET 'http://localhost:8080/api/order/after?orderId=1001'
```
- **预期响应数据（耗时 `queryCostTimeMs` 约为 400ms，性能提升超 60%）**：
```json
{
  "orderId": 1001,
  "orderSn": "ORD202609220001",
  "totalAmount": 1068.00,
  "orderStatus": "PAID",
  "createTime": "2026-09-21T15:00:00",
  "customerInfo": {
    "customerId": 8888,
    "customerName": "张三",
    "memberLevel": "VIP3",
    "phone": "13800001234"
  },
  "productItems": [
    {
      "skuId": 1001,
      "skuName": "智能降噪蓝牙耳机",
      "brandId": 501,
      "brandName": "Sony 索尼",
      "price": 999.00,
      "quantity": 1
    },
    {
      "skuId": 1002,
      "skuName": "原装耳机保护壳",
      "brandId": 501,
      "brandName": "Sony 索尼",
      "price": 69.00,
      "quantity": 1
    }
  ],
  "logisticsInfo": {
    "trackingNumber": "SF10928374829",
    "expressCompany": "顺丰速运",
    "shippingAddress": "北京市海淀区中关村南大街 1 号",
    "status": "运输中",
    "deliveryTime": "2026-09-22T10:00:00"
  },
  "queryCostTimeMs": 412
}
```

---

### 接口 3：【综合对比】一键基准耗时对比测试接口
- **接口说明**：同时执行优化前与优化后调用，自动计算两者的耗时差、降幅比例与性能提升倍数。
- **请求方式**：`GET`
- **请求 URL**：`http://localhost:8080/api/order/compare?orderId=1001`
- **cURL 命令**：
```bash
curl --location --request GET 'http://localhost:8080/api/order/compare?orderId=1001'
```
- **预期响应数据**：
```json
{
  "orderId": 1001,
  "beforeSerialCostMs": 1018,
  "afterParallelCostMs": 412,
  "performanceImprovement": "耗时降低约 59.5%, 性能提升约 2.47 倍",
  "summary": "接口耗时由约 1000ms 成功降低至约 412ms"
}
```

---

## 四、场景二：Redis 主从读写分离测试

### 接口 4：查询 Redis 读写分离拓扑策略信息
- **接口说明**：返回当前系统的读写分离配置、从库分流目标节点以及保护的高频字典数据列表。
- **请求方式**：`GET`
- **请求 URL**：`http://localhost:8080/api/cache/info`
- **cURL 命令**：
```bash
curl --location --request GET 'http://localhost:8080/api/cache/info'
```
- **预期响应数据**：
```json
{
  "strategy": "REPLICA_PREFERRED",
  "description": "读请求优先分流到从节点；写入和删除命令由主节点处理；从节点不可用时自动回退到主节点",
  "readRouting": "从节点（例如 127.0.0.1:6380、127.0.0.1:6381）",
  "writeRouting": "主节点（127.0.0.1:6379）",
  "protectedData": [
    "品牌详情缓存 (erp:brand:*)",
    "商品系列缓存 (erp:brand:series:*)"
  ]
}
```

---

### 接口 5：【优化前】常规单节点读取品牌缓存
- **接口说明**：读命令直接由主节点处理。
- **请求方式**：`GET`
- **请求 URL**：`http://localhost:8080/api/cache/before/get?brandId=501`
- **cURL 命令**：
```bash
curl --location --request GET 'http://localhost:8080/api/cache/before/get?brandId=501'
```
- **预期响应数据**：
```json
{
  "mode": "优化前：读写全部由主节点处理",
  "routedTo": "主节点（127.0.0.1:6379）",
  "brand": {
    "brandId": 501,
    "brandName": "Sony 索尼",
    "description": "索尼株式会社，视听与数码领军品牌",
    "logoUrl": "https://img.example.com/sony.png",
    "seriesList": [
      "WH-1000XM5 系列",
      "LinkBuds 系列",
      "PlayStation 系列"
    ]
  }
}
```

---

### 接口 6：【优化后】主从读写分离读请求（从节点分流）
- **接口说明**：基于 Lettuce `ReadFrom.REPLICA_PREFERRED`，读操作由从库（6380 / 6381）承载。
- **请求方式**：`GET`
- **请求 URL**：`http://localhost:8080/api/cache/after/get?brandId=501`
- **cURL 命令**：
```bash
curl --location --request GET 'http://localhost:8080/api/cache/after/get?brandId=501'
```
- **预期响应数据**：
```json
{
  "mode": "优化后：基于 Lettuce ReadFrom.REPLICA_PREFERRED 主从读写分离",
  "routedTo": "从节点（127.0.0.1:6380 / 6381）分担读请求",
  "brand": {
    "brandId": 501,
    "brandName": "Sony 索尼",
    "description": "索尼株式会社，视听与数码领军品牌",
    "logoUrl": "https://img.example.com/sony.png",
    "seriesList": [
      "WH-1000XM5 系列",
      "LinkBuds 系列",
      "PlayStation 系列"
    ]
  }
}
```

---

### 接口 7：【优化后】写操作与缓存失效（强制走主节点）
- **接口说明**：更新品牌数据并失效缓存，写命令由 Lettuce 自动识别并发往主节点（6379）。
- **请求方式**：`POST`
- **请求 URL**：`http://localhost:8080/api/cache/after/update`
- **请求头**：`Content-Type: application/json`
- **cURL 命令**：
```bash
curl --location --request POST 'http://localhost:8080/api/cache/after/update' \
--header 'Content-Type: application/json' \
--data-raw '{
  "brandId": 501,
  "brandName": "Sony 索尼 (最新更新版)",
  "description": "索尼最新产品线",
  "logoUrl": "https://img.example.com/sony_new.png",
  "seriesList": [
    "WH-1000XM5 系列",
    "LinkBuds 系列",
    "Alpha 单反微单系列"
  ]
}'
```
- **预期响应数据**：
```json
{
  "brandId": 501,
  "message": "品牌信息已更新，主节点上的缓存已安全失效",
  "writeRoutedTo": "主节点（127.0.0.1:6379）"
}
```

---

## 五、在 IDEA 或命令行中运行单元测试
除了通过 Apifox 调用 HTTP 接口外，工程还内置了自动化对比单元测试，会直接在控制台打印耗时对比和提升倍数：

```bash
mvn test
```

控制台将输出如下测试报告：
```text
[INFO] Running com.example.demo.OrderOptimizationTest
>>> 优化前-串行总耗时: 1018 ms
>>> 优化后-并行总耗时: 412 ms
========== 性能对比结果 ==========
串行耗时: 1018 ms -> 并行耗时: 412 ms
接口耗时降低: 59.53%, 性能提升: 2.47 倍
```





# redis 问题

> ⭐⭐**/api/cache/after/get 是怎么实现读写分离的？**
> 
>
> `/api/cache/after/get` 实现读写分离的核心在于：**Spring Boot 底层的 Redis 客户端 Lettuce 原生支持主从拓扑路由（Master-Replica Routing）**。
>
> 业务代码本身不需要做任何主从判断或手动切换数据源，全部由 **Lettuce 客户端在底层根据“命令类型”和“路由策略”自动分发**。
>
> 
>
> **整体实现分为以下三个层次：**
>
> ---
>
> ### 1. 配置层：声明主从拓扑与路由策略
>
> 在 [`RedisMasterSlaveConfig.java`](file:///f:/myProjects/exerciseErpJava/src/main/java/com/example/demo/config/RedisMasterSlaveConfig.java#L66-L78) 中，配置了多节点拓扑和 `ReadFrom` 策略：
>
> ```java
> // 1. 构建主从拓扑：1 个 Master + 2 个 Replica
> RedisStaticMasterReplicaConfiguration topology =
>         new RedisStaticMasterReplicaConfiguration(masterHost, masterPort); // 6379 (主)
> topology.addNode("127.0.0.1", 6380); // 从节点 1
> topology.addNode("127.0.0.1", 6381); // 从节点 2
> 
> // 2. 设置读写分离路由规则
> LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
>         .readFrom(ReadFrom.REPLICA_PREFERRED) // 核心策略：优先从从库读取
>         .build();
> 
> return new LettuceConnectionFactory(topology, clientConfig);
> ```
>
> > **`ReadFrom.REPLICA_PREFERRED` 的含义**：
> > * 优先选择从库（Replica）执行读操作；
> > * 当两个从库全部宕机或网络分区时，**自动优雅降级回退到 Master 执行读**，保证业务高可用，不会直接抛错。
>
> ---
>
> ### 2. 驱动层：Lettuce 客户端智能命令路由
>
> 当请求到达后，底层经历如下路由判定流程：
>
> ```
>                     客户端发起操作
>                           │
>             ┌─────────────┴─────────────┐
>             ▼                           ▼
>     写命令 (SET / DEL)           读命令 (GET / MGET)
>             │                           │
>             │                  检查 ReadFrom 策略
>             │             (ReadFrom.REPLICA_PREFERRED)
>             │                           │
>             ▼                    ┌──────┴──────┐
>        [强制路由]                ▼             ▼
>    Master (6379)         从库可用?          从库全部异常?
>                                 │               │
>                                 ▼               ▼
>                       Replica (6380 / 6381)   自动降级回退
>                          (轮询负载均衡)       Master (6379)
> ```
>
> 1. **执行读请求（GET）**：
>    当调用 `/api/cache/after/get` 时，执行 `redisTemplate.opsForValue().get(cacheKey)`。Lettuce 识别为读命令，根据策略将 TCP 数据包发送给 **6380 或 6381** 端口的从节点。
> 2. **执行写请求（SET / DEL）**：
>    当调用更新接口 `/api/cache/after/update` 时，执行 `redisTemplate.delete(...)` 或缓存回填 `set(...)`。Lettuce 识别为写命令，**强制直发 6379 主节点**，从库不接受任何写操作。
>
> ---
>
> ### 3. 业务层：完全透明，无侵入
>
> 查看 [`AfterRedisReadWriteService.java`](file:///f:/myProjects/exerciseErpJava/src/main/java/com/example/demo/service/cache/AfterRedisReadWriteService.java#L62)：
>
> ```java
> // 业务代码与普通单机 Redis 完全一致，完全无需感知主从
> Object cached = redisTemplate.opsForValue().get(cacheKey);
> ```
>
> 业务层依然直接注入标准的 `RedisTemplate<String, Object>`，**无需在代码里写 `@ReadOnlyConnection` 或做多数据源路由 AOP**，架构完全解耦。
>
> ---
>
> ### 总结
> `/api/cache/after/get` 之所以能读写分离，是因为 **Lettuce 驱动在建立连接时持有主从拓扑连接池，拦截到读命令后自动分流到从节点（6380/6381）执行**。配合 Cache-Aside 模式（写主库，由主库异步复制给从库失效），实现了读性能水平扩展和写数据强一致性。



> **Redisson是什么？**
>
> 
>
> **Redisson** 是一个 Java 的 Redis 客户端。它把 Redis 的能力封装成 Java 对象和接口，常用来实现分布式锁、限流器等功能。
>
> 在这个项目里，Redisson 用于**下单限流**：[OrderRateLimiter.java](F:/myProjects/exerciseErpJava/src/main/java/com/example/demo/service/OrderRateLimiter.java) 通过 `RRateLimiter` 限制同一用户 **10 秒内最多提交 2 次**。额度存在 Redis 中，因此多个应用实例会共享这一限制；超限时接口返回 HTTP 429。



> **limiter.trySetRate(RateType.OVERALL, 2, WINDOW, IDLE_EXPIRY); 这里的第1、4个参数的意思是什么？**
>
> * 第一个参数：
>
>   * `RateType.OVERALL` 表示：**同一个限流器的额度，由所有应用实例共同使用**。
>
>     例如用户 `42` 的 `submit` 限流键配置为 10 秒 2 次：请求即使分别到达应用实例 A 和 B，两台实例合起来也只能放行 2 次。
>
>     这里的“共同使用”是**针对同一个限流键**。用户 `43` 使用不同的键，有自己的额度。
>
> * 第四个参数：
>
>   * `IDLE_EXPIRY` 是这个限流器在 Redis 中的**闲置过期时间**。当前代码将它设为 `Duration.ofMinutes(1)`：某个用户和接口的限流器长期不用时，Redisson 会清理其 Redis 数据，避免访问过的用户越多、限流键越积越多。[Redisson 官方文档](https://redisson.pro/docs/data-and-services/objects/)
>
>     它与第三个参数 `WINDOW` 作用不同：**`WINDOW = 10 秒`决定请求额度怎么算；`IDLE_EXPIRY = 1 分钟`决定闲置的限流器何时清理。**清理后再次请求，会重新创建该用户的限流规则。
>
>     
>
> **意思是在1分钟内，一个用户都没访问的话，就把所有用户清除掉，是吗？**
>
> * 不是清除所有用户，而是**每个“用户 ID + 接口”的限流器分别过期**。
>
>   例如用户 42 的 `submit` 限流器闲置约 1 分钟，就清理这个限流器在 Redis 中的数据；用户 43 一直在访问，他的限流器仍保留。这里也不会删除用户账号或订单数据，只清理限流状态。
