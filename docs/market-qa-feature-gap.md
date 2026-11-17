# 市场主流 QA / 测试管理工具功能差距调研报告

> **作者**：Lucas QQ `2434624535` ｜ 邮箱 `2434624535@qq.com`
>
> **调研日期**：2026-11-17（全部外部资料采集日期均为 2026-11-17）
> **调研对象**：`D:\qa-kb`（QA 团队工作台，公开仓库 `mufengLucas/qa-kb`）vs 三层竞品共 13 家
> **性质**：纯调研报告，不含任何代码改动；本文件为本次调研唯一新增文件。

---

## 摘要（TL;DR）

- qa-kb 是面向 **3~15 人测试团队的内网团队工作台**，已有知识库、周报、项目进度、成员权限、操作审计、站内通知、用例管理与执行记录、自动备份等 8 大模块（详见 §1）。
- 用 **14 个统一能力域** 对比 13 家竞品后（§2）：qa-kb 在「知识库与文档」「站内协作通知」「权限与审计」上不输甚至反超轻量竞品；但在 **缺陷管理、需求追踪（RTM）、测试计划/执行批次、报表仪表盘、导入导出、外发通知** 6 个核心能力域上为空白——这 6 项恰好是专业测试管理工具的"标配六件套"。
- 差距条目共 **14 条**（§3），按价值×成本收敛为 **Top 10 路线图**（§4）：
  - **高价值低成本（4 项，先做）**：用例/执行导入导出、外发通知、报表页+CSV 导出、用例评论@
  - **高价值高成本（4 项，需改表）**：测试计划与执行批次、缺陷最小闭环、需求 RTM 最小版、步骤级执行+截图
  - **低价值/明确不做（2 项，缓做）**：开放 API 与 CI 自动化回传、SSO/LDAP/MFA
- 另有 **8 项明确不做**（自动化引擎、压测、BDD/探索性测试、AI 生成等）与 **2 项候补**（AI 用例生成、自定义字段/模板），理由见 §4。
- 采集局限：飞书多维表格官网反爬（返回空）→ 相关结论全部标注 **⧗ 待确认**；websearch 工具不可用（HTTP 402），全部资料靠 webfetch/curl 直接抓官方页面，失败清单见 §5.3。

---

## §1 功能基线：qa-kb 现状（带证据）

### 1.1 模块与证据对照表

| 模块 | 能力要点 | 证据（README 行号 / 代码文件） |
| --- | --- | --- |
| 知识库 | 空间 / 页面树 / Markdown / 标签 / 回收站 / 历史版本 / 附件（≤20MB）/ 中文 ngram+LIKE 搜索 | README L103–L115；`V1__init.sql` L52–L111（pages/page_revisions/tags/page_tags/attachments）；`AttachmentController.java` L47–L94 |
| 周报 | 周一为周起始、草稿/提交、本周清单联动真实任务、团队周报、未提交名单 | README L117–L125；`ReportController.java` L32（`/api/reports`）、L59–L196 |
| 项目进度 | 项目 / 里程碑 / 任务、逾期标记、进度=完成÷总数 | README L127–L131；`V1__init.sql` L113–L157 |
| 成员与权限 | ADMIN/MEMBER/VIEWER + 空间级三档角色、BCrypt、登录限速、防锁死最后一个 ADMIN | README L133–L146；`V1__init.sql` L1–L50 |
| 操作审计 | 关键写操作 + 登录留痕，ADMIN 分页查看 | README L148；`V1__init.sql` L201（audit_log） |
| 用例管理与执行 | 独立模块，`module` 归属（**不关联空间/项目**）；字段：标题/前置/步骤/预期/P0~P3/ACTIVE~ARCHIVED；执行记录 PASS/FAIL/BLOCKED/SKIPPED + 备注 + 执行人/时间 | README L150–L161；`V1__init.sql` L215（test_cases）、L233（test_runs）；`TestCaseController.java` L60–L128 |
| 站内通知 | 铃铛红点、每日 09:00 扫描周报提醒+任务逾期、`(user_id, dedup_key)` 幂等 | README L163–L182；`V1__init.sql` L248（notifications）；`NotificationController.java` |
| 自动备份 | 每日 02:30 逻辑 dump → zip，保留 14 份 + 异地副本，管理员手动备份 | README L197–L226；`AdminController.java`（`POST /api/admin/backup`） |
| 前端 | 无构建 Vue 3 单页，5 个 tab：知识库 / 周报 / 项目进度 / 用例 / 成员 | `static/index.html` L66–L85 |
| 健康检查 | `GET /api/health` | `HealthController.java` L16 |

