# V0.1 业务与行为规格

版本：0.1；基线日期：2026-11-12。状态：本机模拟范围已发布；模型协议修复后的真实Qwen回归、重新package、部署及证照/待办真实浏览器复测均已完成。发布状态与未验证专项以[验证记录](../../docs/V0.1-验证记录.md)为准。

## 1. 目标、范围与排除项

为模拟/试点构建可读源码、可运行的Java Agent工作台。真实调用Qwen，通过多轮对话和组件办理模拟企业业务；模拟底座的单据、草稿、流程和审核结果必须实际持久化。

首版：证照办理/借用填单、草稿、提交；当前处理人为本人的出差、报销、因私出国（境）、因私证照待办查询/筛选/审核；对话历史、执行追踪及异常恢复。普通员工为统一业务身份。

不含：真实企业制度/员工数据、RAG与评测、完整OKR、文件上传、真实SSO/低代码接口、完整低代码设计器、多实例高可用、生产上线承诺。公共政策问答未启用时不能伪装已检索知识库。

## 2. 功能需求及实现映射

路径均相对仓库根目录。完整协议见[OpenAPI](../../docs/contracts/workbench.openapi.json)及[组件设计](../../docs/02-接口与组件协议.md)。

| 编号 | 必须满足的行为 | 实现入口 | 当前证据 |
| --- | --- | --- | --- |
| FR-001 | Actor来自服务端session；本人证照/草稿按owner，本人待办按assignee；其他用户资源不泄露 | AuthFilter/AuthService、AgentStore、MockBusinessService | Boot3、业务PG11覆盖部分 |
| FR-002 | 真实Qwen解释阶段生成结构化准备/只读工具调用，结果反馈模型后迭代；无结构化结果不得当作业务成功 | QwenInterpreter、WorkflowAgentRuntime | todo与护照2项真实Qwen回归通过；证照真实浏览器V-012通过 |
| FR-003 | 按能力读取办理说明与工具，解释与写入分离；模型不能制造授权或覆盖身份 | capabilities、loadSkill、QwenInterpreter工具白名单 | 代码存在；注册治理限制见plan |
| FR-004 | 查询本人资料及表单元数据，保留字段来源，逐轮补齐/修改缺项；不猜日期与用途 | loadContext/credential/form_validate、FIELD_COLLECTION | Agent离线覆盖；证照真实浏览器V-012通过；Boot覆盖范围以V-005为限 |
| FR-005 | 可保存不完整草稿并恢复；已填内容仍需合法，更新/提交检查草稿版本 | saveDraft、draft_save/draft_get、草稿入口 | Agent离线、业务PG、Boot草稿通过 |
| FR-006 | 完整资料生成预览，经有效确认才正式提交；返回实际单据号/流程号/状态 | newConfirmation/confirm、submit_application | Agent与业务PG相关用例通过；证照真实浏览器V-012通过 |
| FR-007 | 待办按类型、明确日期字段、起止范围和关键词筛选；统计来自同一业务筛选对象 | todo_search/todo_statistics、TODO_SELECTION | 业务PG有日期/范围用例；真实浏览器结果见验证记录 |
| FR-008 | 按用户反馈勾选、排除、同意或驳回；意见/动作/范围在组件明确展示后确认 | UPDATE_REVIEW、APPROVAL_PREVIEW、review_todo | 业务PG与API桩覆盖；浏览器独立记录 |
| FR-009 | 确认绑定服务端快照，修改内容使旧确认失效；重复确认或重复消息不重复执行 | pendingConfirmation、RunCoordinator.accept、幂等回执 | Agent9、Boot去重、业务PG覆盖 |
| FR-010 | 批量逐项事务与逐项结果，允许部分成功；重试不重做已成功条目 | confirm REVIEW、itemResults、review_todo | 实现存在，完整批量故障浏览器验证待补 |
| FR-011 | 暂停/取消停止后续调用；已发写入核实结果，不声称撤销；支持继续任务 | ExecutionEvents.isCancellationRequested、工具网关 | Agent取消回归；进程中批量取消待专项 |
| FR-012 | 参数问题回收集；暂时故障有限退避；未知写入先查回执；保留恢复入口 | call、handleWriteFailure、operation_get | Agent响应丢失、业务PG提交回执等通过 |
| FR-013 | 流式文本、组件、历史、草稿和任务可回取；执行日志关联任务/run/工具/attempt | SSE、AgentStore.events、前端api/App/Cards | 构建/API桩已过；真实浏览器按链路记录 |
| FR-014 | 长会话保留最近消息和旧用户意图摘要，结构化字段/确认/回执不依赖摘要 | remember、任务state | 简化实现；无长会话专项结果 |

以上“覆盖”不是全需求通过。自动化类位于backend各模块src/test；前端桩与真实浏览器分别在frontend/tests/workbench.spec.ts、live-workbench.spec.ts。

