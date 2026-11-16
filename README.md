# QA 团队工作台

> **作者**：Lucas QQ `2434624535` ｜ 邮箱 `2434624535@qq.com`

面向 3~15 人测试团队的内网工作台，一个进程同时提供 Web 界面和 API。第一版覆盖三块刚需：

- **知识库**：空间 / 页面树 / Markdown 正文 / 标签 / 回收站 / 历史版本回滚 / 中文全文检索
- **周报**：个人周报（草稿、提交）、本周清单（关联项目任务）、团队周报、未提交名单
- **项目进度**：项目 / 里程碑 / 任务，进度按「已完成任务 / 总任务」自动计算

另外包含成员与角色管理（ADMIN / MEMBER / VIEWER）、页面附件、操作审计、空间级权限、
用例管理与执行记录（P2 Phase 1~4），以及每日自动备份和管理员手动备份。

技术栈刻意保持轻量，**前端不需要 Node.js，也不需要 Redis、Elasticsearch**；唯一的外部依赖是数据库——**只支持 MySQL**（本机用 Docker 起一个即可）：

| 组件 | 选型 |
| --- | --- |
| 运行时 | JDK 21（Temurin） |
| 构建 | Maven 3.9 |
| 后端 | Spring Boot 3.5 + MyBatis |
| 数据库 | **MySQL 8**（InnoDB + utf8mb4，中文检索靠 ngram `FULLTEXT` 索引） |
| 前端 | Vue 3 全局构建版，纯静态文件，无打包步骤 |

---

## 1. 快速启动

### 1.0 启动 MySQL（每个新环境先做一次）

应用只连 MySQL，启动前必须先有一个跑在 `127.0.0.1:3306` 的 MySQL 8，库名 `qa_kb`：

```powershell
$env:MYSQL_ROOT_PASSWORD='<换成你自己的强口令，不要写进仓库>'
& 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' run -d --name mysql-kb `
  -e MYSQL_ROOT_PASSWORD=$env:MYSQL_ROOT_PASSWORD `
  -e MYSQL_DATABASE=qa_kb `
  -v D:\docker\mysql\kb-data:/var/lib/mysql `
  -p 3306:3306 mysql:8.3
```

`qa_kb` 库与表由应用启动时的 `SchemaInitializer` 幂等创建，不用手工建表。
仓库里的 `docker-compose.yml` 也定义了等价的数据库服务（`qa-kb-db`，`mysql:8.4`），
连同 `Dockerfile` 用于容器化部署，二选一即可。

**口令只走环境变量**：应用与测试都读 `KB_DB_PASSWORD`（见 §4），仓库里不存放任何真实口令。

### 1.1 环境变量（每个新开的 PowerShell 窗口都要设置一次）

```powershell
$env:JAVA_HOME='D:\java\jdk21'
$env:Path="D:\java\jdk21\bin;D:\java\apache-maven-3.9.16\bin;$env:Path"
$env:KB_DB_PASSWORD='<与 MySQL root 口令一致>'
```

### 1.2 开发模式启动

```powershell
cd D:\qa-kb
& 'D:\java\apache-maven-3.9.16\bin\mvn.cmd' -B spring-boot:run
```

看到 `Tomcat started on port 8787` 即成功。

### 1.3 打包后启动（推荐日常使用）

```powershell
cd D:\qa-kb
& 'D:\java\apache-maven-3.9.16\bin\mvn.cmd' -B -DskipTests package
& 'D:\java\jdk21\bin\java.exe' -jar 'D:\qa-kb\target\kb.jar'
```

不注入 `KB_DB_PASSWORD` 时连接会被 MySQL 拒绝，启动日志里会看到数据库连接错误。

### 1.4 访问地址

- 本机：<http://127.0.0.1:8787>
- 局域网：**<http://192.168.1.170:8787>**（以 `ipconfig` 查到的实际 IPv4 为准）

首次在其他电脑打不开时，通常是 Windows 防火墙拦截了 8787 端口。以管理员身份执行一次即可：

```powershell
netsh advfirewall firewall add rule name="QA-KB 8787" dir=in action=allow protocol=TCP localport=8787
```

### 1.5 默认账号

| 登录名 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | 由 `KB_ADMIN_PASSWORD` 注入 | ADMIN |
| `alice` | 由 `KB_DEMO_PASSWORD` 注入 | MEMBER |
| `bob` | 由 `KB_DEMO_PASSWORD` 注入 | MEMBER |
| `viewer` | 由 `KB_DEMO_PASSWORD` 注入 | VIEWER |

> **公开仓库不存放真实密码。** 首次启动前请设置环境变量 `KB_ADMIN_PASSWORD` / `KB_DEMO_PASSWORD`（未设置时使用占位值 `change-me-please-1` / `change-me-demo-1`）。