### 1.2 基线核对结论（"本系统缺失"的反证方法）

- 全仓 `grep`：`defect / bug_ / jira / export / csv / excel / comment` 在 `src/main/java` 下**零业务命中**（仅命中 `SqlScriptRunner.java` L35/L59 的 `stripLineComments`，属 SQL 注释剥离，与功能无关）；`static/` 下仅命中 `purify.min.js` 的 license 文本。→ **缺陷、导出、评论功能确无实现**。
- `V1__init.sql` 共 **20 张** `CREATE TABLE`（users/sessions/settings/spaces/space_members/pages/page_revisions/tags/page_tags/attachments/projects/milestones/tasks/daily_reports/daily_report_projects/report_items/audit_log/test_cases/test_runs/notifications）；`SchemaInitializer.java` L46–L47 另有 2 处幂等补列（`projects.deleted_at`、`spaces.restricted`），L72 为 `ALTER TABLE ... ADD COLUMN`。
- 用例表**没有** requirement/defect/plan/run 批次外键（`V1__init.sql` L215–L246）→ 需求追踪、缺陷关联、测试计划三项在数据模型层即为空白。
- `test_runs` 只有 `result/note/executor/executed_at` 四个业务字段（`V1__init.sql` L233–L246）→ 无步骤级结果、无批次归属、无环境/版本字段。
- README §7（L341–L344）**自认的唯一缺口**是"邮件 / 企业微信外发通知"；但按代码核对，缺口远不止这一项（见 §3）——README §7 不是完整缺口清单。

### 1.3 README 与代码 / 背景说法的不一致点

| # | 不一致点 | 说明 |
| --- | --- | --- |
| 1 | **表数量**：背景/惯例说法为"24 张表"，实际 `V1__init.sql` 只有 **20 张** `CREATE TABLE` | README 本身未写表数（L307 仅指向 `V1__init.sql`）；若把 `SchemaInitializer` 补列的 2 列、以及运行期可能的视图/索引计入也凑不到 24。建议以 20 为准 |
| 2 | **README L308 源码结构**只列 `static/{index.html,app.js,styles.css}` | 实际 `static/` 还有 `vendor/`（vue/marked/purify）、`favicon.svg`、`README.md`、`app.js.bak` 未提及（轻微，文档完整性） |
| 3 | **README §7 缺口清单不完整**：只写"外发通知"一项 | 按 §1.2 核对，缺陷管理、报表仪表盘、导入导出、评论、需求追踪、测试计划等均无实现却未列入 §7 |
| 4 | 测试数"466 个"（README L302） | 本次为只读调研，**未执行 `mvn test`**，该数字为 README 自述，未独立验证 |

---

## §2 竞品能力域对比矩阵

### 2.1 图例与能力域定义

| 符号 | 含义 |
| --- | --- |
| ● | 明确具备（来源页直接证实，来源 ID 见 §5） |
| ○ | 部分具备 / 弱相关（来源页部分证实） |
| ✕ | **采集资料中未见**（未证实，不等于确定缺失） |
| ⧗ | 待确认（官网反爬 / 页面未覆盖，本次未取证） |

**14 个统一能力域**：

| 代号 | 能力域 | 覆盖内容 |
| --- | --- | --- |
| D1 | 用例管理与组织 | 套件/模块/文件夹、自定义字段、模板、版本、共享步骤 |
| D2 | 测试计划与执行 | Test Plan / Test Run 批次、分配、里程碑、回归集 |
| D3 | 步骤级执行与测试数据 | 每步结果、执行截图/附件、参数化测试数据 |
| D4 | 缺陷管理 | 缺陷记录、状态流、与用例/执行关联、推送缺陷 |
| D5 | 需求追踪与覆盖率 | 需求↔用例↔结果追溯（RTM）、覆盖缺口 |
| D6 | 报表与仪表盘 | 指标图表、实时看板、定时/可分享报告 |
| D7 | 数据导入/导出 | CSV/Excel 用例导入、PDF/Excel/CSV 导出 |
| D8 | 自动化与 CI/CD 集成 | 自动化结果回传、CI 流水线集成 |
| D9 | 开放 API 与集成生态 | REST/GraphQL API、Webhook、第三方集成数量 |
| D10 | 通知与外发 | 邮件/IM/Webhook 外发、定时报告推送 |
| D11 | 身份、权限与审计 | SSO(SAML/OIDC)/LDAP/MFA、RBAC、审计日志 |
| D12 | AI 能力 | AI 用例生成、AI 分析、MCP |
| D13 | 协作能力 | 评论、审批、@提及、探索性测试、BDD |
| D14 | 知识库与文档 | 文档/知识库沉淀（qa-kb 的主战场） |

