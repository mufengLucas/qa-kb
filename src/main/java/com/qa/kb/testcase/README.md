# testcase

> **作者**：Lucas QQ `2434624535` 与邮箱 `2434624535@qq.com`

用例管理与执行记录（P2 Phase 4）。

## 定位

独立模块：用例只靠 `module` 字段做归属与筛选，**不关联空间、不关联项目**。
表结构见 `db/migration/V1__init.sql`。

| 表 | 说明 |
| --- | --- |
| `test_cases` | 用例：module / title / precondition / steps / expected / priority / status |
| `test_runs` | 执行记录：result（PASS·FAIL·BLOCKED·SKIPPED）/ note / executor / executed_at |

用例删除是硬删除，执行记录随用例级联清理。

## 端点

`TestCaseController`（`/api/test-cases`）：

- `GET /api/test-cases?module=&q=&status=&limit=&offset=` → `{total, items, modules}`
- `POST /api/test-cases`、`GET|PUT|DELETE /api/test-cases/{id}`
- `GET|POST /api/test-cases/{id}/runs`、`DELETE /api/test-cases/{id}/runs/{runId}`

读需登录，写需 ADMIN/MEMBER（SecurityConfig 全局规则，本模块无特殊配置）；
写操作被 `AuditInterceptor` 自动留痕（targetType = `test-cases`）。

## 关键取舍

- **LIKE 转义符用 `!` 而非反斜杠**：反斜杠在 MySQL 字符串里要写成 `'\\'` 才解析成 1 个字符，
  `'!'` 是普通字符、无需转义，一条语句即可。见 `TestCaseService.likePattern`。
- **统计走相关子查询**：`count` 与 `page` 共用同一段 `<where>`，一页查询拿到执行次数、
  通过/失败数与最近一次结果，前端无需二次请求。
- **创建人走 LEFT JOIN**：成员被删除后用例仍在，登录名显示为 null（前端标注「已删除」）。
