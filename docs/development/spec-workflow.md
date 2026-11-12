# Spec 工作流与变更管理

> 触发：M/L 任务、上游变更或需持续跟踪的 Bug。S 任务走 `CLAUDE.md` 的轻量路径，但不得省略既有行为的需求编号、回归验收与证据。

## 1. 工件与目录

```text
需求 proposal/spec → 设计 design/plan → 契约 contracts → 任务 tasks → 代码 → 验证
                              └─ 重要取舍记录 ADR
```

- 已发布版本基线保留在 `specs/<version>/`，如 V0.1 的 `spec.md`、`plan.md`、`tasks.md`。
- M 变更使用 `specs/changes/<change-id>/spec.md` 一份轻量 Spec。
- L 变更在 `specs/changes/<change-id>/` 拆分 `proposal.md`、`design.md`、`contracts/`、`tasks.md`、`verification.md`；无接口变化时引用已有契约，不创建空契约。
- 重要技术取舍写 `docs/decisions/ADR-<id>.md`。变更验收后同步对应版本 spec/plan/tasks 和 `specs/roadmap.md` 状态；不通过改路线图冒充交付。
- 契约包括 Java 端口/DTO、HTTP/OpenAPI、SSE、组件、工具、表单/Workflow、数据库与任务状态；不是只有 HTTP 才算契约。

模板见 `docs/templates/README.md`。占位符不是项目事实，无关章节可写“不适用”及理由。

## 2. 阶段与门禁

沿用本项目状态：`SPEC_DRAFT → PLANNED → IMPLEMENTING → IMPLEMENTED → VERIFIED_PARTIAL / VERIFIED → RELEASED`。文档变更无需应用发布时可在验证后标 `VERIFIED (docs-only)`，不虚构 RELEASED。

| 阶段出口 | 必须具备 |
| --- | --- |
| SPEC_DRAFT → PLANNED | 需求/变更 ID、目标、范围、非目标、Given/When/Then 或等价 AC、权限与副作用边界；关键歧义已解决 |
| PLANNED → IMPLEMENTING | 模块职责、关键流程、生产者/消费者、输入输出、错误、版本兼容、数据/配置/迁移/回滚、重试与预算已明确 |
| IMPLEMENTING → IMPLEMENTED | tasks 有依赖、所有者、允许路径和验证方式；代码与必要测试存在且可构建；不代表验收通过 |
| IMPLEMENTED → VERIFIED_PARTIAL | 已执行部分必要测试，实际命令、环境、结果、证据和未执行项已记录 |
| VERIFIED_PARTIAL → VERIFIED | 本次必需 AC 全部有证据，无未处理阻断；drift、残余风险及例外已明确 |
| VERIFIED → RELEASED | 新产物/镜像与源码一致，备份和迁移方案就绪，部署及 smoke/live 完成，发布清单、验证记录和 HANDOFF 已更新 |

已有授权范围内连续推进，不逐阶段重复询问。关键业务歧义、范围扩大、真实外部写入或破坏性操作才暂停受影响工作并请求决定。

## 3. 编号与追踪

- 版本需求沿用 `FR-xxx`、`NFR-xxx`、`INV-xx`、`AC-xx`；实现偏差用 `DRIFT-xx`，实施/验证任务用 `T-xxx`、`V-xxx`。
- 新 change 使用稳定、唯一的 `<change-id>`；其内部任务和 AC 必须在所属 change 上下文中可追踪，不与 V0.1 历史编号混用。
- 每个任务关联需求/AC、契约、允许修改路径、依赖和验证。勾选只代表该栏事实，代码完成不自动勾选验证。
- spec drift 记录“计划能力 / 当前实现 / 影响 / 下一步”。实际缺陷不得用 drift 豁免权限、确认、幂等等不变量。

## 4. 四种变更路径

总规则：先复现或澄清，从**受影响的最上游**进入，再逐层检查下游；不是每层都要改。

| 路径 | 判断与起点 | 下游动作 |
| --- | --- | --- |
| 1. 结果不符合预期 | 先复现并对照规范。规范正确则从代码/测试进入；规范遗漏转 2 或 3 | 修实现并补回归，不倒改正确需求；登记根因和证据 |
| 2. 需求改变 | 从 proposal/spec 更新目标、范围、权限、副作用与 AC | 检查 design/contracts/tasks/代码/验证，使失效任务与旧证据重新打开 |
| 3. 需求不变，方案或接口变化 | 从 design/contracts 进入，重要取舍记 ADR | 检查全部调用方、旧任务/确认、数据迁移与回滚，再改实现和测试 |
| 4. 仅任务粒度或顺序调整 | 从 tasks 调整依赖、所有权和验证安排 | 保留已有成果；若职责或接口改变则转 3 |

M/L 在 change 中记录触发事实、路径、最上游修改点、受影响工件/调用方/任务、无需修改的层及理由和重验范围。

## 5. 实现与兼容性检查

1. 执行前报任务 ID，读取对应 Spec、契约、代码与测试；依赖未就绪不启动。
2. Bug 先建立与根因一致的失败复现，再最小修复；不能自动化时保留可复核的修前/修后步骤。
3. 权限、Actor、确认、防重、取消、未知结果和业务事实等 INV 必须逐条检查；不能用 Prompt 或前端修改代替服务端约束。
4. Prompt、Skill、Workflow、表单 Schema、任务 JSON 或表结构变化需明确版本、旧任务/草稿/确认快照、在途 operationId、迁移和旧镜像回滚兼容性。
5. API/组件变化检查 Controller、OpenAPI、客户端和 SSE；数据库变化在隔离 PostgreSQL 验证，不清空演示数据。
6. 复核每行差异的任务依据、临时调试残留、秘密和本次产生的孤儿代码。

## 6. Bug 闭环与验证

M/L 中相关 Bug 建 `T-xxx.B-01`，记录发现阶段、根因阶段、四路径分类、修前失败、最上游修复和修后回归；父任务相关 Bug 未关闭不能完成。S 可在原任务记录中合并，不强制新文件。无关问题只登记，不擅自扩大范围。

按变更选择六层测试，并分别记录：Agent 离线、真实 PostgreSQL、Boot HTTP 集成、显式真实 Qwen、API 桩浏览器、真实浏览器。每条结果包含命令、环境、源码/产物版本、预期、实际和证据位置。

- `pass`：实际执行且满足预期；`fail`：已执行未满足；`blocked`：因明确条件无法执行；`skipped/gated`：框架或开关跳过；`not_run`：本次未执行。
- 跳过、设计场景、历史结果、API 桩、test 模型均不得改写成当前真实链路通过。
- 存量失败需基线证据。相关失败未解决或未获明确例外时，不标 VERIFIED。
- 发布验收还需产物一致性、数据库备份/迁移、部署、smoke/live 和回滚边界；备份生成不等于恢复通过。

每次交接至少留下：需求/变更 ID、修改文件、契约/数据库/配置变化、测试与未执行项、源码对应产物/镜像、部署/回滚、已知限制和下一个可执行步骤。
