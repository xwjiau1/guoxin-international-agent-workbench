# V0.1 后端验证摘要

日期：2026-11-12。以下为实际执行结果；数据库使用Docker PostgreSQL16.4，应用演示使用真实Qwen，未以H2替代PG或以测试解释器代替模型验收。

| 测试 | 数量与结果 | 主要覆盖 |
| --- | --- | --- |
| WorkflowAgentRuntimeTest | 9项通过 | 草稿、确认、过期/重复、权限、结果核实、任务隔离、同run重放、取消 |
| MockBusinessServiceTest | 11项通过，无跳过 | owner/assignee、日期统计、字段/版本、幂等、响应丢失、审批CAS、证照并发占用 |
| WorkbenchIntegrationTest | 3项通过，无跳过 | session/CSRF、消息去重、跨人隔离、草稿关联、实际Checkpoint写入 |
| QwenLiveTest | 修复后2项通过，无跳过 | 真实待办工具迭代及流式叙述、带本人资料和表单元数据的自然证照输入 |
| 全量package | 通过 | 所有模块编译、单测、Boot可执行JAR入口及依赖打包 |
| 容器重启smoke | 通过 | 同一认证session和已建conversation重启后可读 |

QwenLiveTest原先只有待办覆盖；浏览器证照输入暴露模型AUTO模式直接回答的问题后，已改为解释阶段REQUIRED工具约束及协议纠错，并新增证照回归。原1项与新增2项存在覆盖重叠，不合并成3项独立模型测试。

详细测试源位于各模块 src/test，原始本机日志在忽略的.runtime，JUnit XML在各模块target/surefire-reports；新机器不依赖这些缓存，按运维手册复跑并记录新结果。

当前尚未专项通过：完整进程崩溃窗口/批量部分失败交错、性能压测、上下文摘要质量、全OpenAPI契约一致性、旧版本迁移兼容及真实恢复演练。设计或已有代码不等同于这些专项已验收。
