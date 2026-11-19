# 酒店预订系统

项目目录、服务职责、端口、数据归属与核心链路示意图见 [项目总结构与架构示意图](ARCHITECTURE.md)。

项目使用 Maven 多模块构建。网关和五个业务服务各自打包为可执行 Spring Boot JAR。

| 端口 | 模块 | 职责 |
| --- | --- | --- |
| 13307 | `gateway` | 网站静态资源和原有 `/hotel/**` API 入口 |
| 13308 | `account-service` | 注册、登录、JWT 签发、个人资料和密码 |
| 13309 | `catalog-service` | Elasticsearch 酒店搜索、房型查询、房型库存及订单库存占用，向订单服务提供房型快照 |
| 13310 | `order-service` | 下单、支付、入住、取消和退款，向会员权益服务提供订单消费额 |
| 13311 | `engagement-service` | VIP 等级、消费统计与每周福利 |
| 13312 | `pet-service` | 灵宠孵化、抽奖、重新孵化、培养及培养钱包 |

`service-support` 提供共用的 JWT 验证和 Feign 超时配置。业务服务默认绑定 `127.0.0.1`。网关保留前端原有请求路径，各 `/internal/**` 接口由业务服务使用，接收当前用户的 Bearer JWT。灵宠服务保留扣余额与培养进度的本地事务。会员权益通过 OpenFeign 从订单服务和灵宠服务读取当前用户消费额，计算 VIP 等级。VIP 免费券的发放和抽奖扣券共用 `vip_wallet` 表，通过数据库行锁和原子更新协调。

## 服务间调用