> **上线前务必修改 admin 密码，并删除或停用演示账号。**
> 演示数据只会在数据库为空时写入一次；可用 `KB_SEED_DEMO=false` 关闭演示数据。

---

## 2. 功能说明

### 2.1 知识库

- 左侧选择空间，页面以树形展示；支持新建顶层页 / 子页面、编辑、删除（进回收站）、恢复。
- 正文使用 Markdown，保存时自动生成历史版本；可在「历史版本」中回滚，回滚前会把当前内容再存一版。
- 标签按逗号分隔输入，按名称复用，不重复建标签。
- 搜索结果带标题和正文片段，点击直接跳转。
- **附件**（P2 Phase 1）：页面详情可上传 / 下载 / 删除附件，单文件 ≤ 20MB，删除页面时附件一并清理。

**中文检索策略**（重点）：

- 查询 ≥ 3 个字符：走 MySQL **ngram `FULLTEXT` 索引**（`MATCH ... AGAINST`），支持中文子串匹配。
- 查询 1~2 个字符（如「登录」「超时」）：ngram token 拼不出 3 字组合，自动降级为 `LIKE '%…%'`，仍能搜到。
- 单条查询上限 100 字符；空白查询返回参数错误。

### 2.2 周报

- 「周报」页以**周一为周起始**，每周一份：填写本周完成、下周计划、阻塞与风险，可关联多个项目。
- 「保存草稿」与「提交周报」两种状态；团队周报只统计已提交内容，草稿对他人不可见。
- **本周清单**：输入后回车即添加一条轻量待办，勾选即完成；每条可通过「关联项目…」下拉绑定项目——
  选中后自动在该项目下创建一条真实任务并回填关联，勾选清单会把任务置为「已完成」，取消勾选回退「进行中」。
- 右侧显示本周清单完成情况（已完成 / 进行中条目）与团队周报。
- ADMIN 可以看到本周未提交人员名单。
- 历史 `/api/reports/daily/*` 端点保持兼容，旧数据不受影响。

### 2.3 项目进度

- 项目包含名称、唯一标识（2~10 位大写字母/数字）、状态、起止日期、描述。
- 项目详情包含里程碑与任务；任务可修改状态（待开始 / 进行中 / 已完成 / 阻塞），逾期任务会标记。
- 项目进度 = 已完成任务数 ÷ 总任务数，列表与详情一致。

### 2.4 成员与权限

- **ADMIN**：全部读写 + 成员管理 + 手动备份。
- **MEMBER**：内容读写（知识库、周报、项目任务）。
- **VIEWER**：只读。
- 登录名唯一且统一转小写；系统会阻止「停用/降级/删除最后一个可用 ADMIN」，避免把自己锁在门外。
- 登录失败有频率限制（默认 10 次 / 15 分钟窗口），密码使用 BCrypt 存储。

**空间级权限**（P2 Phase 3）：

- 空间可标记为**受限**：列表 / 单点 / 页面树 / 页面 / 标签 / 搜索 / 回收站对非成员一律不可见（403 或不出现）。
- 成员角色三档：**空间管理员**（可改空间设置与管理成员）/ **可编辑**（读写页面）/ **只读**（只能看）。
- 有成员记录按空间角色，无记录回退全局角色；全局 VIEWER 即使是空间成员也不能写（只读底线）。
- 创建受限空间时创建者自动入组空间管理员；「管理 → 成员」面板可增删成员、调整角色。

**操作审计**（P2 Phase 2）：关键写操作与登录留痕进 `audit_log`，ADMIN 在「成员 → 操作审计」分页查看。

### 2.5 用例管理与执行记录（P2 Phase 4）

- **独立模块**：用例只靠 `module` 字段归属与筛选，**不关联空间、不关联项目**。
- 用例字段：模块 / 标题 / 前置条件 / 步骤 / 预期结果 / 优先级（`P0`~`P3`，默认 `P2`）/ 状态（`ACTIVE` / `ARCHIVED`，默认 `ACTIVE`）。
- 「用例」页支持按模块下拉筛选、关键字搜索（LIKE，服务端 `!` 转义）、状态过滤与分页；
  列表直接回显执行统计（执行次数 / 通过 / 失败 / 最近结果与时间）。
- **执行记录**：详情里每次执行填结果（`PASS` / `FAIL` / `BLOCKED` / `SKIPPED`）与备注，
  自动带执行人与执行时间，可单条删除。
