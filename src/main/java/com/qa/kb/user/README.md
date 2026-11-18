# user

> **作者**：Lucas QQ `2434624535` ｜ 邮箱 `2434624535@qq.com`

用户、角色与登录频率限制。

## 字段与校验

| 字段 | 规则 |
| --- | --- |
| `login` | 唯一，统一转小写，`^[a-z0-9][a-z0-9._-]{2,63}$` |
| `name` | 必填，≤ 100 字符（`MAX_NAME_LENGTH`） |
| `email` | **选填（A2）**：空串/空白 = 不收外发邮件；非空则 ≤ 200 字符（`MAX_EMAIL_LENGTH`）且匹配最简格式 `^[^\s@]+@[^\s@]+\.[^\s@]{2,}$`，脏地址直接拒绝 |
| `password` | BCrypt 存储；创建/重置走 `PasswordPolicy` |
| `role` | `ADMIN` / `MEMBER` / `VIEWER`；最后一个启用 ADMIN 不可降级/停用/删除 |
| `active` | 停用后不能登录（已有会话失效） |

`email` 由 `users.email VARCHAR(200) NOT NULL DEFAULT ''` 存储（老库启动时
`SchemaInitializer.ensureColumn` 幂等补列），供 A2 外发通知按人投递——
扫描新增通知的收件人邮箱会进每日汇总的 Bcc（见 `notify/outbound/OutboundNotifier`）。

## 写入口径

- `create(login, name, password, role)`：4 参便捷重载，邮箱缺省为空串；
  `create(..., email)` 5 参版为完整入口。
- `update(id, name, role, active)`：4 参便捷重载，**保持原邮箱**（`email == null`）；
  `update(..., email)` 5 参版中 `null` = 保持、`""` = 显式清空、非空 = 校验后覆盖——
  前端只回传变更字段时不会把已有邮箱抹掉。
- 视图 `UserView` 携带 `email`（`/api/users`、`/api/members`）；
  登录视图 `AuthController.UserView` **不带** email，避免在会话接口多暴露一个字段。
