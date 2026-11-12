# Spec 与交接模板索引

按需复制到 `specs/changes/<change-id>/`；已有等价结构优先复用，V0.1 基线不重排成模板。占位字段只是提示，无关项删除或写“不适用”及理由，不为填表创造需求或伪造用户确认。

- **S**：不新建整套文档；既有行为/Bug 在对应版本 spec/tasks 记录，纯文档可在当前任务简记。
- **M**：复制[轻量 Spec](lightweight-spec.md) 为 `specs/changes/<change-id>/spec.md`。
- **L**：在同一 change 目录使用下列独立工件；无新契约时引用已有契约，无需新建空文件。

| 模板 | 产出位置与用途 |
| --- | --- |
| [需求](proposal.md) | `proposal.md`：目标、范围、权限/副作用和验收 |
| [设计](design.md) | `design.md`：真实架构、职责、数据、兼容与风险 |
| [契约](contract.md) | `contracts/<interface>.md`：输入输出、不变量、错误和版本 |
| [任务与 Bug](tasks.md) | `tasks.md`：依赖、文件所有权、验证及 Bug 闭环 |
| [变更记录](changes.md) | `changes.md`：四路径和下游检查，可并入其他工件 |
| [验证](verification.md) | `verification.md`：六层测试的实际证据与未执行项 |
| [ADR](adr.md) | `docs/decisions/ADR-<id>.md`：重要技术取舍 |
| [派工与交接](agent-handoff.md) | 当前 change 或个人产出区：绑定范围、工作区与交付 |

状态和门禁遵循 `docs/development/spec-workflow.md`。`IMPLEMENTED` 不等于 `VERIFIED`，文档验证不等于应用 `RELEASED`。
