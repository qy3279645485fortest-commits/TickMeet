# 实际接口与文档2差异

实现依据：../dianping/docs/02-展会票务项目-需求接口与迁移规划.md。文档2是目标设计，以下记录TickMeet当前代码事实，避免把未验收规划写进简历。

## 已实现业务模块

N01—N51均有后端路由，统一加/api前缀：邮箱登录、活动/场馆查询、运营草稿/场次/票档/发布、异步预占与订单、模拟支付、电子票与核销、图片、笔记/点赞/关注/动态/签到、退款。输入使用专用DTO与Bean Validation；输出采用白名单字段映射，不直接返回订单/用户实体。

路由存在不代表所有异常组合都经过测试。测试范围见IMPLEMENTATION.md。

## 重要实现差异

| 文档2目标 | 当前实现 | 状态/影响 |
|---|---|---|
| 保留单体Controller/Service/Mapper | Spring Boot单体；用户查询用MyBatis-Plus，其余复杂事务用JdbcTemplate显式SQL | 同一数据源/事务管理器；没有拆微服务 |
| 登录Hash、Lua限流 | live用Redis Hash会话，验证码Lua消费，固定窗口Lua计数 | 固定窗口允许边界突发；尚未换滑动窗口 |
| DRAFT→PREPARING→PUBLISHED | 发布事务内校验、预热Redis，成功后提交PUBLISHED；失败数据库保持DRAFT | 没有可查询的PREPARING中间态；重试仅允许未产生交易的库存初始化 |
| 售卖元数据预热到Redis | 预占事务锁票档，读取最新DB状态/时间后传Lua核验 | 暂停与准入串行；每次预占增加DB读/锁，性能尚未压测 |
| Redis分布式ID | 使用UUID字符串 | 前端无大整数精度问题；不宣称实现了原RedisIdWorker |
| 活动缓存 | live Cache Aside、空值30秒、Redisson重建锁、逻辑30秒/物理5分钟加抖动TTL | 仅缓存展示信息；订单价格和库存不读展示缓存 |
| 查询分页与检索 | 当前部分列表先查询后过滤/分页 | 适合演示数据；大规模场景需改SQL分页与批量关联，未宣称高吞吐 |
| GEO/点赞/关注/签到/Feed | live实际使用GEO、ZSet、Set、Bitmap；DB为源，部分读取时重建投影 | Feed有Outbox扇出；读取也校准DB关系，尚未优化成大规模纯推模式 |
| RabbitMQ异步建单 | Outbox+发布确认/路由检查+持久消息+有限消费重试/DLQ | 业务操作幂等；默认demo使用本地分发 |
| TTL+死信超时关单 | live timeout队列逐消息TTL→events队列；数据库扫描兜底 | TTL队列存在队头等待可能，扫描保证最终关单；尚未注入Broker停机长期实验 |
| 模拟回调签名 | HMAC-SHA256时间戳签名+持久支付尝试，重复通知不重复发票 | 另有仅demo启用的本人订单确认入口，便于UI体验 |
| 迟到支付补偿 | 标记COMPENSATION_REQUIRED，不重开已关闭订单 | 没有真实支付通道，不实施真实退款；未接第三方支付 |
| 退款失败后再次申请 | 失败回调恢复订单/票据可用状态 | 每单唯一退款记录；重新发起失败退款的多次尝试尚未扩展 |
| 文件对象存储 | Local/S3适配器、文件台账/引用锁、删除202重试、孤儿UPLOADING清理 | 本地与失败注入测试通过；S3服务端/跨实例/备份未验收 |
| Redis恢复 | 库存缺失拒绝准入；维护命令按请求和订单重建 | 维护单实例测试通过；没有多实例恢复代次/自动栅栏 |
| Sentinel | 提供profile，Lettuce/Redisson同配置 | 未部署/演练1主2从+3哨兵；不是已完成高可用 |
| 监控 | 独立回环9088端口，health/prometheus，订单和积压指标 | 未交付完整Grafana告警触发/恢复证据；监控端口不能直接公网暴露 |
| SMTP | 可配置JavaMailSender，失败清理验证码 | 未提供真实邮箱服务，送达未实测 |
| HTTPS | 本地127.0.0.1 HTTP体验 | 没有安装本地CA/公网证书；公网部署前需补TLS |