### 2.2 矩阵 A：qa-kb vs 专业测试管理工具（7 家）

| 能力域 | **qa-kb** | TestRail | Zephyr Scale | Xray | qTest | PractiTest | Qase | TestLink |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D1 用例管理与组织 | ○ 模块+固定字段（README L150–153） | ● 套件/自定义字段/模板/版本/共享步骤 [S1] | ○ 建用例+链接需求/缺陷，AI 步骤建议 [S3] | ○ AI 起草用例（manual/Cucumber）[S5] | ● 可复用测试步骤 [S6] | ○ 手工/探索/自动化/BDD 多类型 [S8] | ● Smart Test Wizard + 全局共享步骤 [S9] | ● 用例库并组织进测试计划 [S11] |
| D2 测试计划与执行 | ✕ 仅逐条执行，无批次（`V1__init.sql` L233） | ● Runs/Plans/Milestones/待办/分配 [S1] | ○ plan/track/report（结构待确认）[S3] | ● 计划随 story、回归 Test Set、执行看板 [S4][S5] | ○ 测试管理+DevOps 工作流 [S6] | ● Planning & Design / Execution 分区 [S8] | ● 无限项目与 test runs [S9] | ● 测试计划+分配人员+记录结果 [S11] |
| D3 步骤级执行与测试数据 | ✕ 整体 result+note（`V1__init.sql` L233–L246） | ● 按步骤记录+截图附件+参数化 [S1] | ○ AI 步骤建议 [S3] | ⧗ | ● 可复用测试步骤 [S6] | ⧗ | ● 全局共享步骤 [S9] | ⧗（README 未述步骤级） |
| D4 缺陷管理 | ✕ 无 defect 表（§1.2 grep 反证） | ● 推送缺陷至 Jira/缺陷集成 [S1] | ● 在 Jira 内跟踪缺陷 [S3] | ● 追溯报告含 defects [S5] | ⧗ | ● 平台内含 defects 模块 [S8] | ⧗ | ⧗ |
| D5 需求追踪与覆盖率 | ✕ 用例不关联项目/空间（README L152） | ● 覆盖率报告+追溯报告 [S1] | ● 链接 user story 与需求 [S3] | ● 需求↔用例↔执行↔缺陷追溯 [S5] | ○ 提及需求一致性（弱证据）[S6] | ● 串联 requirement→test→run [S8] | ● 独立 RTM 页（实时覆盖）[S9] | ● 需求与用例双仓库 [S11] |
| D6 报表与仪表盘 | ○ 仅用例统计回显+项目进度%（README L131/L154–155） | ● 实时看板/跨项目/定时可分享报告/自定义图表 [S1] | ● 计划-执行-报告闭环 [S3] | ○ 追溯报告（看板未证实）[S5] | ⧗ | ● Dashboards & Reports [S8] | ○ Test Intelligence 发布就绪度 [S9] | ● 多种报告（README 原文"a wide variety of reports"）[S11] |
| D7 数据导入/导出 | ✕ 全仓无 export/csv/excel（§1.2 grep） | ● CSV 用例导入 [S1] | ⧗ | ⧗ | ⧗ | ● 导出 PDF / Excel [S8] | ⧗ | ⧗（docs.testlink.org 抓取失败） |
| D8 自动化与 CI/CD 集成 | ✕ | ● CI/CD 结果上传+自动化框架集成+CLI [S1] | ● 对接 BDD/CI-CD 工具 [S3] | ● REST API 进 CI，回传 JUnit/NUnit/Jenkins/GitLab 结果 [S5] | ● 开放对接任意 CI/CD/APM/DevOps [S6] | ● CI/CD 流水线+自动化框架 [S8] | ● CI/CD 连接的自动化测试管理 [S9] | ⧗ |
| D9 开放 API 与集成生态 | ○ 仅前端内部 `/api/*`，无对外 token/文档/webhook（`web/` 12 个 Controller） | ● API + CLI + Webhooks [S1] | ⧗（Jira 原生） | ● REST API（GraphQL 未证实）[S5] | ⧗ | ● 公开 API 文档 [S8] | ● API 文档 + 35+ 集成（Jira/GitHub/Slack）[S9] | ⧗ |
| D10 通知与外发 | ○ 站内铃铛，**无外发**（README §7 L341–344） | ● 定时/按需邮件发送报告 [S1] | ⧗ | ⧗ | ⧗ | ○ 灵活分发 [S8] | ○ Slack 等集成 [S9] | ⧗ |
| D11 身份、权限与审计 | ○ 三角色+空间角色+audit_log+登录限速（README L133–148） | ● SSO(SAML2.0/OAuth/OIDC)+MFA+RBAC+自定义角色+审计+项目级管理 [S1] | ⧗（继承 Jira 权限） | ⧗（继承 Jira 权限） | ⧗ | ● SSO/SAML + RBAC + SOC2 [S8] | ● SSO(SAML 2.0)+RBAC+审计日志 [S9] | ⧗ |
| D12 AI 能力 | ✕ | ● AI-driven test management / DesignWise [S1][S2] | ● AI 测试步骤建议 [S3] | ● AI 用例生成（人工/Cucumber 格式）[S5] | ● AI 优化、自动生成测试 [S6] | ● AI Test Management [S8] | ● AI 用例生成 + MCP + QA Skills [S9] | ✕（README 未见 AI 功能） |
| D13 协作能力 | ○ 有周报/通知，**无评论、无审批**（grep 无 comment；README §2 无评论章节） | ● 用例审批流+结果评论 [S1] | ● BDD 工具集成 [S3] | ● 探索性测试产品线 + Cucumber/BDD [S5] | ● 探索性测试 [S6] | ● 手工/探索/自动化/BDD 一体 [S8] | ⧗ | ⧗ |
| D14 知识库与文档 | ● **核心能力**：空间/页面树/版本/标签/搜索/附件（README L103–115） | ✕ 采集资料未见 | ✕ | ✕ | ✕ | ✕ | ✕ | ○ 需求与测试规格仓库（弱）[S11] |

