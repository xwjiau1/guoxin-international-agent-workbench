# 前端真实 Docker 端到端验收

日期：2026-11-12，Asia/Shanghai。执行工具：Playwright + 本机 Microsoft Edge；目标为 Docker Compose 前端 5173、Java 后端 8080 与 PostgreSQL。

本报告所述真实用例不拦截或模拟 API。模型使用 Docker 后端配置的真实 Qwen，业务数据为持久化模拟业务数据。

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 证照：登录→多轮收集→草稿→刷新恢复→补齐→确认提交 | 最终容器版本通过 | 用例 25.3 秒，总运行 26.4 秒；真实单据 GX-FED79127、流程 PROC-F2762204，业务状态 IN_APPROVAL |
| 待办：登录→本人出差待办→只选一条→预览→确认同意→业务回执 | 最终同一容器版本通过 | 用例 13.9 秒，总运行 14.9 秒；仅一项审核成功，回执 todoId 与选中项一致 |

两项在同一最终应用容器版本上分别通过独立 grep 执行，**不是一次完整 serial suite 的通过结果**。测试没有修改后端模型模式，没有 API 拦截或 Mock，也未启动本机 Vite。

最终任务 ID：证照 `a8f02772-8460-4f6b-ac6f-669e09f07ba5`；待办 `ae8ae12a-1af1-42e6-9bc7-0db4e8c9e3b6`。两项数据库任务状态均为 SUCCEEDED；证照业务单据仍处于审批中，和 Agent 的“提交完成”状态区分。

证照截图：[v0.1-credential-submitted.png](../screenshots/v0.1-credential-submitted.png)；待办截图：[v0.1-todo-approved.png](../screenshots/v0.1-todo-approved.png)。证照截图为 1440×960 浏览器当前视口，保持页头可见，聊天区域滚动到实际业务回执；截图复核只进行登录和读取，没有重复提单。

## 过程中的问题与修复

1. 首轮证照诊断任务 `3e86fa6c-7761-4492-b07f-ac50542de8a4` 在模型准备步骤失败，原因为 MODEL_TOOL_REQUIRED 被包装为 MODEL_UNAVAILABLE。后端调整工具调用协议并部署后，真实 Qwen 准备步骤正常。
2. 第二轮已完成保存草稿、刷新和 RESUME，但浏览器测试 `.last()` 在新组件上屏前选中了仍留在历史消息中的 disabled 表单；Playwright 持续等待旧元素启用，超时 8 分钟。服务端未重复提交、没有生成业务单据。测试定位限定为 `.card-container` 当前交互组件，保留所有业务断言，并将动作超时设为 30 秒。
3. 修复后最终证照完整链路通过；随后在同版本独立重跑待办链路通过。此前旧容器待办 19.0 秒的通过结果不计入最终版本两项通过证据。

当前界面保留历史卡片用于追溯，旧卡片明确不可操作；测试和人工操作都应使用当前办理区的有效组件。

执行命令（工作目录 frontend）：

```powershell
$env:RUN_LIVE_E2E = '1'
npx playwright test tests/live-workbench.spec.ts --grep 'credential collection'
npx playwright test tests/live-workbench.spec.ts --grep 'own todos'
Remove-Item Env:RUN_LIVE_E2E
```

自动化脚本为 `frontend/tests/live-workbench.spec.ts`。草稿和审批用例会真实改变本地模拟数据库，禁止指向生产环境。没有使用测试模型替代 Qwen。

此前 `workbench.spec.ts` 中 4 项 API 桩测试只证明浏览器交互契约，不计入本报告的真实链路通过数量。

最终封版说明：上述两项在同一功能镜像中独立通过。之后仅追加后端正常SSE断线异常处理（避免断线后再写JSON），重新package及部署；该最终镜像已通过健康、登录、SSE读取/断线和容器JAR哈希一致性smoke，未重复写库型浏览器流程。最终产物与镜像标识见[发布清单](../releases/v0.1.json)。
