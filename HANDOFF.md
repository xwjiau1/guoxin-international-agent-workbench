# 项目接管入口

更新：2026-11-12。V0.1模拟初版已完成两条真实浏览器闭环。本文件用于让没有聊天上下文的 Agent 接管，具体证据以 `docs/V0.1-验证记录.md` 为准。

## 1. 先读什么

1. `AGENTS.md`：开发、数据和环境约束。
2. `specs/README.md` 和 `specs/v0.1/spec.md`：SDD流程、需求编号和验收。
3. `docs/V0.1-验证记录.md`：已执行结果，不能拿设计当验证。
4. `docs/运维与部署手册.md`：运行、备份、迭代部署、回滚。
5. `docs/01-工程架构与Agent状态机.md` 至 `05-RAG与后续扩展.md`：详细设计；遇到差异查看spec的实现映射与限制。

## 2. 当前运行方式

工作台 `http://localhost:5173`；后端 `http://localhost:8080`，健康检查 `/actuator/health`；数据库只在本机55432暴露。

本次发布标识为v0.1-20261112；产物哈希和实际镜像ID保存在 `docs/releases/v0.1.json`。离线镜像归档为 D:\GuoxinAssistantRuntime\releases\v0.1-images.tar，数据库备份与验证边界见运维手册及验证记录。

三服务均为Docker容器：guoxin-assistant-frontend-1、guoxin-assistant-backend-1、guoxin-assistant-postgres-1。服务数据保存在命名卷；会话、草稿、操作状态与业务回执在PostgreSQL中。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start.ps1 -Prebuilt
```

当前采用已验证JAR和dist构建运行镜像。修改源码后先重新构建产物，再 prepare-prebuilt；不得把旧dist/JAR当成新代码。正常网络环境可用默认源码Docker构建，区别见运维手册。

演示账号 DEMO001/DEMO002/DEMO003，密码 Demo@123。全部为虚构员工和模拟制度/单据，不是企业真实数据。当前正常路径使用真实Qwen API；只有显式test模型用于自动化确定性验收。

## 3. 代码定位

| 目录/类 | 修改入口 |
| --- | --- |
| backend/assistant-domain | Actor、ToolResult |
| backend/assistant-application | AgentRuntime、BusinessPort、RunRequest/Result、ExecutionEvents |
| backend/assistant-agent | WorkflowAgentRuntime（图/业务步骤）、QwenInterpreter（工具循环/流式模型） |
| backend/assistant-business-mock | MockBusinessService、schema-business.sql、真实PG业务测试 |
| backend/assistant-infrastructure | AgentStore、schema-agent.sql、持久化与事件 |
| backend/assistant-boot | WorkbenchController、AuthFilter/Service、RunCoordinator、Boot集成测试 |
| frontend/src | 对话工作台、API/SSE、业务卡片 |
| frontend/tests | API桩与live-workbench真实端到端，不能混报 |
| capabilities、prompts | 两项Skill、Workflow描述及Prompt版本资源 |
| deploy、compose*.yaml、scripts | 源码/产物容器构建、调试、启动和主机兼容处理 |

调试调用路径：WorkbenchController → RunCoordinator → AgentRuntime → LangGraph4j节点 → Qwen/ToolGateway → BusinessPort → 模拟底座 → 数据库/回执 → 持久化事件/SSE。

## 4. 关键实现边界

- 图检查点按taskId:runId保存本轮；跨轮恢复由完整应用任务快照完成，并非保持HTTP线程或把旧图副作用回放。
- lastCompletedRunId防止“节点完成但命令未落完成状态”时重启重放；业务提交另有稳定operationId与回执事务。
- 首版确认、字段来源、能力快照、组件和逐项结果存任务JSON，并非都已拆独立管理表。
- 单JVM任务执行器通过数据库advisory lock限制多实例；不支持横向扩容。
- 待办批量逐项事务，允许部分成功；完成提单不代表审批通过，审批通过也不代表证照已交接。
- 初始化SQL是幂等建表，不是完整迁移平台。新增字段必须设计版本迁移，不可假定CREATE IF NOT EXISTS会修改旧表。

## 5. 迭代闭环

建立需求变更 → spec写需求/边界/验收 → plan写接口/数据/兼容性 → tasks拆分实现与验证 → 按目录实施 → 运行对应测试 → 更新契约和证据 → 产物与镜像标记 → 备份 → 部署 → smoke/live验收 → 更新接管记录。

每次交接至少留下：修改需求编号、代码入口、配置变化、测试命令与结果、是否需要迁移、已知问题、部署镜像标识、回滚步骤。未完成的工作明确列出，不靠聊天记录传递。

## 6. 当前证据与后续重点

后端全量package、9项Agent离线、11项真实PG业务、3项Boot+PG集成、2项真实Qwen、证照及待办两条真实浏览器流程、后端重启持久性已通过。数据库备份已生成并校验，恢复/回滚演练尚未执行。最终证据见验证记录。

后续按 `specs/roadmap.md` 推进接口发布治理、RAG复现与评测、完整OKR/证照页面、真实系统适配。接管时先检查容器健康和源码/产物是否一致，再决定新工作；不要重新下载或安装已存在的环境。
