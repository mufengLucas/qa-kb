# space

> **作者**：Lucas QQ `2434624535` ｜ 邮箱 `2434624535@qq.com`

知识库空间的增删改查 + 空间级权限（P2 Phase 3）。

## 空间权限（Phase 3）

- **表结构**：`spaces.restricted` 标记受限空间；`space_members (space_id, user_id, role)` 双列主键存成员，
  外键 CASCADE——删空间成员记录一并清掉。
- **判权核心** `SpaceAccessService`：
  - 开放空间：登录即可读，写按全局角色；
  - 受限空间：非成员 403（列表里直接不出现）；
  - 成员有效角色 = 成员记录优先，无记录回退全局角色；全局 VIEWER 为只读底线；
  - 改空间设置 / 管理成员需空间 ADMIN。
- **成员端点**：`GET/POST /api/spaces/{id}/members`（upsert）、`DELETE .../members/{userId}`；
  创建受限空间时创建者自动入组 ADMIN。
- **穿透范围**：空间列表、单点、页面树、页面读写、标签云、搜索、回收站、附件全部按可见空间过滤。
- **双方言**：成员 upsert 在 `mapper/SpaceMemberMapper.xml` 按 `databaseId` 分流
  （MySQL `ON DUPLICATE KEY UPDATE` / SQLite `ON CONFLICT ... DO UPDATE`）；
  同批顺手修复 `TagMapper`（原注解 `INSERT IGNORE` 是 MySQL 语法，SQLite 直接语法错）
  与 `SearchMapper.searchLike` 的 `ESCAPE` 字面量方言差异。
- **前端**：空间表单有「受限」开关；「管理」面板每个空间可开关受限、打开成员面板增删成员 / 改角色。