## 3. 非功能需求

| 编号 | 约束 | 当前实现/验证边界 |
| --- | --- | --- |
| NFR-001 权限 | 登录/CSRF、资源所有权与操作人检查必须在后端执行 | session哈希存库；Boot与业务PG有验证；不是生产SSO |
| NFR-002 持久化 | 受理命令落库后才返回202，刷新/断流不触发业务重提 | PostgreSQL命令、任务、消息、事件；跨整容器崩溃专项待补 |
| NFR-003 防重 | 稳定operationId绑定请求摘要，重复键不同内容拒绝；同run重放不得执行新工具 | 数据库回执+lastCompletedRunId；Agent/业务PG回归通过 |
| NFR-004 有限执行 | 模型和工具/时间预算、网络重试有上限；用户等待不占持续请求 | 模型最多8次含总结，读取工具20次，run180秒，工具最多3次尝试；批量最多100项 |
| NFR-005 可追溯 | 保留节点、模型/工具、错误/重试、字段来源、确认和回执事实 | gxa_events与任务JSON；非完整独立span平台，见漂移 |
| NFR-006 密钥 | Key仅服务端运行环境；日志、前端、镜像构建参数不可含Key | 环境变量/忽略.env；不导出展开的compose配置 |
| NFR-007 部署 | 应用及PostgreSQL在Docker；端口仅本机暴露；不得自动删卷 | 三容器已运行；运维按HANDOFF和运行文档 |
| NFR-008 可维护 | 模块通过端口集成；规格、契约、证据与源码可接管 | Maven多模块、SDD任务与交接文档；未搭完整迁移/配置平台 |

不承诺当前无实测支持的吞吐、延迟、成功率或权限泄露率百分比。简历既有数字不作为种子指标。

## 4. 恢复与幂等不变量

- INV-01：认证session、conversationId、taskId、runId不能互相当作身份；执行Actor不从模型参数获得。
- INV-02：CONFIRM只引用当前用户可见的服务端快照；范围/动作/值变化不能沿用旧授权。
- INV-03：同一写入重试复用operationId；相同键不同请求拒绝，修改参数是新操作并重新确认。
- INV-04：写入超时不等于失败；先查询原回执，不以新键重提。
- INV-05：lastCompletedRunId命中则返回既有结果，不重做模型/工具；落后Checkpoint不能撤销或重复业务副作用。
- INV-06：批量成功项不重试；未知项不能伪装SKIPPED；取消仅阻止未发出的后续操作。
- INV-07：叙述模型失败不能把已成功业务改成失败；单据提交、审批通过和实际证照交接是三个事实。
- INV-08：草稿保存不创建审批；草稿版本冲突、证照在途占用必须由业务层拒绝。

## 5. Gherkin验收场景

场景定义不代表已经执行；对应结果只能在tasks与验证记录更新。

```gherkin
Scenario: AC-01 自然语言借护照且日期未提供
  Given 员工已登录且本人资料与表单元数据可查询
  When 输入“我想借护照去日本旅游，请帮我准备申请。”
  Then 解释阶段必须返回结构化CREDENTIAL/BORROW/PASSPORT参数
  And 未提供的出发、返程、归还日期保持缺失
  And 展示补充资料组件而非提交成功

Scenario: AC-02 草稿保存与恢复
  Given 申请仍缺少提交必填项
  When 保存草稿并刷新后继续原任务
  Then 草稿编号、本人字段和已填内容可查询
  And 不存在由本次保存创建的审批流程

Scenario: AC-03 确认提交与重复点击
  Given 已校验的单据预览和有效确认快照
  When 员工确认并重复发送同一确认
  Then 只有一个对应单据和审批流程
  And 返回原回执及待审批状态

Scenario: AC-04 本人待办和范围调整
  Given 本人及其他员工均有待办且申请人可能不同
  When 按类型和日期筛选并排除一项后确认同意
  Then 只处理最终快照中当前处理人为本人的条目
  And 未选条目和其他处理人的任务不变化

Scenario: AC-05 驳回与筛选修正
  When 输入“不同意这笔报销，金额不对”
  Then 准备驳回动作和意见并展示确认
  When 输入“不对，只看上周”
  Then 修改筛选而非自动驳回

Scenario: AC-06 成功提交但响应丢失
  Given 原operationId对应业务事务已提交
  When 调用方超时并恢复执行
  Then 先核实原回执且不生成第二张单据

Scenario: AC-07 跨用户与并发草稿
  Given 草稿归另一员工或版本已被更新
  When 当前请求试图读取、保存或提交旧草稿
  Then 后端拒绝未授权访问或版本冲突
  And 不覆盖新数据

Scenario: AC-08 取消和未知操作
  Given 一批审核中部分已成功且部分尚未发出
  When 员工要求停止
  Then 不发起新审核或写入重试
  And 保留真实成功结果并核实未知项
```