## 额外体验接口

这些接口不替代原51个业务接口：

- GET /api/experience：模式和当前界面角色，决定显示哪些入口。
- POST /api/demo/payments/{id}/confirm：仅开启demo时，本人支付尝试的模拟成功。
- POST /api/demo/refunds/{id}/confirm：仅开启demo时，本人退款的模拟成功。
- GET /api/tickets/{id}/qr：仅持票人获取SVG二维码。
- GET /api/files/{id}/content：公开展示图片代理，不用于私密票码。

运维端点：回环管理端口9088的/actuator/health与/actuator/prometheus，不属于/api业务接口。

## 当前实际路由索引

以下从Controller注解提取。路径参数名称有少量简化（userId→id等），语义一致。


| 方法 | 路径 | 控制器 |
|---|---|---|
| POST | /api/user/code | AuthController.java |
| POST | /api/user/login | AuthController.java |
| POST | /api/user/logout | AuthController.java |
| GET | /api/user/me | AuthController.java |
| GET | /api/user/{id} | AuthController.java |
| GET | /api/user/info/{id} | AuthController.java |
| GET | /api/experience | AuthController.java |
| GET | /api/event-categories | CatalogController.java |
| GET | /api/events | CatalogController.java |
| GET | /api/events/{id} | CatalogController.java |
| GET | /api/events/{id}/sessions | CatalogController.java |
| GET | /api/sessions/{id}/ticket-types | CatalogController.java |
| GET | /api/ticket-types/{id} | CatalogController.java |
| GET | /api/venues/nearby | CatalogController.java |
| GET | /api/venues/{id} | CatalogController.java |
| POST | /api/admin/venues | CatalogController.java |
| PUT | /api/admin/venues/{id} | CatalogController.java |
| POST | /api/admin/events | CatalogController.java |
| PUT | /api/admin/events/{id} | CatalogController.java |
| POST | /api/admin/events/{id}/sessions | CatalogController.java |
| PUT | /api/admin/sessions/{id} | CatalogController.java |
| POST | /api/admin/sessions/{id}/ticket-types | CatalogController.java |
| PUT | /api/admin/ticket-types/{id} | CatalogController.java |
| POST | /api/admin/events/{id}/publish | CatalogController.java |
| PUT | /api/admin/ticket-types/{id}/sale-status | CatalogController.java |
| POST | /api/posts | CommunityController.java |
| PUT | /api/posts/{id}/like | CommunityController.java |
| GET | /api/posts/{id}/likes | CommunityController.java |
| GET | /api/posts/mine | CommunityController.java |
| GET | /api/users/{id}/posts | CommunityController.java |
| GET | /api/posts/feed | CommunityController.java |
| GET | /api/posts/hot | CommunityController.java |
| GET | /api/posts/{id} | CommunityController.java |
| GET | /api/follows/{id} | CommunityController.java |
| PUT | /api/follows/{id} | CommunityController.java |
| GET | /api/follows/common/{id} | CommunityController.java |
| POST | /api/user/sign | CommunityController.java |
| GET | /api/user/sign/count | CommunityController.java |
| POST | /api/files/images | FileController.java |
| DELETE | /api/files/{id} | FileController.java |
| GET | /api/files/{id}/content | FileController.java |
| POST | /api/ticket-types/{id}/reservations | OrderController.java |
| GET | /api/reservations/{id} | OrderController.java |
| GET | /api/orders | OrderController.java |
| GET | /api/orders/{id} | OrderController.java |
| POST | /api/orders/{id}/cancel | OrderController.java |
| POST | /api/orders/{id}/payments | PaymentController.java |
| POST | /api/payments/mock/notify | PaymentController.java |
| POST | /api/refunds/mock/notify | PaymentController.java |
| POST | /api/demo/payments/{id}/confirm | PaymentController.java |
| POST | /api/demo/refunds/{id}/confirm | PaymentController.java |
| GET | /api/tickets | PaymentController.java |
| GET | /api/tickets/{id} | PaymentController.java |
| POST | /api/staff/tickets/verify | PaymentController.java |
| POST | /api/orders/{id}/refunds | PaymentController.java |
| GET | /api/tickets/{id}/qr | PaymentController.java |
