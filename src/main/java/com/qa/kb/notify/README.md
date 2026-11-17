# notify

> **作者**：Lucas QQ `2434624535` 与邮箱 `2434624535@qq.com`

站内通知（P2 Phase 5 最小集：周报提醒 + 任务逾期）。

## 定位

独立模块：只负责「把该提醒的事写进当事人的收件箱」，不发邮件、不发 IM。
表结构见 `db/migration/V1__init.sql` 的 `notifications`。

| 表 | 说明 |
| --- | --- |
| `notifications` | 收件箱：user_id / type / title / body / ref_type / ref_id / dedup_key / read_at |

`user_id` 上的外键 `ON DELETE CASCADE`——人没了通知也没意义。

## 组成

| 类 | 职责 |
| --- | --- |
| `NotificationService` | 读取（列表 / 未读数 / 标已读）与每日扫描生成 |
| `NotificationScheduler` | 每日 cron 调度，失败只记日志 |
| `NotificationMapper` | 全部 SQL（幂等插入、收件箱查询、逾期候选粗筛） |
| `web/NotificationController` | 4 个端点（见下） |

## 端点

- `GET /api/notifications?limit=` → `{unread, items}`（未读在前，同状态 id 倒序）
- `POST /api/notifications/{id}/read`（他人通知一律 404，不泄露存在性）
- `POST /api/notifications/read-all` → `{updated}`
- `POST /api/notifications/scan` → `{weeklyReminders, overdueTasks}`（手工触发，幂等）

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
