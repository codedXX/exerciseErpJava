# 商城三点机制：含义、实现和 curl 测试

本工程是 **独立的教学模拟**，不会启动或修改 `D:\Projects\ERP`、`D:\myProjects\ShopWeb`。参考关系：

- ERP 的 `sources/ERP.API/Apis/Ailai.Product/Controllers/ShopProductController.cs` 提供 `api/single-product/{productId}`，对应本例的 `ProductStore`（模拟主数据）。
- ShopWeb 的 `Ailai.ShopWeb/Controllers/ProductController.cs` 从 MongoDB 取商品详情，未命中时访问下游，并用进程内分段锁避免同一进程重复写入；其 `refresh=true` 分支先删商品文档再调下游刷新。本例模拟这些业务动作，并把跨实例协调放到 Redis。
- 截图三条是待学习的设计目标；不能据此推断两个参考仓库已经完整实现这些方案。截图中的“缓存命中率至少 90%”和“超限返回 429”也只是目标/行为，不是这里测出的生产指标。

## 启动

需要 JDK 21、Maven 3.9 和本机 Redis 7。商品缓存、下单限流和演示刷新接口都需要 Redis。可先启动 Docker Desktop，然后执行：

```powershell
docker run --name mall-demo-redis -p 6379:6379 -d redis:7
```

本仓库原有配置默认使用 6379 主库和 6380/6381 从库。本教程只用单机 Redis，所以必须覆盖原配置；刷新接口不需要启动令牌：

```powershell
mvn '-Dmaven.compiler.fork=true' spring-boot:run '-Dspring-boot.run.arguments=--demo.redis.read-write-split.enabled=false'
```

端口默认 `8080`。下面每个 `bash` 代码块都只有**一条标准 cURL 请求**，可分别复制到 Apifox 的「导入 cURL」中。启动 Redis 和应用的命令只是环境准备，不需要导入 Apifox。`X-Demo-User-Id` 是演示身份，不具备认证能力。

也可以打开 **Knife4j**：`http://localhost:8080/doc.html`，在「3.1 商品详情缓存」「3.2 下单限流」「3.3 商品强制刷新」三个分组中直接调试这些接口。强制刷新时在「请求头部」勾选 `X-Demo-Refresh` 这一行，值填 `true`；在「请求参数」填写商品 ID 和当前版本（首次通常为 `0`，直接填 `0` 即可）。普通查询和下单不用这个请求头。Apifox 仍可使用下方 cURL，或从 `http://localhost:8080/v3/api-docs` 导入 OpenAPI 文档。

## 1. 商品详情缓存互斥与二次检查

**含义**：PC、移动端、小程序访问商品 10200 时，缓存键只按商品 ID 建立，例如 `mall:product:detail:10200`。缓存失效后，所有实例竞争 `mall:product:lock:10200`；拿到锁的请求再次检查缓存，仍未命中才读 ERP 主数据并回填。未拿到锁的请求最多等待约 500ms；找不到的商品暂存 15 秒空值，防止大量请求反复查不存在的 ID。锁带 10 秒租期，并按持有者令牌解锁。

第一次查询：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200'
```

第二次查询（在 Apifox 中再次发送同一请求）：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200'
```

两次响应的 `cacheKey` 一样，`dbReadCount` 第二次不应增加。请求不存在的商品返回 `404`，短时间内重复请求不会反复增加 `dbReadCount`：

第一次查询不存在的商品：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/99999'
```

第二次查询不存在的商品：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/99999'
```

并发验证：先用另一个尚未访问过的正整数 ID 经 ERP 接口写入，再将下面的 GET 请求在 Apifox 中并发发送约 20 次；各响应的 `dbReadCount` 应接近一致。若竞争等待超过约 500ms，请求会返回 `503`，不会绕开锁打爆 ERP。

