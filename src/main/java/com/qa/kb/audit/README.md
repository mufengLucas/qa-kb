# audit

> **作者**：Lucas QQ `2434624535` ｜ 邮箱 `2434624535@qq.com`

操作审计（P2 Phase 2）：谁在何时对什么做了什么。

- `AuditService.record`：写入唯一入口，截断对齐列宽（action 200 / target_type 50 / target_id 100 / detail 500），**永不抛异常**——审计失败只记 warn，绝不影响主业务响应。
- 采集入口有二：
  - `web/AuditInterceptor` 统一拦 `/api/**` 的 POST/PUT/PATCH/DELETE（GET 不产生审计）；登录/登出由 `AuthController` 显式埋点（LOGIN / LOGIN_FAILED / LOGOUT），拦截器跳过这两条路径避免双记。
  - 读取：`GET /api/admin/audit?limit=&offset=`（ADMIN-only，复用 SecurityConfig `/api/admin/**` 规则）。
- 表 `audit_log` **刻意不设 user_id 外键**：成员删除后审计必须保留，查询端 LEFT JOIN users 补登录名（已删用户显示为 null，前端标注「已删除」）。
- 建表由 `SchemaInitializer` 启动幂等执行（MySQL `db/migration/V1__init.sql` / SQLite `db/schema.sql` 两侧同结构）。
