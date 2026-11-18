# notify

> **作者**：Lucas QQ `2434624535` 与邮箱 `2434624535@qq.com`

站内通知（P2 Phase 5 最小集：周报提醒 + 任务逾期）+ A2 外发汇总（`outbound/` 子包）。

## 定位

独立模块：负责「把该提醒的事写进当事人的收件箱」，并在扫描出**新增**后把它们合并成一条
每日汇总外发到已配置的通道（邮件 / IM Webhook，不配置就不启用）。
表结构见 `db/migration/V1__init.sql` 的 `notifications`。

| 表 | 说明 |
| --- | --- |
| `notifications` | 收件箱：user_id / type / title / body / ref_type / ref_id / dedup_key / read_at |

`user_id` 上的外键 `ON DELETE CASCADE`——人没了通知也没意义。

## 组成

| 类 | 职责 |
| --- | --- |
| `NotificationService` | 读取（列表 / 未读数 / 标已读）与每日扫描生成；`ScanResult` 携带真正新增的 `newNotices` |
| `NotificationScheduler` | 每日 cron 调度，扫描后调外发，失败只记日志 |
| `NotificationMapper` | 全部 SQL（幂等插入、收件箱查询、逾期候选粗筛） |
| `web/NotificationController` | 4 个端点（见下），`scan` 同样接外发 |
| `outbound/EmailSender` | SMTP 通道：现场组装 `JavaMailSenderImpl`，587/25 STARTTLS、465 SSL、10s 超时，from 回落 username |
| `outbound/WebhookSender` | 通用 IM Webhook：wecom / dingtalk / feishu 三种报文 + 加签（钉钉进 URL、飞书进报文） |
| `outbound/OutboundNotifier` | 外发编排：只发增量、失败隔离、邮件与 IM 同款内容 |

## 端点

- `GET /api/notifications?limit=` → `{unread, items}`（未读在前，同状态 id 倒序）
- `POST /api/notifications/{id}/read`（他人通知一律 404，不泄露存在性）
- `POST /api/notifications/read-all` → `{updated}`
- `POST /api/notifications/scan` → `{weeklyReminders, overdueTasks, newNotices}`
  （手工触发，幂等；`newNotices` 是本次真正新落库的明细，重扫为 `[]`，扫描后同样走外发）

读需登录；标已读只要求 authenticated（VIEWER 可清自己的红点，见 `SecurityConfig`）；
scan 按全局内容写规则走 ADMIN/MEMBER。`AuditInterceptor` 跳过 `/api/notifications` 前缀
（个人收件箱状态，进审计只会刷屏）。

## 关键取舍

- **幂等靠唯一键，不靠先查后插**：`(user_id, dedup_key)` 唯一键 + `INSERT ... WHERE NOT EXISTS`，
  重复扫描（含服务重启补跑）天然不产生重复行。
  周报提醒 `weekly:<周一>` 每周一条；任务逾期 `overdue:<taskId>:<dueDate>` 每个截止日一条——
  **改期后再逾期会重新提醒，持续逾期不刷屏**。
- **逾期口径只有一个**：SQL 只粗筛（`status <> 'done' AND due_date < today`，JOIN 未删项目），
  最终判定统一走 `ProgressCalculator.isOverdue`，与项目页的「逾期」标记不会打架。
- **收件人回落链**：启用的负责人 → 启用的项目 owner → 跳过。
  启用成员 id 一次性 `listActiveUserIds` 取回内存判定，扫描不逐行查库。
- **周报复用周报页的查询**：`reportMapper.missingUserIds(weekStart)`，
  通知名单与周报页「未提交名单」口径完全一致，不会出现两处各算一套。
- **调度与备份同款模式**：`@ConditionalOnProperty(kb.notify)` + `@Scheduled(cron, zone)`，
  异常只记日志绝不抛出；关掉调度不影响手工 `scan` 端点。

### 外发三不变式（`outbound/OutboundNotifier`）

- **只发增量**：`ScanResult.newNotices` 为空（幂等重扫 / 无待办）时什么都不发——
  同一份汇总不会因重复触发而重发；dedup 命中的通知不进明细。
- **失败隔离**：任何通道异常只记日志，绝不向调度线程或 HTTP 响应抛出——
  站内通知在扫描时已落库，外发是旁路，挂了不能连累扫描。
- **同款内容**：邮件与 IM 收到的是同一份汇总文本；收件人 = 固定列表
  `KB_NOTIFY_EMAIL_TO`（To）∪ 本次新通知收件人的邮箱（Bcc，互不暴露地址）；
  汇总三层截断（`MAX_LINES=20` / `MAX_LINE_CHARS=100` / `MAX_CHARS=1200`），
  IM 机器人 4096 字节上限内留足余量。

**启用条件**：邮件 = `enabled=true` 且 `host` 非空；IM = `url` 非空——两者都不配就静默跳过
（只打一行 debug 日志）。凭据与密钥只经环境变量注入，不入库、不进仓库。
