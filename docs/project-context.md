# 项目事实与命令索引

核对日期：2026-11-12；基线提交：`b79abf4`。本文件登记仓库事实和历史证据，不表示本次接管已重新启动服务或重跑全部测试。实际运行状态以当次检查为准，V0.1 历史结果见 `docs/V0.1-验证记录.md`。

## 1. 产品与范围

| 项目事实 | 已确认值 | 来源 |
| --- | --- | --- |
| 目标用户与形态 | 模拟/试点企业员工；浏览器对话工作台 + Java Agent 后端 | `specs/v0.1/spec.md` |
| V0.1 范围 | 证照填单/草稿/提审，本人待办筛选/确认审核，历史、追踪与恢复 | `specs/v0.1/spec.md` |
| 非目标 | 生产上线、真实 SSO/低代码、RAG、完整 OKR、多实例高可用 | `specs/v0.1/spec.md`、`specs/roadmap.md` |
| 当前发布 | V0.1 本机模拟范围 RELEASED；专项 V-015 至 V-020 尚无完整证据 | `docs/V0.1-验证记录.md`、`specs/v0.1/tasks.md` |
| 身份语义 | Actor 来自后端 session；owner 管本人资源，assignee 管本人待办 | `AGENTS.md`、FR-001、INV-01 |

## 2. 技术与目录

| 项目事实 | 已确认值 | 来源 |
| --- | --- | --- |
| 后端 | Java 21、Spring Boot 3.5.7、Maven 多模块 | `backend/pom.xml` |
| Agent | LangChain4j 1.8.0、LangGraph4j 1.8.4、Qwen API | `backend/pom.xml`、`backend/assistant-agent/pom.xml` |
| 数据库 | PostgreSQL 16.4，Compose 命名卷；禁止换成 H2/本机库冒充验证 | `compose.yaml`、`AGENTS.md` |
| 前端 | React 19、TypeScript 5.9、Vite 7、Playwright；锁文件为 `frontend/package-lock.json` | `frontend/package.json` |
| 运行 | Docker Compose；工作台 5173、后端 8080、PostgreSQL 仅本机 55432 | `compose.yaml`、`HANDOFF.md` |
| 构建产物 | 后端 fat JAR、前端 dist；prebuilt 脚本不负责编译 | `docs/运维与部署手册.md` |
| 当前 Git 约定 | 主分支 main；未获明确要求不提交、不推送、不公开发布 | Git 状态、`AGENTS.md` |

### 模块索引

| 路径 | 职责 | 关键入口 |
| --- | --- | --- |
| `backend/assistant-domain` | 无框架共享事实 | `Actor`、`ToolResult` |
| `backend/assistant-application` | Agent/业务/事件端口和运行 DTO | `AgentRuntime`、`BusinessPort`、`RunRequest/Result` |
| `backend/assistant-agent` | Qwen 工具循环、LangGraph4j Workflow | `QwenInterpreter`、`WorkflowAgentRuntime` |
| `backend/assistant-business-mock` | 真实 PostgreSQL 上的模拟业务、表单/流程/审批/回执 | `MockBusinessService`、`schema-business.sql` |
| `backend/assistant-infrastructure` | 会话、任务、命令、消息和事件持久化 | `AgentStore`、`schema-agent.sql` |
| `backend/assistant-boot` | HTTP、session/CSRF、持久命令协调 | `WorkbenchController`、`AuthFilter/Service`、`RunCoordinator` |
| `frontend/src` | 对话、组件、HTTP/SSE 客户端 | `App.tsx`、`Cards.tsx`、`api.ts` |
| `capabilities/`、`prompts/` | 版本化 Skill 和 Prompt 资源；治理限制见 DRIFT-03/04 | 两项能力的 `1.0.0` 目录 |
| `deploy/`、`scripts/`、Compose | 构建、运行、备份、宿主环境适配 | 以运维手册为准 |

主调用链：`WorkbenchController → RunCoordinator → AgentRuntime → LangGraph4j 节点 → Qwen/工具网关 → BusinessPort → 模拟业务/PostgreSQL → 回执与持久事件/SSE`。