### 2.3 矩阵 B：qa-kb vs 国内综合平台 / 轻量替代（5 家）

| 能力域 | **qa-kb** | 禅道 | MeterSphere | Jira（+缺陷插件组合） | Notion 类模板 | 飞书多维表格 |
| --- | --- | --- | --- | --- | --- | --- |
| D1 用例管理与组织 | ○ 模块+固定字段 | ● 测试用例管理模块 [S12] | ● 测试用例管理 [S13] | ✕（本体无用例库，靠 Xray/Zephyr 插件） | ○ 数据库自建用例表 [S15][S16] | ⧗ 待确认 [S17] |
| D2 测试计划与执行 | ✕ 无批次 | ● 测试计划 / 测试执行模块 [S12] | ● 测试计划与执行 [S13] | ○ sprint/backlog（非测试计划）[S14] | ○ 可自建看板，无执行语义 [S16] | ⧗ [S17] |
| D3 步骤级执行与测试数据 | ✕ | ⧗（用例含步骤字段，未取证） | ⧗ | ✕ | ✕ | ⧗ [S17] |
| D4 缺陷管理 | ✕ 无 defect 表 | ● Bug/缺陷管理模块 [S12] | ● 缺陷管理 [S13] | ● issue/缺陷跟踪本体 [S14] | ○ 可自建缺陷表 | ⧗ [S17] |
| D5 需求追踪与覆盖率 | ✕ | ○ 需求模块（本次未取证细节） | ⧗ | ○ user story 作为 issue 管理 [S14] | ○ 可自建需求表 | ⧗ [S17] |
| D6 报表与仪表盘 | ○ 统计回显+进度% | ○ 报表/BI（首页提及 BI 产品）[S12] | ⧗ | ⧗ | ✕ | ⧗ [S17] |
| D7 数据导入/导出 | ✕ | ● **Excel 导入导出** [S12] | ⧗ | ⧗ | ⧗（导出能力未取证） | ⧗ [S17] |
| D8 自动化与 CI/CD 集成 | ✕ | ● Jenkins/GitLab/SonarQube/Nexus 集成 [S12] | ● 开放生态、DevOps 流水线集成 [S13] | ○ DevOps 生态（未取证细节） | ⧗ | ⧗ [S17] |
| D9 开放 API 与集成生态 | ○ 内部 API | ● 开放 API [S12] | ● 开放 API 与 SDK [S13] | ⧗ | ⧗ | ⧗ [S17] |
| D10 通知与外发 | ○ 站内，无外发 | ● **SMTP 邮件 + 系统通知** [S12] | ⧗ | ⧗ | ⧗ | ⧗ [S17] |
| D11 身份、权限与审计 | ○ 三角色+空间角色+审计 | ● **LDAP 集成** [S12] | ○ 系统-组织-项目分层角色 [S13] | ○ 权限体系（未取证） | ⧗ | ⧗ [S17] |
| D12 AI 能力 | ✕ | ● ZenChat/ZenAgent/AI Wiki/ZAI [S12] | ● AI 生成用例/接口文档 [S13] | ● Rovo AI [S14] | ⧗ | ⧗ [S17] |
| D13 协作能力 | ○ 周报+通知，无评论 | ⧗（评论/@未取证） | ⧗ | ⧗ | ● 团队协同编辑（"Think together"）[S15] | ⧗ [S17] |
| D14 知识库与文档 | ● **核心能力** | ● 文档管理模块 [S12] | ⧗ | ⧗（Confluence 生态未取证） | ● 知识库/文档 + 模板市场 [S15][S16] | ⧗ [S17] |

