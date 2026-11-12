# 国新国际「智能小秘」Agent 工作台

Java 21 + LangChain4j + LangGraph4j 企业 Agent 工作台，Qwen API 驱动多轮对话。V0.1 聚焦证照申请草稿与提单、本人待办查询与确认代审核、执行追踪与异常恢复。业务接口使用有持久化状态的模拟企业底座。

运行统一使用 Docker Compose，详见 [运行与验证](docs/06-运行与验证.md)。

```powershell
docker compose up -d --build
```

工作台地址：http://localhost:5173 。演示登录 DEMO001 / Demo@123。模型 Key 通过当前环境的 QWEN_API_KEY 或 DASHSCOPE_API_KEY 注入，也可按 `.env.example` 创建本地 `.env`。

- [设计导航](docs/README.md)
- [实际验证状态](docs/V0.1-验证记录.md)
- [业务脚本与验收标准](docs/04-业务样例与验收.md)
- [工作台 API 契约](docs/contracts/workbench.openapi.json)
- [其他Agent接管入口](HANDOFF.md)与[协作约定](AGENTS.md)
- [Claude开发规则](CLAUDE.md)、[项目事实与命令索引](docs/project-context.md)
- [SDD规范与需求追踪](specs/README.md)
- [维护、迭代、部署及回滚手册](docs/运维与部署手册.md)

RAG、知识治理与完整 OKR 为后续迭代。当前实现不表示已达到简历示例中的评测百分比。
