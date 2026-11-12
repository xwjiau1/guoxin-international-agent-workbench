# 编码与模块边界

- 以现有 Java 21/Spring Boot、React/TypeScript 代码和锁定依赖为准；遵循所在文件风格，不顺手全仓格式化、升级依赖或引入新框架。注释解释约束和原因，不复述代码。
- 后端排版基线：Java 使用 google-java-format AOSP 四空格，POM 使用四空格缩进，SQL 按列/子句换行，YAML 保持两空格。格式化不得改变 import 内容、注释文字、依赖版本或 SQL 语义；基线及验证见 `specs/changes/QUAL-001-backend-formatting/spec.md`。
- 保持最小实现和清晰数据流。公共抽象必须有真实复用或端口职责依据，不为单次调用增加通用层，不让实现细节偷改业务规则。
- `assistant-domain`/`assistant-application` 定义共享契约；`assistant-agent` 负责模型与图编排，`assistant-business-mock` 实现模拟业务，`assistant-infrastructure` 持久化，`assistant-boot` 负责 HTTP、认证和协调。Agent 与业务模块不得绕过端口直接互调。
- Actor 只能由后端认证会话建立并沿可信调用链传递。不得接受模型或客户端传入的 actor/owner/assignee 作为授权依据；本人资源按 owner、本人待办按 assignee 过滤。
- 模型输出是不可信输入：使用工具白名单、参数校验、预算和错误分类。解释/只读与正式写入分离；提交、审批等写入必须经过服务端确认快照、确定性 Workflow 和业务层校验。
- 保留字段来源、能力版本、确认、逐项结果和业务回执等结构化事实，不只存聊天摘要。叙述失败不得覆盖已经成功落库的业务事实。
- 写操作使用绑定请求摘要的稳定 operationId；相同键不同请求拒绝。超时/结果未知先查原回执，不以新键重提；批量成功项不重做，取消只阻止尚未发出的操作。
- 数据访问坚持 PostgreSQL。初始化 SQL 不是迁移系统；新增/修改字段、索引或任务 JSON 需版本化兼容方案，不能假定 `CREATE IF NOT EXISTS` 会更新旧表，也不能改用 H2 凑测试。
- 前端只发送服务端生成的 confirmationId 等契约字段，不自行构造授权快照；SSE 断线按游标重取，不重新发送业务命令。模型 Key 和认证秘密不得进入 bundle。
- 修改接口时同步核对 Controller、OpenAPI、客户端、组件 Schema、错误语义及调用方；修改 Prompt/Skill/Workflow/表单 Schema 时同步核对版本、旧任务和确认失效。
