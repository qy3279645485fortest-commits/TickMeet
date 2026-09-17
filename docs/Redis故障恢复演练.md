# Redis库存恢复：已验证范围与操作

## 已实测：库存键丢失的维护重建

测试：LiveRecoveryTest，真实MySQL/Redis，独立tickmeet_test库，测试消费者与调度关闭。

过程：票档5张 → 预占两张 → 第一张已落库订单、第二张仍待建单 → 删除该测试票档三个Redis键 → 新抢票失败 → 重建 → 再建单/取消/重复释放。

断言：D=4（DB可用），P=1（未落库预占），重建R=D-P=3，保留两个holder；取消其中一单后R/DB均回到4，旧释放消息再执行两次仍为4。

没有执行FLUSHDB，没有触碰点评Redis键。测试不是Sentinel切主实验。

## 维护命令

仅用于本项目单实例本地环境。在多实例部署中，必须先停掉全部应用写入者、MQ消费者和补偿任务。

```powershell
.\scripts\recover-inventory.ps1 -TicketTypeId '实际票档ID'
# 检查 INVENTORY_RECOVERY 输出，再按需要启动live服务
.\scripts\start.ps1 -Profile live
```

脚本只停止由TickMeet启动脚本记录的Java进程，通过PID和启动时间同时确认归属。维护Java进程关闭MQ消费、调度，使用随机临时端口，执行完成后退出。

InventoryRecovery锁库存/请求台账，保留RESERVED和有效订单的占用，重建stock/holders/reservations。终态预占记RELEASED，防止历史释放再次加库存。INIT在恢复后可由原请求幂等重试；无法解释的重复有效占用或负可用量会拒绝重建。

## 尚未验收

- 1主2从+3 Sentinel的真实切主、复制滞后和丢写。
- 多实例共享恢复代次、全局RECOVERING门禁和自动栅栏。
- Redis单键丢失、holder或凭据部分丢失的自动检测。
- 多组件同时故障及备份还原。

application-sentinel.yml仅提供连接配置；Lettuce与Redisson使用同一组Sentinel配置。不能把“连接可配置”写成“已经实现无损高可用”。当前票档stock键缺失会拒绝新准入，最终MySQL条件扣库/占用约束仍是安全底线。