```bash
curl --location --request PUT 'http://localhost:8080/api/demo/erp/products/10300' --header 'Content-Type: application/json' --data-raw '{"name":"并发测试商品","priceCents":29900}'
```

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10300'
```

## 2. 下单接口：Redis 滑动窗口

**含义**：Redis 用 `mall:order:window:<用户ID>:submit` 保存最近 10 秒内成功放行的请求时间。每次下单在同一段 Lua 脚本中删除过期记录、计数并决定是否添加本次请求；这样并发请求和多个应用实例都共享同一额度。任意连续 10 秒最多放行 2 次；超限时不生成订单，返回 HTTP `429`、`Retry-After` 和 `retryAfterMs`。Redis 不可用时，下单不能成功。

连续执行三次（尽量在 10 秒内）：

第一次下单：

```bash
curl --location --request POST 'http://localhost:8080/api/demo/orders/submit' --header 'X-Demo-User-Id: 42'
```

第二次下单：

```bash
curl --location --request POST 'http://localhost:8080/api/demo/orders/submit' --header 'X-Demo-User-Id: 42'
```

第三次下单：

```bash
curl --location --request POST 'http://localhost:8080/api/demo/orders/submit' --header 'X-Demo-User-Id: 42'
```

前两次为 `200`，第三次为 `429`。另一个用户有自己的窗口：

```bash
curl --location --request POST 'http://localhost:8080/api/demo/orders/submit' --header 'X-Demo-User-Id: 43'
```

从第一次成功提交起约 10 秒后，用户 42 可以再次提交。生产代码必须从已认证的用户身份取得 ID，并把接口标识固定在服务端；这里请求头仅为教学输入。

## 3. 普通访问与演示刷新分流

**含义**：普通 `GET /products/{id}` 给商城用户读取；演示刷新 `POST /products/{id}/refresh` 要求请求头 `X-Demo-Refresh: true`。这只是本地功能开关，任何调用方都能自行添加，不构成身份认证。商品版本拒绝旧任务，商品互斥锁防止并发回源。刷新先从主数据构造新值，再用 Lua 原子替换商品缓存和递增版本。普通 GET 在刷新中可继续读旧缓存，不经历“先删掉、等所有人一起回源”的空窗。

### 按顺序执行的 cURL 测试

先按上面的“启动”步骤运行 Redis 和应用。若 8080 端口仍运行旧版程序，先重启以加载新代码。以下每个代码块都是一条可单独复制的 cURL 请求，适合导入 Apifox。在 Windows PowerShell 直接运行时，把命令开头的 `curl` 改为 `curl.exe`。

本例使用商品 `10200`。为观察“刷新前仍读旧缓存”，请在 **2 分钟缓存有效期内**完成下面步骤；若 Redis 中已有该商品的旧缓存或版本，请换一个未使用过的商品 ID，并将全部 URL 中的 `10200` 改成新 ID。刷新请求中的 `expectedVersion` 必须使用第 2 步实际查到的版本，下面的 `0` 仅是新商品的示例。

1. 先写入模拟 ERP 的旧商品主数据：

```bash
curl --location --request PUT 'http://localhost:8080/api/demo/erp/products/10200' --header 'Content-Type: application/json' --data-raw '{"name":"before","priceCents":19900}'
```

2. 查询当前缓存版本。新商品通常返回 `0`：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200/version'
```

3. 查询商品详情，将 `before` 写入缓存：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200'
```

4. 修改模拟 ERP 主数据。此操作不会主动删除商品缓存：

```bash
curl --location --request PUT 'http://localhost:8080/api/demo/erp/products/10200' --header 'Content-Type: application/json' --data-raw '{"name":"after","priceCents":20900}'
```

5. 再查详情，`product.name` 应仍为 `before`：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200'
```

6. 不带演示请求头强制刷新，预期 HTTP `401`，缓存不变：

```bash
curl --include --request POST 'http://localhost:8080/api/demo/products/10200/refresh?expectedVersion=0'
```

7. 请求头的值不是 `true` 时也应返回 HTTP `401`：

```bash
curl --include --request POST 'http://localhost:8080/api/demo/products/10200/refresh?expectedVersion=0' --header 'X-Demo-Refresh: false'
```

8. 带演示请求头刷新，预期 HTTP `200`，响应中的 `result` 为 `UPDATED`：

```bash
curl --include --request POST 'http://localhost:8080/api/demo/products/10200/refresh?expectedVersion=0' --header 'X-Demo-Refresh: true'
```

