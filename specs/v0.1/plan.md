# V0.1 实现计划与实际架构

更新：2026-11-12。本文描述当前代码，而非将早期详细设计全部视为已实现。

## 1. 真实技术基线

Java21、Spring Boot3.5.7、LangChain4j1.8.0、LangGraph4j1.8.4、PostgreSQL16.4；React/TypeScript/Vite前端。版本来源是backend/pom.xml、frontend/package-lock.json及Compose，不以远程最新版本替换已验证依赖。

模块：domain记录Actor/ToolResult；application定义AgentRuntime/BusinessPort和事件端口；agent实现模型及图；business-mock实现持久化低代码模拟底座；infrastructure实现AgentStore；boot负责HTTP、认证与持久命令协调。前端api.ts/App.tsx/Cards.tsx消费HTTP和SSE。

图为load_context → understand → execute_workflow。模型的只读工具循环在understand节点内；正式写入只在确定性Workflow中，经服务端确认后调用BusinessPort。

## 2. 当前请求与执行路径

1. AuthFilter从Cookie对应session取得Actor，修改请求校验CSRF。
2. Controller将消息或组件动作交给RunCoordinator；数据库事务保存命令、消息、run.accepted后返回202。
3. 单线程执行器领取持久命令；数据库全局advisory lock防止同库两个实例同时执行。启动恢复RUNNING命令为QUEUED。
4. WorkflowAgentRuntime读取任务state，加载资料与Skill，调用Qwen解释；模型可查询只读工具，再输出prepare_business_task。
5. 缺字段返回组件并持久化；保存草稿或确认写入使用稳定操作编号和业务回执事务。
6. 每节点保存应用任务快照，native PostgresSaver按taskId:runId保存图节点。成功节点写lastCompletedRunId。
7. 普通文字run结束后可作无工具流式总结；失败回退确定性回复。按钮动作不增加该模型调用。
8. 最终消息、组件、任务与命令完成事件在事务中落库；SSE按会话单调序号重放，断线只重新订阅。

## 3. 数据与核心决策

gxa_sessions/conversations/tasks/messages/commands/events承载Agent应用状态。确认快照、字段来源、能力版本、摘要及逐项操作结果嵌在gxa_tasks.state JSONB中。业务独立使用gx_biz_*员工、证照、草稿、单据、流程、待办、审批、占用及操作回执表。真实表定义以schema-agent.sql/schema-business.sql为准。

读取参数错误回模型修正或用户补齐；鉴权错误停止；临时网络/SQLState连接故障及可恢复冲突有限重试；写入结果未知先operation_get，业务适配器复用原键。业务确认快照不作为模型自由生成参数。

凭证只在后端环境；演示制度与用户均为虚构数据。文档、源码、缓存不应保存实际Key。初始化只在空业务表创建演示资料，不把启动当作重置数据库。

## 4. Spec drift与明确限制

| 编号 | 早期规划 | 当前事实与影响 | 后续处置 |
| --- | --- | --- | --- |
| DRIFT-01 | 租约、fence、可扩展执行器 | 全局advisory lock+单JVM单线程；不支持横向扩容 | 若扩展并发，先更新NFR/一致性规格再改调度 |
| DRIFT-02 | 多张独立确认/组件/操作管理表 | 任务JSON存确认和组件；业务表存幂等回执 | 当前保持一致，不宣称具备独立管理后台 |
| DRIFT-03 | Skill发布目录、校验和、不可变注册 | 两项固定版本文件；运行读取instructions.md并按代码白名单暴露工具，manifest非完整发布系统 | V0.2接口发布与版本治理 |
| DRIFT-04 | 动态Prompt模板管理 | 提示主要在QwenInterpreter中，prompts文件为镜像说明 | 改动需同时审查代码/资源；未来单一模板来源 |
| DRIFT-05 | 完整迁移、熔断、日志保留策略 | 初始化SQL及少量兼容ALTER；没有完整迁移平台、独立熔断器或自动保留期清理 | 明确迁移方案，不把设计默认值当运行功能 |
| DRIFT-06 | 完整span层级追踪 | 持久事件关联task/run/toolCallId/attempt；未建设独立span树与外部追踪平台 | 增强时保持业务审计和隐私边界 |
| DRIFT-07 | 强契约与所有Schema约束自动校验 | OpenAPI是设计契约，运行Controller和Map DTO需人工及测试对照；组件data为开放对象 | 后续补契约测试，不能声称已有全自动一致性 |
| DRIFT-08 | 完整长上下文压缩 | 最近12条+最多20条旧用户意图摘录；结构化事实保留，非完整语义摘要服务 | 长对话/预算专项待补 |
| DRIFT-09 | 完整任务切换/旁路 | 一个会话主要沿active_task推进，业务kind切换会清理旧业务字段；未提供完整多任务选择与长期挂起管理 | 扩展前补任务归属与切换验收 |
| DRIFT-10 | 全部日期/游标约束与契约对齐 | Controller历史消息默认limit=100而OpenAPI为20；部分会话/消息时间直接Timestamp.toString而契约为date-time；无事件保留清理及EVENTS_EXPIRED完整策略 | 补接口级格式/默认值/游标测试并收敛，不能改文案伪装已验证 |

这些差异不是已通过的例外审批；涉及确认、权限、防重不变量的实际缺陷仍必须修复。初版范围允许简化管理功能，不允许绕过服务端业务约束。

## 5. 工具协议阻断修复 FR-002

真实浏览器暴露AUTO工具选择可仅返回文字，原同prompt重试不能保证准备结果。修复：解释请求REQUIRED，禁并行工具；到后期或协议修复时只暴露prepare；无工具返回添加纠错上下文，保持有限预算。叙述请求不带业务工具。

需验证两种真实输入：明确要求todo_search的待办查询，以及含profile/metadata上下文的自然护照申请。后者不得编造日期，必须进入结构化补齐流程。单模型测试通过后重打fat JAR、重建backend镜像，再由真实浏览器复测；旧镜像通过不代表新源码已部署。

## 6. 构建、部署与恢复

详见[HANDOFF](../../HANDOFF.md)和[运行说明](../../docs/06-运行与验证.md)。当前三服务Docker运行，提供源码构建与已验证JAR/dist的prebuilt路径。源码变更必须更新产物，prepare-prebuilt不会自动编译。

保留PostgreSQL命名卷；升级和回滚均需记录配置/Schema兼容性、镜像和数据恢复方式。Checkpoint不是备份，不能对已发生的审批作时间回滚。源码构建受官方镜像大层截断影响时可用官方分块校验导入，不改全局DNS或切未知镜像站。
