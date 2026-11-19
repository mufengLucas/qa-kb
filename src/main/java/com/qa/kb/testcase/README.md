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
- **评论（A4）**：
  - `GET /api/test-cases/{id}/comments` → 评论列表（时间正序）
  - `POST /api/test-cases/{id}/comments`：@提及会为被提到的启用用户创建站内通知
  - `PUT /api/test-cases/{id}/comments/{commentId}`（仅作者）、`DELETE`（作者或管理员，软删除）
- **CSV 导入导出（A1）**：
  - `GET /api/test-cases/export?module=&q=&status=`：按筛选全量导出用例 CSV（不分页）
  - `GET /api/test-cases/runs/export`：执行记录全量导出（带用例模块/标题与执行人）
  - `POST /api/test-cases/import`（multipart `file`）：表头按列名映射，合法行入库、
    坏行逐行回执不整体失败；单次 ≤ 2000 行，表头缺 `title` / 空文件整体 400

读需登录，写需 ADMIN/MEMBER（SecurityConfig 全局规则，本模块无特殊配置）；
写操作被 `AuditInterceptor` 自动留痕（targetType = `test-cases`）。

## 关键取舍

- **CSV 自己写、不引第三方库**：只做 RFC 4180 的解析与序列化（引号字段、字段内换行、
  `""` 转义、BOM 剥离），`common/Csv.java` 约 100 行可控可测，比为一个功能点引依赖更划算。
- **导入逐行校验、坏行回执不整体失败**：迁入场景常见「几十行里夹两行坏数据」，
  整体回滚会逼用户先手工修表；回执行号含表头，与用户在 Excel 里看到的行号一致。
- **LIKE 转义符用 `!` 而非反斜杠**：反斜杠在 MySQL 字符串里要写成 `'\\'` 才解析成 1 个字符，
  `'!'` 是普通字符、无需转义，一条语句即可。见 `TestCaseService.likePattern`。
- **统计走相关子查询**：`count` 与 `page` 共用同一段 `<where>`，一页查询拿到执行次数、
  通过/失败数与最近一次结果，前端无需二次请求。
- **创建人走 LEFT JOIN**：成员被删除后用例仍在，登录名显示为 null（前端标注「已删除」）。