## 3. 命令索引

以下均为仓库已记录命令，默认在项目根目录 PowerShell 执行。当前 Windows 机器运行 Docker 前先执行 `. .\scripts\docker-env.ps1`，或用 `scripts/start-docker.ps1`；这只改当前进程，不修改全局环境。

| 目的 | 命令/入口 | 证据边界 |
| --- | --- | --- |
| Compose 语法 | `docker compose config --quiet` | 不输出展开秘密 |
| 源码构建并启动 | `docker compose up -d --build` | 三服务容器；启动不等于业务验收 |
| 已验证产物启动 | `powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Prebuilt` | 修改源码后须先重建 JAR/dist |
| 默认后端测试 | `docker compose --profile verify run --rm verify` | 真实 PG；Qwen 默认跳过，查看汇总 |
| 显式真实 Qwen | 使用 `docs/06-运行与验证.md` 第 3 节完整命令 | 产生 API 用量，只覆盖声明的模型用例 |
| 前端构建 | 在 `frontend/` 执行 `npm ci`、`npm run build` | 静态构建，不等于浏览器闭环 |
| API 桩浏览器 | 在 `frontend/` 执行 `npx playwright test tests/workbench.spec.ts` | 需 Docker 工作台；不是实际后端业务验收 |
| 真实浏览器 | 按 `docs/运维与部署手册.md` 6.3 显式设置 `RUN_LIVE_E2E=1` 后运行 live spec | 写入 local-demo；可能产生 Qwen 用量 |
| 备份 | `scripts/backup-db.ps1` | 备份成功不等于恢复演练通过 |

宿主 Maven 仅用于已有编译工具的开发/测试；先确认 `mvn -version` 实际使用 Java 21。不得为运行项目启动本机数据库或应用服务，不得修改系统 JAVA_HOME。

BUILD-001 已在项目内安装隔离的 Maven 3.9.9，复用 `C:\Users\admin\.jdks\temurin-21`，依赖仓库为 `.runtime/maven-repository`。这些路径被Git忽略，不属于发布产物；新机器需重新安装或恢复工具。当前机器从仓库根目录可用以下 PowerShell 命令进行跳过测试的全模块打包（仍编译测试源码）：

```powershell
$env:JAVA_HOME = 'C:\Users\admin\.jdks\temurin-21'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
& '.\.runtime\tools\build-001\apache-maven\apache-maven-3.9.9\bin\mvn.cmd' -s .mvn/settings.xml -f backend/pom.xml -B -ntp "-Dmaven.repo.local=$PWD\.runtime\maven-repository" -DskipTests package
```

该命令不启动Docker、数据库或应用，不执行测试，也不更新运行镜像。

## 4. 配置名称（不记录秘密值）

- `QWEN_API_KEY` / `DASHSCOPE_API_KEY`：生成模型认证，仅后端环境。
- `QWEN_BASE_URL`、`QWEN_MODEL`：模型端点与模型名。
- `AGENT_MODEL_MODE`：正常为 `qwen`；`test` 只用于明确自动化测试。
- `DATABASE_PASSWORD`：本机模拟数据库配置；已有卷不会因改 `.env` 自动轮换密码。
- `PG_TEST_URL`、`PG_TEST_USER`、`PG_TEST_PASSWORD`：数据库测试连接；缺失导致的跳过不能算通过。
- `RUN_LIVE_E2E`：真实浏览器写入的显式开关。

## 5. 已知限制与证据边界

- 单 JVM 单线程执行器 + PostgreSQL advisory lock；不支持横向扩容。
- 确认/组件/能力快照等部分存于任务 JSONB；初始化 SQL 不是完整迁移平台。
- OpenAPI 与实际 Map DTO/Controller 尚无全接口自动契约测试；DRIFT-10 已登记默认 limit 和时间格式差异。
- V-015 至 V-020：崩溃窗口、复杂批量恢复、长上下文/性能、全接口契约、恢复/回滚演练、版本升级兼容仍无完整专项证据。
- 后续 RAG、OKR、真实系统适配只存在路线/设计，不能据设计文档声明可用。