### 2.4 矩阵读法（三条结论）

1. **专业工具的"标配六件套" = D2/D4/D5/D6/D7/D10**：7 家专业工具在测试计划、缺陷、需求追踪、报表、导入导出、外发通知上几乎全员 ●；qa-kb 这 6 列全是 ✕/○ 缺口 —— 这就是差距清单的主干（§3 的 G1~G5、G7）。
2. **qa-kb 反超的格子是 D14（知识库）和 D1（部分）**：TestRail/Zephyr/Xray/qTest/PractiTest/Qase 在知识库域全是 ✕，而 qa-kb 的空间/版本/搜索/附件是完整能力——**"知识库 + 用例"组合正是 qa-kb 相对专业测试工具的差异化定位**，应继续加固而非放弃。
3. **国内平台（禅道/MeterSphere）在 D7/D10/D11 上更接地气**（Excel 导入导出、SMTP 通知、LDAP），这三项恰是国内小团队最常抱怨的"导不出、喊不到人、账号难管"，属于低成本高感知差距。

---

## §3 差距清单（价值 × 成本分级）

> 价值：对 3~15 人内网测试团队的日常收益；成本：按"改表+后端+前端+测试"的相对工作量估算（低/中/高）。
> 每条都给出**本系统证据**（README 行号或代码文件）与**竞品证据**（§5 来源 ID，采集日期均为 2026-11-17）。

