# 智能小秘工作台前端

React + TypeScript + Vite。应用运行统一使用根目录 Docker Compose，前端容器通过 Nginx 将 `/api` 转发给后端容器。

```powershell
docker compose up --build -d
# 以下仅为本机静态编译与浏览器测试工具，不启动本机应用服务
cd frontend
npm ci
npm run build
npx playwright test
```

工作台地址 `http://127.0.0.1:5173`。真实业务需要 Compose 中后端与数据库启动；前端不存储模型 API Key。Playwright 不会自动启动本机 Vite，请先启动 Docker 服务。

员工演示账号：DEMO001 / DEMO002 / DEMO003，密码 Demo@123。仅用于本地模拟环境。

测试使用本机 Microsoft Edge。`tests/workbench.spec.ts` 是明确使用 API 桩的浏览器交互测试，验证确认引用、字段提交、待办选择与失效卡片；不能代替真实 Qwen 和数据库的端到端验收。

流式连接按持久化游标恢复；断开连接不会重新发送业务请求。页面刷新从服务端加载消息、任务和组件，确认动作只发送后端生成的 confirmationId。

真实 Docker 端到端验收（会真实写入本地模拟数据库，绝不拦截或模拟 API）：

```powershell
$env:RUN_LIVE_E2E = '1'
npx playwright test tests/live-workbench.spec.ts
Remove-Item Env:RUN_LIVE_E2E
```

`live-workbench.spec.ts` 覆盖不完整草稿保存、刷新恢复、表单补齐、确认提交，以及本人待办选择范围和审核。模型使用当前 Docker 后端配置；若后端显式配置测试模型，结果只能标记为业务端到端验收，不能标记为真实 Qwen 验收。
