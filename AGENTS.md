# Agent 接管约定

本项目是国新国际智能小秘的模拟/试点工程。先读 `HANDOFF.md`、`specs/README.md`、`docs/V0.1-验证记录.md`，再读对应业务代码。用户最新指令优先；不要重复要求批准已经授权的开发和本地部署。

## 范围和事实

- 当前交付 V0.1：Java21、Spring Boot、LangChain4j、LangGraph4j、Qwen；证照填单/草稿/提审、本人待办筛选/确认审核、历史和追溯。
- PostgreSQL、应用运行使用 Docker Compose。测试可使用已有编译工具，但不能悄悄改成本机数据库或 H2。
- RAG、完整 OKR、真实 SSO/低代码系统适配属于后续版本；不虚构已实现能力或简历评测结果。
- 本人待办按 assignee 过滤，证照/草稿按 owner 过滤。Actor 仅来自后端 session。

## 开发约定

1. 按 SDD 先更新对应 spec/验收/任务，再改代码；修复已有 spec 的 bug 不需要重新征求已授权范围的许可。
2. domain/application 定义共享契约；Agent 与业务模块不能相互绕过端口调用。正式提交和审核经过服务端确认，模型不能制造授权。
3. 读工具可自动运行；写入重试必须复用幂等键。结果未知先核实，不以新键重提。Checkpoint 不是业务回滚。
4. 修改模型、Skill、Prompt、表单 Schema 或 Workflow 要检查版本、已有任务和确认快照兼容性。保留结构化业务事实，不只依赖聊天摘要。
5. 发生业务成功而叙述模型失败时，保留真实回执。取消不意味着撤销已发生业务。
6. 多 Agent 开发先分配独立目录，改共享端口前协商。用户已授权模块分工；不要并行修改同一个文件。

## 验证和安全

- 检查 `docs/V0.1-验证记录.md` 的已执行状态，不能把 gated/skipped 测试称为通过。
- 测试层次：Agent离线、真实PostgreSQL业务、Boot HTTP集成、显式真实Qwen、API桩浏览器、真实浏览器，分别记录。
- 演示账号密码仅用于 local-demo。真实Key仅环境变量或忽略的.env；不要输出 `docker compose config` 的展开结果，用 `config --quiet`。
- 不删除或重建已有数据库卷，不运行 `down -v`，不自动恢复覆盖当前库，不自动重启Windows。先做可审查的备份/变更方案，明确破坏性操作再按用户授权处理。
- 不修改全局DNS、hosts、APPDATA、JAVA_HOME来绕过本机环境问题。已有Docker进程目录隔离和系统代理继承在 `scripts/docker-env.ps1`。
- 不公开发布、推送远端或发送外部消息，除非用户明确要求。不要替用户接受新业务审批；演示测试只操作明确的模拟记录。

## 本机特别事项

Windows用户目录有重定向，Docker配置原子重命名会报跨盘。用 `scripts/start-docker.ps1` 启动，或先 dot-source docker-env 再执行Docker命令；这只改当前进程。

`java` 是21，但默认 `mvn` 的 JAVA_HOME可能仍指向8；使用Docker构建或只为当前进程选择现有21，不能改系统Java设置。官方镜像大层下载可能截断，可用已验证的 `scripts/pull_official_image.py` 分块校验导入官方镜像，不能换成未知镜像站。

交接文档必须说明当前运行地址、镜像/产物、通过的测试、未验证项、已知限制和下一个可执行步骤。