| # | 差距项 | 本系统证据（缺失证明） | 竞品证据 | 价值 | 成本 | Top 10 归档 |
| --- | --- | --- | --- | --- | --- | --- |
| G1 | 缺陷管理（缺陷表、状态流、与用例/执行关联、推送 Jira） | `grep defect/bug` 零命中；`V1__init.sql` 20 表无缺陷表；README §2 无缺陷章节 | TestRail 推送缺陷 [S1]、Zephyr 跟踪缺陷 [S3]、PractiTest defects 模块 [S8]、禅道 Bug 模块 [S12]、MeterSphere 缺陷管理 [S13]、Jira 本体 [S14] | 高 | 高 | **B2** |
| G2 | 需求追踪 RTM / 覆盖率 | 用例"不关联空间、不关联项目"（README L152）；无需求表 | TestRail 覆盖率+追溯报告 [S1]、Xray 追溯报告 [S5]、Qase RTM 页 [S9]、TestLink 需求仓库 [S11] | 高 | 高 | **B3** |
| G3 | 测试计划与执行批次（Plan/Run、分配、里程碑、按批次通过率） | `test_runs` 仅逐条记录（`V1__init.sql` L233）；无 plan/run 表 | TestRail Runs/Plans/Milestones [S1]、Xray 回归 Test Set [S5]、TestLink 测试计划 [S11]、禅道测试计划 [S12] | 高 | 高 | **B1** |
| G4 | 报表与仪表盘（通过率趋势、模块分布、定时报告） | 仅列表回显统计（README L154–155）与进度%（L131）；无图表 | TestRail 实时看板/定时可分享报告 [S1]、PractiTest Dashboards [S8]、TestLink 多种报告 [S11] | 高 | 中 | **A3** |
| G5 | 导入/导出（CSV/Excel 用例导入、执行与报表导出） | `grep export/csv/excel` 零业务命中（§1.2） | TestRail CSV 导入 [S1]、PractiTest 导出 PDF/Excel [S8]、禅道 Excel 导入导出 [S12] | 高 | 低 | **A1** |
| G6 | 步骤级执行结果 + 执行截图附件 | `test_runs` 无 step 字段；附件只挂在页面（`AttachmentController` L47） | TestRail 按步骤记录+附件 [S1]、qTest/Qase 共享步骤 [S6][S9] | 中高 | 中高 | **B4** |
| G7 | 外发通知（邮件/企业微信/Webhook） | README §7 L341–344 **自认缺失**；`notify/` 仅站内 | TestRail 定时邮件报告 [S1]、禅道 SMTP+系统通知 [S12] | 高 | 低 | **A2** |
| G8 | 用例评论与 @提及（评审协作） | `grep comment` 零命中；README §2 无评论章节 | TestRail 结果评论+用例审批 [S1] | 中 | 低 | **A4** |
| G9 | 开放 API Token / Webhook / 自动化结果回传 | `web/` 12 个 Controller 均为前端内部接口，无 token、无对外文档 | TestRail API+CLI+Webhooks [S1]、Xray REST API 进 CI [S5]、Qase API 文档 [S9]、MeterSphere API/SDK [S13] | 中 | 中 | **C1（缓）** |
| G10 | 企业级身份：SSO/LDAP/MFA、自定义角色 | 仅本地 BCrypt 账号+三角色（README L133–146） | TestRail SSO/MFA/自定义角色 [S1]、PractiTest SSO/SAML [S8]、Qase SAML [S9]、禅道 LDAP [S12] | 中（内网低） | 中 | **C2（缓）** |
| G11 | AI 用例生成 / AI 分析 | 无任何 AI 能力 | TestRail [S1][S2]、Xray [S5]、qTest [S6]、Qase+MCP [S9]、禅道 Zen 系列 [S12]、MeterSphere [S13]、Jira Rovo [S14] | 中 | 中高 | 候补 11（首期不做） |
| G12 | 用例自定义字段 / 模板 / 版本对比 / 共享步骤 | `test_cases` 字段固定 8 个（`V1__init.sql` L215–L230） | TestRail 自定义字段+模板+版本+共享步骤 [S1]、Qase 全局共享步骤 [S9] | 中 | 中 | 候补 12 |
| G13 | 跨项目报表 / 多项目视图 | 项目为单层列表（README L127–131） | TestRail 跨项目报告 [S1] | 低 | 中 | 明确不做 |
| G14 | 工时估算/预测、用例审批流、甘特图 | 无 | TestRail Estimates & Forecasting、Test Case Approvals [S1] | 低 | 中 | 明确不做 |

**统计**：能力域 14 个；差距条目 **14 条**（G1–G14）；进入 Top 10 的 10 条（G1–G10），候补 2 条（G11、G12），明确不做 2 条（G13、G14）。

---

## §4 分阶段补全建议（Top 10 路线图）+ 明确不做项

### 4.1 第一档：高价值低成本（A1–A4，建议 1~2 个迭代内完成）

