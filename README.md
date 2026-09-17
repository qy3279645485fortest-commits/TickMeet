# TickMeet · 热爱，现场见

非选座展会/演出票务项目。目录与 `dianping` 同级，独立数据库、Redis命名空间和消息队列。按照文档2实现业务，不修改点评源代码。

## 直接体验

从 GitHub 独立克隆后，安装 JDK 8 和 Maven 3.9，在项目根目录执行：

```sh
mvn clean package
java -jar target/tickmeet-1.0.0.jar --spring.profiles.active=demo
```

打开 http://127.0.0.1:8088/ 即可体验。demo 模式无需安装 MySQL、Redis 或 RabbitMQ；运行数据保存在 `.data/`，不提交到 Git。下述 Windows 脚本依赖原工作区父目录的 `.runtime`，独立克隆时请使用以上标准命令。

双击 **start-demo.cmd**，打开 http://127.0.0.1:8088/ 。停止用 **stop.cmd**。首次启动需要父目录已有的 `.runtime` JDK/Maven（本工作区已配置）。

登录弹窗可选择三个演示账号，点击“获取验证码”后自动填入，点击登录：

| 身份 | 演示邮箱 | 可体验内容 |
|---|---|---|
| 观众 | hello@tickmeet.local | 抢票、订单、模拟支付、退款、票夹、发笔记、点赞关注、签到 |
| 运营 | admin@tickmeet.local | 创建活动/场次/票档、发布、暂停销售、创建场馆 |
| 核销员 | staff@tickmeet.local | 已授权演示场次的入场核销 |

验证码为随机一次性验证码。页面上的支付/退款均为模拟，不产生真实扣款。

建议体验顺序：

1. 观众登录，首页选择活动和票档，提交抢票。
2. 等待异步建单，在“我的订单”点击模拟支付。
3. 打开“我的票夹”，查看二维码，点击“复制核销信息”。
4. 点击右上角账号 → 切换核销员 → 入场核销。信息自动带入；第二次核销会提示已使用。
5. 另购一个票档，可测试取消后重买；支付后未核销可测试退款。
6. 运营登录创建草稿，发布后回到观众界面购票。新建场次的核销权限需在 `tm_staff_session` 配置，P0未提供角色/授权管理API。

演示数据保存在 `.data/tickmeet.mv.db`，图片保存在 `.data/uploads`。停止不会删除数据。演示临时登录态保存在内存，重启需重新登录。

## 两种运行方式

| 模式 | 数据库 | 登录/预占 | 消息 | 用途 |
|---|---|---|---|---|
| demo（默认） | H2文件库 | 进程内适配器 | 本地Outbox分发、扫描关单 | 零中间件体验 |
| live | MySQL | Redis Lua/Hash/Set/ZSet/Bitmap/GEO + Redisson | RabbitMQ确认、重试、DLQ、TTL超时 | 技术联调与学习 |

真实中间件联调已经在独立 `tickmeet_test` 数据库执行。demo测试通过不能替代live测试。

本工作区运行live：

```powershell
# 在 TickMeet 目录
..\.runtime\start-services.ps1
.\scripts\stop.ps1
$env:TICKMEET_DEMO='true' # 仅本地实验：显示验证码、开放本地模拟付款入口
$env:TICKMEET_SEED='true' # 首次初始化演示账号与活动
.\scripts\start.ps1 -Profile live
```

私密连接配置是 `.data/live.json`（不入Git）；已有配置可用。新环境先准备MySQL/Redis/RabbitMQ，再设置环境变量，参考 `.env.example` 和 `application-live.yml`。不要把测试库URL沿用到体验服务。

live默认不显示验证码；启用 `mail` profile并配置SMTP后可发送登录邮件。没有邮件服务器时，应显式使用本地实验的 `TICKMEET_DEMO=true`。真实SMTP送达尚未实测。

## 模块和测试

| 模块 | 源码包 | 测试 |
|---|---|---|
| 登录、权限 | auth | AuthModuleTest、HttpModuleTest |
| 活动、场馆、场次、票档 | catalog | CatalogModuleTest |
| 预占、Outbox、异步订单、超时 | trade | ReservationModuleTest、LiveModuleTest |
| 模拟支付、电子票、核销、退款 | trade | PaymentModuleTest、HttpModuleTest |
| 图片、引用、失败补偿 | files | FileCommunityModuleTest、StorageFailureTest |
| 笔记、点赞、关注、签到 | community/cache | FileCommunityModuleTest、LiveModuleTest |
| Redis恢复 | trade/InventoryRecovery | LiveRecoveryTest |

已完成的自动化测试：**33项通过**，详见 [实施与测试记录](docs/IMPLEMENTATION.md)。测试内容包括40请求抢8张票、真实Redis30请求抢5张票、6线程并发核销、重复消息、越权、回调伪造、文件删除失败、库存丢失恢复。

```powershell
.\scripts\test.ps1 -Module AuthModuleTest
.\scripts\test.ps1 -Module ReservationModuleTest
.\scripts\test.ps1 # 不接外部中间件，live测试会显式跳过
.\scripts\test-live.ps1 # 隔离真实中间件测试
```

## 技术和代码阅读

Java 8、Spring Boot 2.7.18、MyBatis-Plus 3.5.3.2、JdbcTemplate显式事务SQL、Redis、Redisson、RabbitMQ、Actuator/Micrometer。前端为同源HTML/CSS/JavaScript，无需Node构建。保留单体部署，不引入微服务。

用户查询示例使用MyBatis-Plus Mapper；订单状态迁移和锁库存使用显式SQL，便于核对锁顺序、条件更新和事务边界。所有持久化ID为字符串，价格以分计，前端不决定成交金额。

主链路：`OrderController → TradeService.reserve → Inventory Lua → tm_outbox → RabbitEvents → TradeService.createOrder`。支付/核销/退款在 `PaymentService`；库存恢复在 `InventoryRecovery`。

- [实际接口与文档2差异](docs/API与规划差异.md)
- [库存恢复说明](docs/Redis故障恢复演练.md)
- [文件存储验收](docs/文件存储验收.md)
- [并发验证与压测边界](docs/压测报告.md)

当前是可体验、可测试的学习项目。Sentinel切主、多实例自动恢复栅栏、S3真实服务器、Grafana告警与HTTPS部署尚未完成现场验收；配置样例不能当成已通过的生产能力。