- 删除用例为**硬删除**，执行记录随外键 `ON DELETE CASCADE` 一并清理。
- 读需登录，写需 ADMIN/MEMBER（全局规则，本模块无特殊配置）；写操作自动进操作审计（targetType = `test-cases`）。
- 端点：`GET|POST /api/test-cases`、`GET|PUT|DELETE /api/test-cases/{id}`、
  `GET|POST /api/test-cases/{id}/runs`、`DELETE /api/test-cases/{id}/runs/{runId}`。

---

## 3. 数据与备份

### 3.1 数据位置

| 内容 | 默认路径 | 说明 |
| --- | --- | --- |
| 主数据库 | MySQL（Docker 容器 `mysql-kb`，数据目录 `D:\docker\mysql\kb-data`） | 库名 `qa_kb`，表由 `SchemaInitializer` 幂等创建 |
| 日志 | `data\qa-kb.log` | 运行日志 |
| 备份 | `data\backups\kb-YYYYMMDD-HHmmss-SSS.zip` | 每份 zip 内含一个 `kb.sql`（逻辑 dump） |
| 附件 | `data\uploads` | 页面附件（P2 Phase 1） |

### 3.2 自动备份

- 每天 **02:30**（Asia/Shanghai）自动执行一次：纯 JDBC 把每个基表导出成 `INSERT` 语句
  （首尾包 `SET FOREIGN_KEY_CHECKS=0/1`，恢复时不依赖表顺序），再压缩为 zip。
- 本地默认保留最近 **14** 份，按文件名（零填充时间戳）轮换删除最旧的。
- 管理员也可以在「成员 → 数据备份」点「立即备份」，对应接口 `POST /api/admin/backup`。
- 配置了异地目录后，每份备份会额外复制一份，并按相同规则清理。

异地目录示例（在 `application.yml` 的 `kb.backup.offsite-dir` 或环境变量里配置）：

```yaml
kb:
  backup:
    enabled: true
    dir: ./data/backups
    keep: 14
    offsite-dir: '\\192.168.1.10\qa-backup'   # 网络共享；留空则只保留本地
    cron: '0 30 2 * * *'
```

也可以用环境变量，不必改代码：

```powershell
$env:KB_BACKUP_OFFSITE_DIR='\\192.168.1.10\qa-backup'
$env:KB_BACKUP_KEEP='14'
$env:KB_BACKUP_CRON='0 30 2 * * *'
```

> 异地目录建议选另一台机器 / NAS / 移动硬盘。数据库本体在 Docker 卷里，
> 不要把 `data` 目录以外的数据目录整个放进 OneDrive、百度网盘等实时同步目录。

### 3.3 从备份恢复

> `kb.sql` **只含数据（INSERT），不含建表语句**——表结构由 `V1__init.sql` 负责。
> 因此恢复的目标库必须**先有表**，且里面若已有数据会和 dump 的主键冲突。

**整库丢失（换机器 / 误删库）：**

1. 起一个空 MySQL（§1.0），停止一切正在运行的应用进程。
2. 先给空库建表，二选一：
   - 启动一次应用，看到 `MySQL schema ready` 后立刻停掉；或
   - 手工导入建表脚本：
     ```powershell
     Get-Content 'D:\qa-kb\src\main\resources\db\migration\V1__init.sql' -Raw -Encoding UTF8 `
       | docker exec -i mysql-kb mysql -uroot -p"$env:MYSQL_ROOT_PASSWORD" qa_kb
     ```
3. 解压最新的 `kb-YYYYMMDD-HHmmss-SSS.zip`，把 `kb.sql` 灌进去：

   ```powershell
   Get-Content kb.sql -Raw -Encoding UTF8 | docker exec -i mysql-kb mysql -uroot -p"$env:MYSQL_ROOT_PASSWORD" qa_kb
   ```

4. 启动应用，用管理员账号登录抽查内容。

**回滚到某天的数据**：现有库里有数据，直接导会主键冲突——把现有库整个 DROP 后按上面 1~4 重建
（或先把 `kb.sql` 导入临时库核对无误再整体替换）。

建议每季度做一次「导入到临时库 → 用备份启动到 8788 端口 → 登录检查」的演练，确认备份真的可用。

---

## 4. 配置项

修改 `src/main/resources/application.yml`，或用同名环境变量覆盖（推荐后者）。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8787` | 服务端口 |
| `BIND_ADDRESS` | `0.0.0.0` | 监听地址；只允许本机可改为 `127.0.0.1` |
| `KB_DB_USER` | `root` | 数据库用户（数据源 URL 固定为 `127.0.0.1:3306/qa_kb`，要改库地址请改 `application.yml`） |
| `KB_LOG_FILE` | `./data/qa-kb.log` | 日志路径 |
| `KB_UPLOAD_DIR` | `./data/uploads` | 附件目录 |
| `KB_SEED` | `true` | 首次启动是否初始化管理员 |
| `KB_SEED_DEMO` | `true` | 是否写入演示空间 / 项目 / 周报 |
| `KB_ADMIN_LOGIN` | `admin` | 初始管理员登录名 |
| `KB_ADMIN_NAME` | `管理员` | 初始管理员姓名 |
| `KB_ADMIN_PASSWORD` | `change-me-please-1`（占位） | 初始管理员密码（部署时必须覆盖） |
| `KB_DEMO_PASSWORD` | `change-me-demo-1`（占位） | 演示账号统一密码 |
| `KB_DB_PASSWORD` | 空 | 数据库密码（**必须注入**，否则无法连接） |
| `KB_BACKUP_ENABLED` | `true` | 是否启用每日自动备份 |
| `KB_BACKUP_DIR` | `./data/backups` | 本地备份目录 |
| `KB_BACKUP_KEEP` | `14` | 本地保留份数 |
| `KB_BACKUP_OFFSITE_DIR` | 空 | 异地备份目录 / 网络共享 |
| `KB_BACKUP_CRON` | `0 30 2 * * *` | 备份 cron（秒 分 时 日 月 周） |