| 序 | 项 | 做什么 | 为什么先做 |
| --- | --- | --- | --- |
| A1（G5） | **用例/执行导入导出** | `test-cases` CSV/Excel 导入（含校验与错误行回执）、用例+执行记录导出 CSV；报表导出 CSV | 竞品标配（[S1][S8][S12]）；只加 Controller+解析，不改核心模型；466 例基线迁入/迁出都靠它 |
| A2（G7） | **外发通知** | 在现有每日扫描（README L167–182）之上加 SMTP 邮件 + 企业微信机器人 Webhook 两个通道，按用户配置开关 | README §7 已自认；复用 `notifications` 表与 dedup_key，增量成本最低、感知最强 |
| A3（G4） | **报表页 + CSV 导出** | 新增"报表"tab：用例按模块/优先级分布、执行通过率与趋势、失败 Top 模块；全部可导出 CSV | 把已有的 `test_runs` 数据变成可见价值，无需新表，纯聚合查询+图表 |
| A4（G8） | **用例评论与 @提及** | `test_case_comments` 表 + 详情页评论区，@成员复用现有 `notifications` 落站内信 | 补上评审协作空白，成本低；与 A2 组合可外发 |

### 4.2 第二档：高价值高成本（B1–B4，需表结构变更，按 2~4 个迭代排期）

| 序 | 项 | 做什么 | 关键设计约束 |
| --- | --- | --- | --- |
| B1（G3） | **测试计划与执行批次** | 新增 `test_plans` / `test_run_batches`：按版本/迭代圈选用例、分配执行人、批次通过率与完成率 | 必须向后兼容现有逐条 `test_runs`（历史执行记录不可迁移丢失） |
| B2（G1） | **缺陷最小闭环** | 本地 `defects` 表（标题/严重级/状态/关联用例+执行记录/负责人），先做站内闭环，Jira 推送做成可选适配器 | 定位是"团队工作台"，**不做**专业缺陷系统的工作流引擎；状态流控制在 4~5 态 |
| B3（G2） | **需求 RTM 最小版** | `requirements` 表（编号/标题/来源），用例↔需求多对多，覆盖视图（已覆盖/缺口/失败关联） | 先解决 README L152"用例不关联项目"的结构性问题，否则 RTM 无从谈起 |
| B4（G6） | **步骤级执行 + 执行附件** | `test_run_steps` 表（步骤序号/结果/备注），执行时逐步打勾；执行截图复用 `attachments` 机制 | 步骤文本目前是整段 TEXT（`V1__init.sql` L219），需定义"步骤拆分"兼容策略 |

### 4.3 第三档：低价值 / 明确缓做（C1–C2）

| 序 | 项 | 结论与理由 |
| --- | --- | --- |
| C1（G9） | 开放 API Token / Webhook / CI 自动化结果回传 | **缓做**：本系统定位明确"不是自动化平台"（README L5–12），首期无自动化回传需求；若团队后续引入自动化，再升为 B 档候补（第 11 项） |
| C2（G10） | SSO / LDAP / MFA / 自定义角色 | **缓做**：内网 3~15 人、已有 BCrypt+登录限速+三角色+空间角色（README L133–146），收益低；组织规模扩大或对接公司统一身份时再评估 |

### 4.4 明确不做项（超出定位，写入"不做清单"防止范围蔓延）

1. **自动化执行引擎**（Selenium/Playwright 编排）——不是自动化平台。
2. **性能压测平台**——不是压测平台。
3. **BDD / Cucumber 编排与探索性测试会话管理**（[S5][S6][S8] 均有，但对小团队属重流程）。
4. **工时估算/预测、用例审批流、甘特图**（G14，[S1] 有但收益低）。
5. **跨项目 BI / 多项目报表矩阵**（G13，当前单层项目模型足够）。
6. **多租户 SaaS 化、MFA、细粒度自定义角色矩阵**（企业级能力，[S1] 有但内网小团队用不上）。
7. **对标飞书多维表格的全量视图体系**（看板/日历/表单/仪表盘全量复刻）——飞书资料本次 **⧗ 待确认**，即便具备也只取"表格化用例视图"这一个点，不整体对标。
8. **AI 用例生成（首期）**——列为候补 11：等 A/B 档落地、有真实数据积累后再评估（届时可参考 [S9] Qase 的 MCP 路线）。

### 4.5 建议排期（一句话版）

> **先 A（4 项低成本，1~2 迭代）→ 再 B（4 项改表，2~4 迭代）→ C 与候补按需触发；不做清单永久生效，新增需求先对照 §4.4。**

---

## §5 来源引用