业务远程调用使用 Spring Cloud OpenFeign。Spring Boot 3.0.2、Spring Cloud 2022.0.0 和 Spring Cloud Alibaba 2022.0.0.0 按官方配套版本统一管理，以兼容 Sentinel 的 Feign 适配器。版本对应关系见 [Spring Cloud Alibaba 官方说明](https://sca.aliyun.com/en/docs/2022/overview/version-explain/)，客户端配置见 [OpenFeign 官方文档](https://docs.spring.io/spring-cloud-openfeign/docs/4.0.0/reference/html/)。

| 调用方 | Feign 接口 | 被调用方及接口 |
| --- | --- | --- |
| 订单 | CatalogClient | 酒店目录 GET /internal/catalog/rooms/{id} |
| 订单 | InventoryClient | 酒店目录 /internal/inventory，查询、占用、释放与迁移库存 |
| 会员权益 | OrderSpendingClient | 订单 GET /internal/orders/spending |
| 会员权益 | PetSpendingClient | 灵宠 GET /internal/pet/spending |

Feign 通过接口上的服务名从 Nacos 发现实例，由 Spring Cloud LoadBalancer 选择实例，使用当前用户的 Bearer JWT 调用内部接口。连接超时 2 秒，读取超时 4 秒，关闭自动重试和重定向。目录返回房型不存在时，下单接口返回 404。依赖服务不可用或消费统计响应为空时，返回 503。网关从 Nacos 查找后端实例，通过 HTTP 反向代理转发浏览器请求。

## Nacos 注册与发现

六个应用均接入 Spring Cloud Alibaba Nacos Discovery，使用各自的 spring.application.name 注册。默认命名空间为 public，分组为 DEFAULT_GROUP。

| 注册服务名 | 默认端口 |
| --- | --- |
| gateway | 13307 |
| account-service | 13308 |
| catalog-service | 13309 |
| order-service | 13310 |
| engagement-service | 13311 |
| pet-service | 13312 |

先启动 Nacos，再启动业务应用。本机现有 Docker 容器可使用 `docker start nacos` 启动，控制台地址为 [http://localhost:8848/nacos](http://localhost:8848/nacos)。本机容器使用 MySQL 的 nacos 库，连接地址已修复为 host.docker.internal:13306，并使用官方 2.1.0 脚本初始化该空库。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| NACOS_SERVER_ADDR | 127.0.0.1:8848 | Nacos 地址 |
| NACOS_NAMESPACE | 空值，即 public | 命名空间 ID |
| NACOS_GROUP | DEFAULT_GROUP | 服务分组 |
| NACOS_USERNAME | nacos | Nacos 用户名 |
| NACOS_PASSWORD | nacos | Nacos 密码 |
| NACOS_DISCOVERY_IP | 127.0.0.1 | 注册给调用方使用的服务 IP |

六个应用需要使用相同的 Nacos 地址、命名空间和分组。Nacos 2.x 客户端还需要连接服务端 HTTP 端口加 1000 的 gRPC 端口，本机为 9848。已有容器映射了 8848、9848、9849。跨机器部署时，将 NACOS_DISCOVERY_IP 设置为其他服务可达的本机地址，并将 HOTEL_SERVICE_BIND_ADDRESS 设置为实际监听地址。

依赖版本使用 Spring Cloud Alibaba 2022.0.0.0 BOM。接入方式见 [Nacos Discovery 官方文档](https://sca.aliyun.com/en/docs/2022/user-guide/nacos/quick-start/)，版本对应关系见 [官方版本说明](https://sca.aliyun.com/en/docs/2022/overview/version-explain/)。

## 限流与熔断

预约入口 `POST /hotel/orders` 使用 Sentinel 资源 `reservation:create`，默认每实例 5 QPS，超限返回 429、中文提示和 `Retry-After: 1`。订单到酒店目录、会员权益到订单及灵宠的三个 Feign 调用已配置降级工厂。默认以异常比例熔断，10 秒统计窗口内至少 5 次调用且异常比例达到 50% 时，熔断 10 秒，之后通过半开探测恢复。依赖故障、超时或熔断时返回 503，消费统计降级会终止 VIP 发券。酒店房型不存在的 404 作为业务结果处理。

规则源文件保存在 `sentinel/`。Nacos 中选择 public 命名空间、`SENTINEL_GROUP` 配置分组，可以看到以下三个 JSON 配置。

| Data ID | 规则类型 |
| --- | --- |
| order-service-flow-rules.json | 预约入口限流 |
| order-service-degrade-rules.json | 酒店目录调用熔断 |
| engagement-service-degrade-rules.json | 订单、灵宠消费统计调用熔断 |

应用监听 Nacos 配置，规则修改后动态生效，重启后重新加载。先确认 Nacos 就绪，再运行 `./publish-sentinel-rules.ps1` 导入规则。默认保留已有配置，显式使用 `-Replace` 将仓库中的三份规则更新到 Nacos。`start-services.ps1` 在启动服务前会执行首次导入。IDEA 启动时也需要先导入规则，并在修改 Maven 依赖后点击 Reload All Maven Projects。配置分组可用 `SENTINEL_NACOS_GROUP` 覆盖，命名空间及认证使用现有 `NACOS_*` 环境变量。持久化规则通过 Nacos 配置列表发布。集成方式见 [Sentinel 官方接入文档](https://sca.aliyun.com/en/docs/2022/user-guide/sentinel/advanced-guide/)。

## Elasticsearch 酒店搜索

「酒店管理」和「办理酒店业务」页面通过 `GET /hotel/search` 调用目录服务的 Elasticsearch 搜索，按页加载结果。支持酒店名称、地址的中文关键词与英文大小写无关查询，多关键词以空格分隔；纯数字精确匹配酒店 ID 或完整电话，`#12` 只查酒店 ID。默认每页 10 条，最大 50 条。关键词最多 80 个字符、10 个词，分页窗口最多 10000 条，超过后需要缩小条件。旧的 `/hotel/searchall`、`/hotel/searchbyid`、`/hotel/searchbyphone` 接口继续兼容。

```text
GET /hotel/search?keyword=上海&page=0&size=10
GET /hotel/search?keyword=%2312&page=0&size=10
```

接口要求现有 Bearer JWT，返回 `items`、匹配总数 `total`、从 0 开始的 `page`、`size` 和诊断字段 `source`。`source` 为 `elasticsearch` 或 `database`。目录服务使用 Spring Boot 3.0.2 管理的官方 Elasticsearch REST Client 8.5.3，服务端使用相同版本。名称、地址使用 IK 分词：建索引用 `ik_max_word`，查询用 `ik_smart`，查询分词以 AND 组合。keyword 子字段补充单字和包含查询，电话与 ID 保留精确字段。映射保存在 `catalog-service/src/main/resources/search/hotel-index.json`。配置方式见 [IK 官方文档](https://github.com/infinilabs/analysis-ik)。

`elasticsearch/Dockerfile` 将匹配 ES 的 `analysis-ik 8.5.3` 插件安装到 `hotel-elasticsearch-ik:8.5.3` 镜像，下载包通过固定 SHA-256 校验，容器重建后仍具备 IK。首次部署或更新 Dockerfile 时先构建镜像：

```powershell
docker compose -f elasticsearch/compose.yml build elasticsearch
docker compose -f seata/compose.yml -f rabbitmq/compose.yml -f elasticsearch/compose.yml up -d
```

IK 必须与 ES 版本一致；使用外部集群时需在所有相关节点安装对应版本插件并重启。可通过 `GET /_nodes/plugins` 查看 `analysis-ik`，通过 `POST /_analyze` 传入 `{"analyzer":"ik_smart","text":"南京市长江大桥"}` 验证分词。

索引模式已升级为 `hotel_schema=2`。即使酒店数据未变，目录服务也会将原 CJK 索引重新构建为 IK 索引，再原子切换原查询别名。客户端继续使用同一个别名。搜索结果通过对比 ES 文档字段与当前 MySQL 资料检测陈旧数据，避免把 IK 合理的非连续分词匹配误作无效结果，例如“南京市长江大桥”可以命中“南京市的长江大桥酒店”。

MySQL 是酒店数据来源。目录服务启动后在后台同步，默认每 30 秒读取一次一致快照并计算指纹；数据不变时复用已有索引。多个实例通过 Redis/Redisson 锁协调同一别名的重建。新快照先批量写入独立索引，刷新并校验数量后，原子切换查询别名，最后清理本项目已无别名使用的旧索引。写入或切换失败时保留原索引，下一周期重试。别名机制见 [Elasticsearch 官方说明](https://www.elastic.co/guide/en/elasticsearch/reference/8.5/indices-aliases.html)。

当前项目的酒店接口为只读接口，酒店资料可通过 SQL 维护，新增、修改和删除会在下一次同步后反映到关键词检索中。搜索先由 ES 找到酒店 ID，再从 MySQL 按这些 ID 读取当前展示资料；发现酒店已删除或索引中的资料已变化时，改用数据库重新查询。索引尚未就绪、ES 不可用或搜索返回不完整结果时，也自动回退数据库的关键词包含查询，ES 恢复后自动恢复 IK 检索。Redis 不可用时暂停重建，已存在的 ES 索引仍可查询。房型、价格和库存保持现有 MySQL/Redis 流程，下单快照直接查询数据库。

```powershell
docker compose -f seata/compose.yml -f rabbitmq/compose.yml -f elasticsearch/compose.yml up -d
```

本机 ES 地址为 [http://localhost:13317](http://localhost:13317)，健康状态可在 [集群健康接口](http://localhost:13317/_cluster/health) 查看。此 Compose 为本机演示配置，单节点、512 MB 堆内存、仅绑定本地端口，索引数据保存在命名卷中。默认查询别名为 `hotel-catalog-v1`，应用使用的物理索引前缀为 `hotel-catalog-v1-g-`。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| ELASTICSEARCH_URIS | http://127.0.0.1:13317 | ES HTTP 地址，多个地址用逗号分隔 |
| ELASTICSEARCH_PORT | 13317 | Docker 暴露端口，更改时同步调整 URIS |
| ELASTICSEARCH_USERNAME | 空 | 外部 ES 的认证用户名 |
| ELASTICSEARCH_PASSWORD | 空 | 外部 ES 的认证密码 |
| HOTEL_SEARCH_ENABLED | true | 启用 ES 检索和后台建索引，false 时使用数据库 |
| HOTEL_SEARCH_ALIAS | hotel-catalog-v1 | 搜索别名，同一数据集的实例必须一致；不同环境使用不同别名 |
| HOTEL_SEARCH_SYNC_INTERVAL_MS | 30000 | 数据快照检查间隔，毫秒 |
| HOTEL_SEARCH_LOCK_PREFIX | hotel:search:index-lock: | 索引重建锁前缀，同一数据集保持一致 |

## Redis 热点缓存与房间库存

兼容接口的酒店列表、酒店详情、电话查询，以及房型详情和房型列表使用 Redis Cache-Aside 缓存。默认 JSON 缓存 300 秒，附加 0～30 秒随机过期时间，空结果缓存 30 秒。同一实例内对同一热点键合并回源请求，缓存内容损坏或 Redis 不可用时读取数据库。下单使用目录服务直接从数据库生成的房型快照，订单金额采用下单时的数据库价格。

| 环境变量 | 默认值 |
| --- | --- |
| REDIS_HOST | 127.0.0.1 |
| REDIS_PORT | 6379 |
| REDIS_PASSWORD | 空 |
| REDIS_DATABASE | 0 |
| HOTEL_CACHE_PREFIX | hotel:catalog:v1: |
| HOTEL_CACHE_TTL_SECONDS | 300 |

本机 Redis 在 Ubuntu WSL 中运行，可以用 `wsl -d Ubuntu -- redis-cli ping` 检查。目录数据直接通过 SQL 修改后，会在缓存过期后刷新。需要立即刷新时，可用 Redis 的 SCAN 查找本项目缓存前缀并删除对应键。缓存访问使用 Spring Data Redis，参考 [官方文档](https://docs.spring.io/spring-data/redis/docs/3.0.1/reference/html/)。

按确认的演示设置，每个房型的初始可售总数为 10 间，以房型为单位共享库存池。目录服务维护 `room_inventory` 总数与占用数，以及 `room_reservation` 的每笔订单占用记录。创建订单时，通过 `reserved_count < total_count` 条件更新占用数，并通过 Seata 与订单写入组成全局事务。库存不足返回 409。前端显示当前可售数量，售罄时禁用购买按钮。库存查询接口为 `GET /hotel/orders/inventory?roomTypeId=房型ID`，库存读取以数据库为准。锁定机制参考 [MySQL 官方说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)。

下单请求支持可选 UUID `requestId`，前端在重试时复用该编号，同一用户和编号只创建一笔订单、占用一间房。取消未付款订单、完成退款及退房到期会释放库存，付款保留已有占用，申请退款期间保留占用。到期回补每 30 秒执行一次，下单与库存查询也会清理对应房型的已结束入住。未付款订单默认 30 分钟超时，由 RabbitMQ 延迟消息触发取消并释放库存。

启动会补齐旧订单字段，并在全局事务中将旧订单的有效占用导入目录服务，通过订单主键保证重复启动不重复扣库存。历史占用数超过 10 时，可售数显示为 0，释放到总数以内后才允许继续下单。新房型首次查询库存或下单时初始化库存。目录服务的 `HOTEL_INITIAL_ROOM_STOCK` 可设置首次初始化总数，已有库存总数保持持久化值，订单服务的 `HOTEL_INVENTORY_RELEASE_INTERVAL_MS` 可设置到期清理间隔。

库存扣减使用 Redisson 分布式锁，按房型串行处理，默认锁键为 `hotel:inventory:lock:房型ID`。多个目录服务实例需要使用相同的 Redis 地址、数据库和锁前缀。锁与热点缓存分别使用独立前缀，不同房型可以并发预订。Redisson 复用现有 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE` 配置，并延迟建立锁客户端连接。

扣减先获取分布式锁，再使用 `TransactionTemplate` 开启独立的本地 AT 分支事务，在该分支提交或回滚完成后释放锁。默认最多等待 1 秒，使用看门狗自动续期，默认租期为 30 秒。释放时由 Redisson 原子检查当前线程的锁所有权，未获取锁的请求不会执行解锁。实例退出后停止续期，锁会到期释放。实现机制见 [Redisson 官方文档](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/)。

锁竞争超时、Redis 不可用或锁释放异常时返回 503，由订单服务触发 Seata 全局回滚。缓存查询仍可在 Redis 不可用时读取数据库。分布式锁保护本地扣减分支执行区间，Seata 全局锁和 `undo_log` 继续协调全局提交与回滚，数据库条件更新继续约束库存上限。客户端重试沿用原 `requestId`。

| 库存锁环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| HOTEL_INVENTORY_LOCK_PREFIX | hotel:inventory:lock: | 房型锁前缀，同一库存库的目录实例必须一致 |
| HOTEL_INVENTORY_LOCK_WAIT_MS | 1000 | 抢锁等待毫秒数，应小于 Feign 的 4 秒读取超时并给数据库操作留出时间 |
| HOTEL_INVENTORY_LOCK_WATCHDOG_MS | 30000 | 看门狗租期毫秒数，运行中的锁自动续期 |

## Seata 分布式事务

订单服务作为 TM，订单库和目录库存库作为 RM，使用 Seata 1.7.0 AT 模式。下单先写订单，再由 OpenFeign 调用目录服务占用库存；Feign 传递 `TX_XID`，两个数据源自动代理并分别记录 `undo_log`。任一分支抛出异常或调用超时，会触发全局回滚。取消、完成退款和退房回补也通过同一全局事务入口协调订单状态和库存。预约限流及房型读取降级保留原有配置，库存写入失败会向上抛出，触发回滚。接入方式见 [Seata 官方文档](https://sca.aliyun.com/en/docs/2022/user-guide/seata/quick-start/)。

`BookingTransactions` 提供独立的代理入口，避免同类方法调用绕过全局事务。库存写接口要求短期服务 JWT 和全局事务上下文；网关会过滤外部传入的 `TX_XID`。订单请求仍使用 `requestId` 去重，目录服务另以订单 ID 保证占用与释放幂等。支付使用全局锁检查，避免与回滚中的订单写入冲突。

先启动 Nacos，再在项目根目录执行：

```powershell
docker compose -f seata/compose.yml up -d
```

控制台为 [http://localhost:13313](http://localhost:13313)，演示账号和密码均为 `seata`。事务 RPC 端口为 `13314`，本机映射到 `127.0.0.2`，以适配 Seata 1.7 对注册地址的检查。Nacos 服务列表切换到 `SEATA_GROUP`，可看到 `seata-server`。六个业务应用继续使用 `DEFAULT_GROUP`。客户端事务分组为 `hotel_tx_group`，映射到 TC 的 `default` 集群。

演示 TC 使用 file 存储，事务日志保存在 Docker 命名卷中，重启容器后保留。订单库、目录库的 `undo_log` 在应用启动时自动创建。应用升级时先停止旧订单和目录进程，启动 TC 和新版目录服务，再启动新版订单服务，最后恢复入口流量。分库迁移时，将旧 `room_inventory` 的全部行复制到目录库，保留订单库的旧表供容量读取；启动迁移会补齐有效订单的占用明细。迁移完成后由目录服务统一写库存。旧订单服务必须停止，避免继续写旧库存表。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| SEATA_NACOS_GROUP | SEATA_GROUP | TC 注册分组，服务端和客户端一致 |
| SEATA_ADVERTISE_IP | 127.0.0.2 | TC 对客户端公布的地址，也是 Docker RPC 绑定地址 |
| SEATA_CONSOLE_PORT | 13313 | TC 控制台端口 |
| SEATA_PORT | 13314 | TC RPC 端口 |
| NACOS_CONTAINER_ADDR | host.docker.internal:8848 | TC 容器访问 Nacos 的地址 |

跨机器部署时，设置 `SEATA_ADVERTISE_IP` 为所有业务服务可达的宿主机 IP，并配置对应的 Nacos 地址和访问控制。此 Compose 为单机演示部署，生产部署需要单独配置 TC 高可用和持久化存储。

## RabbitMQ 下单通知与超时取消

订单服务在创建订单、占用库存的同一个本地事务中写入两条 `order_outbox` 记录，分别用于下单通知和支付超时检查。投递器通过 Seata `@GlobalLock` 保护的 `SELECT ... FOR UPDATE` 领取消息，等待 AT 全局提交或回滚完成后才允许发送，避免发布仍可能全局回滚的订单。全局回滚会同时撤销订单和待发消息。默认每秒投递一次，等待 RabbitMQ 发布确认并检查不可路由退回，确认后标记 `SENT`。发送失败延迟重试，进程异常退出后通过领取租约恢复。消息为至少一次投递，固定的 `eventId` 用于消费者去重。确认机制见 [Spring AMQP 官方文档](https://docs.spring.io/spring-amqp/docs/3.0.0/reference/html/)。

下单通知发送到 `hotel.order.created` 队列，包含 `eventId`、虚拟手机号 `phone`、订单号 `orderId`、状态、支付截止时间，以及 `stay` 中的酒店名称、地址、电话、房型、人数、一晚入住规则。虚拟号码以 `1990000` 开头，`virtualPhone` 为 `true`。当前流程在付款时安排具体入住时间和房号，因此下单通知中的 `checkInTime`、`checkOutTime`、`roomNumber` 为 `null`，`checkInRule` 说明付款后的入住安排。通知队列保留消息供短信或通知消费者接入，本项目此处只发送 MQ 消息。

未付款订单保存 `payment_deadline`，默认创建后 30 分钟到期。超时消息进入 TTL 延迟队列，到期经死信交换机转到取消队列，使用 RabbitMQ 自带功能。延迟消息按剩余支付时间设置 TTL，积压补发时已经到期的消息直接进入取消队列。队列使用持久化 quorum 类型，死信转发采用至少一次策略。机制见 [RabbitMQ TTL](https://www.rabbitmq.com/docs/ttl) 和 [死信转发文档](https://www.rabbitmq.com/docs/quorum-queues#dead-lettering)。

| 队列 / 交换机 | 默认名称 | 用途 |
| --- | --- | --- |
| 事件交换机 | hotel.order.events | 下单与超时消息路由 |
| 下单通知队列 | hotel.order.created | 手机号、订单号、入住信息 |
| 延迟队列 | hotel.order.timeout.delay.1800000 | 无消费者，等待 TTL 到期；后缀随超时配置变化 |
| 超时取消队列 | hotel.order.timeout.cancel | 消费后通过 Seata 取消未付款订单并释放库存 |
| 超时失败队列 | hotel.order.timeout.failed | 消费重试仍失败的消息，供排查与重放 |

消费者读取数据库当前订单状态和截止时间。已付款、已取消、已退款或不存在的订单直接完成检查，重复消息不会重复释放库存。提前投递的消息按剩余时间重新延迟。付款接口也校验截止时间，防止消息积压时超期付款。取消时库存释放失败会全局回滚并重新消费，最多重试 3 次后进入失败队列；后台每分钟补偿检查超过截止时间 60 秒仍未付款的订单。MQ 不可用时，订单主流程仍可提交，消息保存在数据库中，消费者在订单迁移完成后于后台启动和重连。

首次升级时，旧的未付款订单补上一个完整支付窗口并补发超时检查消息，后续重启保留原截止时间。修改支付超时配置影响新订单，已有订单以数据库保存的截止时间为准。多个订单实例需要使用相同的 RabbitMQ 地址、虚拟主机、队列前缀和超时配置。

```powershell
docker compose -f seata/compose.yml -f rabbitmq/compose.yml up -d
```

RabbitMQ AMQP 端口为 `13315`，管理页面为 [http://localhost:13316](http://localhost:13316)，演示账号 `hotel`、密码 `hotel-demo`。在 Queues 页面选择 `hotel.order.created` 并使用 Get messages，可以查看通知正文；重新入队选项可保留消息。RabbitMQ 数据保存在命名卷中。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| RABBITMQ_HOST | 127.0.0.1 | RabbitMQ 地址 |
| RABBITMQ_PORT | 13315 | AMQP 端口 |
| RABBITMQ_MANAGEMENT_PORT | 13316 | Docker 管理页面端口 |
| RABBITMQ_USERNAME | hotel | 应用与容器初始化账号 |
| RABBITMQ_PASSWORD | hotel-demo | 应用与容器初始化密码 |
| RABBITMQ_VHOST | / | 虚拟主机 |
| HOTEL_ORDER_PAYMENT_TIMEOUT_MS | 1800000 | 新订单支付窗口，毫秒，至少 1000 |
| HOTEL_ORDER_MQ_PREFIX | hotel.order | 交换机与队列前缀，同一环境保持一致 |
| HOTEL_ORDER_OUTBOX_INTERVAL_MS | 1000 | 待发消息投递间隔 |
| HOTEL_ORDER_TIMEOUT_RECOVERY_INTERVAL_MS | 60000 | 逾期订单补偿检查间隔 |

## 本地启动

需要 JDK 17、Maven、MySQL、Redis、Nacos、Seata、RabbitMQ 和 Elasticsearch。已有安装默认连接 `127.0.0.1:13306/hotel_db`，如需切换地址可设置 `HOTEL_DB_URL`。新环境先创建数据库，启动时各服务会初始化自己的表。目录里的酒店和房型需要从旧库导入，或在新环境录入。数据库账号需要对所用表具备读写与初始化所需的 DDL 权限。

在项目根目录用 PowerShell 执行以下命令。复用旧系统的 `JWT_SECRET` 可以继续验证已签发且尚未过期的令牌。新环境可用至少 32 字节的随机值经 Base64 编码作为密钥。

```powershell
$env:JWT_SECRET = '<现有或新生成的 Base64 密钥>'
$env:HOTEL_DB_USER = 'root'
$env:HOTEL_DB_PASSWORD = '<MySQL 密码>'
docker start nacos
docker compose -f seata/compose.yml -f rabbitmq/compose.yml -f elasticsearch/compose.yml up -d
mvn verify
.\start-services.ps1
```

浏览器访问 [http://localhost:13307](http://localhost:13307)。启动前若旧单体仍监听 13307，请先关闭旧进程。启动脚本检查 Seata 注册与 RPC 连接，等待目录服务注册后再启动订单服务，将日志写入 `target/service-logs`，按 Ctrl+C 结束本次启动的服务。订单服务完成旧库存迁移前返回 503。也可以用 `java -jar <模块>/target/<模块>-0.0.1-SNAPSHOT.jar` 单独启动某个服务。

在 IDEA 中运行时，分别在 `AccountApplication`、`CatalogApplication`、`OrderApplication`、`EngagementApplication` 和 `PetApplication` 的运行配置中设置 `JWT_SECRET` 和 `HOTEL_DB_PASSWORD`。五个业务服务应使用相同的 JWT 密钥。旧单体运行配置中的环境变量只作用于旧配置，新的启动类需要各自配置。网关可以直接启动。缺少密钥会报 `Could not resolve placeholder 'JWT_SECRET'`，数据库密码为空会报 `Access denied ... (using password: NO)`。本机的五个业务运行配置已补齐这些环境变量，保存在 Git 忽略的 `.idea/workspace.xml` 中。

## 数据库和部署配置

默认复用旧库中的账号、目录、订单及会员权益数据。灵宠与会员权益当前共用 VIP 券余额表。需要分库时，先备份，再把表连同原有主键和历史行迁到对应数据库，配置下表中的 JDBC URL。其他服务通过用户 ID 关联各自的业务数据。

| 服务 | JDBC URL 覆盖项 | 所属表 |
| --- | --- | --- |
| 账号 | `HOTEL_ACCOUNT_DB_URL` | `user` |
| 目录 | `HOTEL_CATALOG_DB_URL` | `hotel`、`room_type`、`room_inventory`、`room_reservation`、`undo_log` |
| 订单 | `HOTEL_ORDER_DB_URL` | `stay_order`、`order_outbox`、`undo_log` |
| 会员权益 | `HOTEL_ENGAGEMENT_DB_URL` | `vip_week_claim`、共享 `vip_wallet` |
| 灵宠 | `HOTEL_PET_DB_URL` | `spirit_pet`、`spirit_pet_draw`、`account_wallet`、`account_transaction`、共享 `vip_wallet` |

本次按服务进程拆分，灵宠和会员权益应连接同一个 MySQL 库，以便共用免费券余额。`HOTEL_PET_DB_URL` 默认继承 `HOTEL_ENGAGEMENT_DB_URL`。两个服务连接独立数据库时，免费券发放和扣券将无法同步。现有灵宠、培养和钱包历史数据会直接复用。升级时先停止旧会员权益进程，再执行 `mvn clean verify`，随后启动六个应用。

独立进程可分别设置 `HOTEL_DB_USER` 和 `HOTEL_DB_PASSWORD`。端口可通过 `HOTEL_GATEWAY_PORT`、`HOTEL_ACCOUNT_PORT`、`HOTEL_CATALOG_PORT`、`HOTEL_ORDER_PORT`、`HOTEL_ENGAGEMENT_PORT`、`HOTEL_PET_PORT` 覆盖。跨机器运行时，设置监听地址和 NACOS_DISCOVERY_IP，通过 Nacos 注册实际地址及端口。Feign 与网关使用注册服务名寻址。

## 测试

```powershell
mvn verify
node --test gateway/src/test/js/pet-effects.test.cjs gateway/src/test/js/hotel-search.test.cjs
```

酒店搜索集成测试使用真实 ES、两个目录实例及网关，验证 IK 插件和字段映射、非连续中文分词命中、英文、多词、精确电话/ID、分页、数据库增删改同步、原子切换失败后的旧索引保留、ES 故障回退及自动恢复。测试会创建并清理临时 MySQL、Redis 容器及带随机前缀的 ES 索引，通过 HTTP 代理模拟故障。需要本机 Nacos、Seata 和已加载 IK 的 ES 已启动，JDK 17、Python 3 和空闲的 24307、24309、24319、24317 端口。

```powershell
py -3 elasticsearch/verify-integration.py
```

Seata 集成回归使用真实 TC、两个独立 MySQL 库、两个订单实例、两个目录实例和一个故障注入代理，检查扣库存后响应失败、超时、订单连接中断、取消与退款回滚、旧库存迁移、重复启动及并发防超卖。还检查锁竞争时不扣库存、不误解锁，其他房型仍可预订，以及 Redis 中断时拒绝扣减、恢复后使用原请求编号重试。先启动本机 Nacos、Seata，构建 JAR，并设置 `JAVA_HOME` 为 JDK 17。脚本使用 Python 3 标准库，自动创建并清理临时 MySQL、Redis、RabbitMQ 容器和 Nacos 测试规则，需要空闲的 23309、23310、23319、23320、23329、23330 端口。

```powershell
py -3 seata/verify-integration.py
# 真实 MQ 通知、5 秒超时、全局未决事务禁止发布、库存失败重试、已付款保护与 MQ 故障恢复
py -3 seata/verify-integration.py --check-order-mq
# 仅在独立演示环境检查 TC 中断及恢复，会短暂暂停 hotel-seata 容器
py -3 seata/verify-integration.py --check-tc-outage
```