---

## 5. 开发与测试

```powershell
$env:JAVA_HOME='D:\java\jdk21'
$env:Path="D:\java\jdk21\bin;D:\java\apache-maven-3.9.16\bin;$env:Path"
$env:KB_DB_PASSWORD='<与 MySQL root 口令一致>'   # 跑测试前必设，只留在当前会话里

cd D:\qa-kb
& 'D:\java\apache-maven-3.9.16\bin\mvn.cmd' -B test      # 全部单元 + 接口测试
& 'D:\java\apache-maven-3.9.16\bin\mvn.cmd' -B package   # 生成可执行 jar
```

- **测试只用 MySQL**：每个 `@SpringBootTest` 测试类独占一个一次性 schema（`qa_kb_<名字>`，
  启动时 DROP + CREATE），因此不会污染生产库 `qa_kb`，用例之间也互不干扰。
- 连接信息来自环境变量 `KB_DB_HOST` / `KB_DB_PORT` / `KB_DB_USER` / `KB_DB_PASSWORD`
  （缺 `KB_DB_PASSWORD` 时测试会直接报错并给出注入提示）。
- 当前测试数：**450 个，全部通过**。
- 源码结构：
  - `src/main/java/com/qa/kb/web`：REST 接口
  - `src/main/java/com/qa/kb/{page,space,report,project,user,search,backup,testcase}`：业务逻辑
  - `src/main/java/com/qa/kb/persistence`：MyBatis Mapper
  - `src/main/resources/db/migration/V1__init.sql`：MySQL 建表脚本（`SchemaInitializer` 幂等执行）
  - `src/main/resources/static`：Vue 单页前端（`index.html` / `app.js` / `styles.css`）

### 关于 Docker 文件

`docker-compose.yml`（`qa-kb-db` + `qa-kb-app`）、`Dockerfile`、`.env.example` 是容器化部署的
一条完整路径；本机日常开发直接用 §1.0 的 `docker run` 起库、本地跑 jar 即可，两种方式二选一。
`src/test/legacy-mysql-it/` 是早期基于 Testcontainers 的集成测试，不参与 `mvn test`。

---

## 6. 运维备忘

- **同一时间只能有一个进程写数据库**。升级或恢复前先停掉旧进程，避免端口占用和文件锁。
- 检查端口占用：

  ```powershell
  Get-NetTCPConnection -LocalPort 8787 -State Listen | Select-Object OwningProcess
  ```

- 开机自启（可选）：
  - 任务计划程序 → 创建任务 → 触发器「登录时」→ 操作「启动程序」指向
    `D:\java\jdk21\bin\javaw.exe`，参数 `-jar D:\qa-kb\target\kb.jar`，
    起始于 `D:\qa-kb`。
  - 或用 WinSW / NSSM 注册为 Windows 服务。
- 日志排错优先看 `data\qa-kb.log` 和启动窗口输出。
- 升级步骤：停应用 → 点「成员 → 数据备份」（或 `POST /api/admin/backup`）出一份 `kb.sql` →
  替换 jar → 启动 → 管理员登录抽查。
  数据库结构由 `db/migration/V1__init.sql` 幂等初始化，新增表会自动创建；改字段仍需手工迁移。
- 用计划任务 / 服务方式启动时，**记得在任务里配置 `KB_DB_PASSWORD`**（以及 `KB_ADMIN_PASSWORD`），
  否则进程连不上数据库。

---

## 7. 尚未包含（P2）

- 邮件 / 企业微信通知