### 5.1 官方资料（全部采集日期：**2026-11-17**）

| ID | 来源 | URL | 采集日期 |
| --- | --- | --- | --- |
| S1 | TestRail Platform 功能总览 | https://www.testrail.com/platform/ | 2026-11-17 |
| S2 | TestRail 官网首页 | https://www.testrail.com/ | 2026-11-17 |
| S3 | SmartBear Zephyr Scale 产品页 | https://www.smartbear.com/test-management/zephyr-scale/ | 2026-11-17 |
| S4 | Xray（by Sembi）官网首页 | https://www.getxray.app/ | 2026-11-17 |
| S5 | Xray Test Management 产品页 | https://www.getxray.app/test-management/ | 2026-11-17 |
| S6 | Tricentis qTest 统一测试管理页 | https://www.tricentis.com/products/unified-test-management-qtest | 2026-11-17 |
| S7 | PractiTest 官网首页 | https://www.practitest.com/ | 2026-11-17 |
| S8 | PractiTest Product 页 | https://www.practitest.com/product/ | 2026-11-17 |
| S9 | Qase Features 页 | https://qase.io/features/ | 2026-11-17 |
| S10 | TestLink 官网站点 | https://testlink.org/ | 2026-11-17 |
| S11 | TestLink GitHub README（1.9.20） | https://github.com/TestLinkOpenSourceTRMS/testlink-code | 2026-11-17 |
| S12 | 禅道官网首页 | https://www.zentao.net/ | 2026-11-17 |
| S13 | MeterSphere GitHub README | https://github.com/metersphere/metersphere | 2026-11-17 |
| S14 | Atlassian Jira 产品页 | https://www.atlassian.com/software/jira | 2026-11-17 |
| S15 | Notion 产品页 | https://www.notion.com/product | 2026-11-17 |
| S16 | Notion 模板市场 | https://www.notion.com/templates | 2026-11-17 |
| S17 | 飞书多维表格 | https://www.feishu.cn/product/base —— **抓取返回空（反爬），全部结论标 ⧗ 待确认** | 2026-11-17（抓取失败） |

### 5.2 本系统证据（只读核对，2026-11-17）

- `D:\qa-kb\README.md`：L5–12（定位与模块清单）、L103–115（知识库）、L117–125（周报）、L127–131（项目进度）、L133–148（权限与审计）、L150–161（用例与执行）、L163–182（站内通知）、L197–226（备份）、L302（测试数自述）、L307–308（源码结构）、L341–344（§7 自认缺口）。
- `src/main/resources/db/migration/V1__init.sql`：20 张 `CREATE TABLE`；L215 `test_cases`、L233 `test_runs`、L248 `notifications`、L201 `audit_log`。
- `src/main/java/com/qa/kb/db/SchemaInitializer.java`：L46–L47 补列、L72 `ALTER TABLE`。
- `src/main/java/com/qa/kb/web/`：12 个 Controller（含 `TestCaseController.java` L60–L128、`ReportController.java`、`AttachmentController.java` L47–L94、`HealthController.java` L16）。
- `src/main/resources/static/index.html` L66–L85：5 个 tab。
- 全仓 grep（`defect|bug_|jira|export|csv|excel|comment`）：无业务实现命中。

### 5.3 采集失败与局限说明（不臆测原则）

- `websearch` 工具不可用（HTTP 402），全部改用 webfetch / curl 直接抓官方页面。
- 抓取失败的官方资料：`testrail.io`（改用 testrail.com 成功）、`getxray.app/features`（404，改用主页）、`practitest.com/features`（404，改用 /product/）、`metersphere.io` 全站（transport error，改用 GitHub README）、`qase.io/pricing`、`docs.testlink.org`、`qase.com/pricing`、`larksuite.com`（502）、知乎专栏（403）。
- **飞书多维表格**（feishu.cn 多个 URL）均返回空 → 报告中所有飞书相关格子标 ⧗ 待确认，不做任何能力断言。
- 竞品页面中标 ⧗ 的格子表示"本次未取证"，**不代表该产品没有该能力**；标 ✕ 表示"采集资料中未见"，同样不等于确定缺失。

---

*本报告为纯调研产出：未修改 `src/` 下任何文件，未执行任何 git 操作，未连接运行中的服务/数据库/Docker。*
