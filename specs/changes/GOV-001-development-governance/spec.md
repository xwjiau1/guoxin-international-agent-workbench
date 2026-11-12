# GOV-001 开发规则与 Spec 体系接管

状态：VERIFIED（docs-only，无需应用发布）

日期：2026-11-12

授权依据：用户明确要求接管当前工程，结合指定 `personal-dev-system` 通用说明建立 `CLAUDE.md`、rules，并结合现存 docs/specs 补充后续迭代规范。

## 1. 需求与范围

### 目标

- GOV-FR-001：建立项目根 `CLAUDE.md`，保留 `AGENTS.md` 的硬性约束，提供接管、任务入口、变更路径和验证导航。
- GOV-FR-002：建立 `.claude/rules/` 下编码、测试、安全、Agent、Git 五类短规则，纳入 Actor/权限、端口、确认、防重、数据和产物一致性要求。
- GOV-FR-003：将通用 S/M/L 分级和四种变更路径与现有 `SPEC_DRAFT → … → RELEASED` SDD 门禁合并，不重构 V0.1 历史编号和证据。
- GOV-FR-004：登记真实项目事实、构建/测试命令索引和按需模板，使后续 M/L 变更可从 `specs/changes/` 独立追踪。
- GOV-FR-005：更新 README、specs、docs 和 HANDOFF 导航，明确本次仅规则/文档变化，不伪报应用重新验证或发布。

### 非目标

- 不实现 V0.2、RAG、OKR、真实 SSO/低代码适配，不修改业务源码、数据库、Compose、模型或部署产物。
- 不安装 Serena/MCP/Skills/子代理，不创建 worktree，不提交或推送。
- 不把外部通用模板中的示例路径、覆盖率、技术栈或工具能力当作项目事实。

## 2. 设计与契约

### 文档分层

- `AGENTS.md`：项目范围、安全和环境硬约束，保持唯一权威。
- `CLAUDE.md` + `.claude/rules/`：常驻工作原则和领域底线。
- `docs/development/`：较长的 SDD、派工、工作区和维护流程，按需读取。
- `docs/project-context.md`：真实技术/目录/命令/证据边界。
- `docs/templates/`：M/L 工件模板。
- `specs/<version>/`：版本基线；`specs/changes/<change-id>/`：进行中的独立变更。

### 契约影响

无 Java、HTTP、SSE、组件、数据库、Prompt/Skill/Workflow 或配置契约变化。现有契约继续以 `specs/v0.1/`、`docs/contracts/workbench.openapi.json` 和实际源码为准；DRIFT-07/10 的契约验证缺口不因本次文档建设而关闭。

### 兼容与副作用

只新增规则/模板/变更记录并修改导航及忽略项。无数据库写入、迁移、外部 API 调用、镜像构建或服务重启；V0.1 发布清单保持原版本。

## 3. 验收

- AC-GOV-01：Given 通用规范与现有项目约束，When 新 Agent 从根目录接管，Then 可从 CLAUDE/HANDOFF 找到五类 rules、项目事实、SDD、验证和运维入口。
- AC-GOV-02：Given V0.1 已发布但有未验证专项，When 阅读规则与项目事实，Then 不会把 RAG/OKR/真实适配、gated/skipped、恢复/回滚或全接口契约写成已通过。
- AC-GOV-03：Given S/M/L 或四种变更入口，When 创建后续任务，Then 规则能确定最少工件、目录、状态、任务/ Bug 追踪与六层验证要求，且与现有 SDD 不冲突。
- AC-GOV-04：Given 本次文档差异，When 执行文档检查，Then 新增本地 Markdown 链接有效、Git whitespace 检查无错误，并明确业务测试未重跑。

## 4. 任务

| 任务 | 状态 | 工作与验证 |
| --- | --- | --- |
| T-GOV-001 | done | 读取 HANDOFF、V0.1 spec/plan/tasks、验证/设计/运维及指定通用规范，提取不变量和事实 |
| T-GOV-002 | done | 建立 CLAUDE 与五类 rules；检查不放宽 AGENTS |
| T-GOV-003 | done | 建立项目事实、Spec 流程、团队/工作区/维护和按需模板 |
| T-GOV-004 | done | 建立本 change、更新导航与 HANDOFF；修复文档状态滞后，链接、必需文件和差异检查通过 |

### T-GOV-004.B-01 V0.1文档状态滞后

- 发现阶段/根因阶段：最终一致性检查；早期发布文档未随最终验收同步，属路径1的文档结果不符。
- 修前事实：V0.1 spec仍写“真实浏览器需部署新版后复测”及FR-002/004/006待复测，需求讨论稿仍写“尚未开始代码开发”，与V-012/V-013和发布记录冲突。
- 修复：仅将状态更新为已有最终证据，保留Boot覆盖边界和所有未验证专项；把需求讨论稿明确标为历史背景，不改需求或测试结论。
- 回归：重新执行陈旧状态搜索、Markdown链接和`git diff --check`。

## 5. 验证与变更记录

| 验证 | 测试层/方法 | 当前结果 | 证据/限制 |
| --- | --- | --- | --- |
| V-GOV-001 | 来源对照 | pass | 已逐项阅读现有 SDD/验证/运维和通用 CLAUDE/rules/模板；采用项目化合并而非整目录覆盖 |
| V-GOV-002 | 文档链接与布局自动检查 | pass | 47个Markdown文件共80个本地链接全部解析成功；13项核心治理文件全部存在 |
| V-GOV-003 | `git diff --check` 与差异审查 | pass | 无whitespace错误；既有文件差异仅限导航、历史状态校正和忽略项，无业务代码/配置变化 |
| V-GOV-004 | 六层业务测试 | not_run（不适用） | 无业务代码/配置/产物变化，本次不重跑；V0.1 历史证据保持原状 |

变更路径：本次为路径 2 的工程治理需求新增，从需求/规则进入；不影响业务设计、运行契约和应用任务。后续若发现规则与 AGENTS 或现有 spec 冲突，从最上游规则修正，并重新检查所有导航与引用。

## 6. 交接与下一步

后续迭代从 `CLAUDE.md` 判断 S/M/L，M/L 在 `specs/changes/` 建档；优先从用户指定需求或 `specs/roadmap.md` 建立独立 change，不能直接把路线图当作已授权全部实现。V-015 至 V-020 仍是 V0.1 已登记专项缺口，本次不关闭。