9. 再查详情，`product.name` 应变为 `after`：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200'
```

10. 再查版本，应比第 2 步增加 `1`：

```bash
curl --location --request GET 'http://localhost:8080/api/demo/products/10200/version'
```

11. 用第 2 步的旧版本重复刷新，预期 HTTP `409 CONFLICT`：

```bash
curl --include --request POST 'http://localhost:8080/api/demo/products/10200/refresh?expectedVersion=0' --header 'X-Demo-Refresh: true'
```

同一商品正在刷新且锁未释放时，另一个刷新请求会得到 `423 BUSY`；本例的内存主数据读取很快，手工连续点击不一定能复现。商品 GET 和强制刷新没有按请求次数配置独立限流，它们通过商品互斥锁限制并发回源；上文 Redis 滑动窗口限流只用于下单。

**边界**：这是单进程内存主数据加单机 Redis 的教学示例。它不接真实 MySQL/MongoDB，也不含真实登录、支付、订单持久化。`X-Demo-Refresh` 只是演示开关，生产环境应改用真实内部身份认证和访问审计。缓存互斥锁的租期必须大于最坏回源时间；实际部署还需监控、限流拒绝率及数据库与缓存的失败补偿。`dbReadCount` 是本进程计数，不能用于计算生产缓存命中率。







# 问题

明白，你问的是**这段设计里的商品详情模块**，不是仓库里另一个“下单限流”模块。我刚才把两者混在一起了。

按这段设计，接口和保护措施应这样对应：

| 接口                                            | 限流                                                     | 互斥锁                                     |
| ----------------------------------------------- | -------------------------------------------------------- | ------------------------------------------ |
| 普通访问 `GET /api/demo/products/{id}`          | 设置普通查询限流；缓存未命中时尤其要限制回源压力         | 缓存未命中、需要回源重建时，按商品 ID 加锁 |
| 强制刷新 `POST /api/demo/products/{id}/refresh` | 设置**独立且更严格**的刷新限流，并先校验调用方身份和权限 | 按商品 ID 加锁，防止同一商品并发刷新       |

两条路径应共用同一商品 ID 的锁，避免普通查询回源与强制刷新同时冲击下游。**当前仓库只实现了这两个接口的互斥锁，没有实现它们各自的请求限流；刷新入口的演示请求头也不构成鉴权。**



> 关键调用链就是：`product()` → `cache.get(id)` → 缓存未命中 → `tryLock(id)` → 持锁回源并回填缓存。



> 那 redis.opsForValue().setIfAbsent(             lockKey(id), owner, Duration.ofSeconds(10)         )到底是什么意思，你每个参数都解释一下
>
> 
>
> 这行代码的意思是：**尝试在 Redis 中写入一把锁；只有锁键还不存在时才写入，并让锁在 10 秒后过期。**
>
> ```
> redis.opsForValue().setIfAbsent(
>     lockKey(id),             // 第 1 个参数：Redis 键
>     owner,                   // 第 2 个参数：要写入的值
>     Duration.ofSeconds(10)   // 第 3 个参数：过期时间
> )
> ```
>
> 以 `id = 10200` 为例：
>
> | 参数                     | 实际内容                        | 作用                                   |
> | ------------------------ | ------------------------------- | -------------------------------------- |
> | `lockKey(id)`            | `mall:product:lock:10200`       | 指定要抢**哪个商品的锁**               |
> | `owner`                  | 本次生成的 UUID，例如 `a1b2...` | 标记**谁拿到了锁**，之后解锁时用于核对 |
> | `Duration.ofSeconds(10)` | 10 秒                           | 防止持锁程序崩溃后，锁一直不释放       |
>
> `setIfAbsent` 的意思是“**不存在才设置**”：如果这个锁键原本不存在，写入成功，返回 `true`；如果已经存在，保持原值不动，返回 `false`。因此，两个请求同时查询商品 `10200` 时，通常只有一个能拿到这把锁。

> 如果有两个用户执行tryLock，会发生什么？
>
> 要看两个请求传入的**商品 ID 是否相同**。
>
> 如果都执行 `tryLock(10200)`，它们会争抢同一个 Redis 键 `mall:product:lock:10200`。Redis 会依次处理这两个写入：一个请求成功，得到自己的 `owner` UUID；另一个发现键已存在，得到 `null`。在 `get()` 中，拿到锁的请求负责回源并写缓存，没拿到锁的请求则等待、重查缓存。
>
> 如果分别执行 `tryLock(10200)` 和 `tryLock(10300)`，锁键不同，两个请求都可以拿到锁，并行处理。
>
> 这里锁是**按商品 ID** 区分的，不是按用户区分的。锁正常释放后，或 10 秒过期后，后续请求又可以获取它。
