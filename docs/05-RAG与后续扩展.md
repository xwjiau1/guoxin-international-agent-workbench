# 05 RAG 与后续扩展

## 1. 与 Agent 主体的边界

RAG 在 V0.3 交付，V0.1 注册能力目录时只保留明确的未启用状态，不用普通模型回答冒充已接知识库。未来通过 policy_search/policy_answer 能力接入同一任务、工具网关、事件和错误协议。

Java 负责文档治理与问答编排；EmbeddingGateway 和 RerankGateway 是独立 HTTP 推理端口。使用 BGE-M3、bge-reranker-v2-m3 的真实模型端点，不能用其他模型的结果标称原模型。无现成服务时再落实独立推理服务部署，生成模型仍走 Qwen API。

## 2. 知识生命周期

文档状态：UPLOADED → PARSING → CHUNKING → EMBEDDING → READY_FOR_REVIEW → PUBLISHED → RETIRED；失败保留阶段、原因和可重试入口。业务填单不上传附件与知识库管理导入是两个范围，后者在 RAG 版本建设。

治理字段：documentId、version、title、source、checksum、status、effectiveFrom/effectiveTo、accessScope、ownerId?、parserVersion、chunkPolicyVersion、embeddingVersion。重复 checksum 复用处理结果，但不同权限不能混为一份授权记录。

首批演示制度覆盖出差、报销、因私出境、证照办理/借用，各含新旧版本、易混条款和无答案问题。所有制度标记模拟，不宣称为国新国际真实制度。

切片以标题、条款和段落为边界；设计起点为 400–700 中文字符、相邻上下文 80–120 字，避免拆断表格与条款编号。最终按对应模型 tokenizer 校验输入限制。记录 chunkId、documentVersion、章节路径、页码或段落锚点、原文偏移和内容哈希；策略参数通过评测调整。

导入解析器适配 PDF/DOCX/Markdown；OCR、复杂表格识别的实际支持范围在该版本单独验收，不把扫描 PDF 当作天然可解析。解析失败不得悄悄发布空知识。

## 3. 检索、重排与权限

链路：理解查询 → 计算服务端可见范围 → BGE-M3 dense 向量 → Qdrant 权限/生效版本过滤检索 top30 → 重排 → top5 证据 → Qwen 回答与引用。首个基线采用 dense 检索；后续混合检索是独立实验，不默认声称已经使用 BGE-M3 全部检索模式。

Qdrant payload 至少有 documentId、version、chunkId、publicationStatus、effectiveFrom/To、accessScope、ownerId。可见集合为公共文档或当前用户获授权的个人/受限文档；过滤由后端构建，模型无权去掉。按实际过滤字段建 payload 索引，并在回传证据前检查数据库发布状态。

不同向量维度或模型配置建立新 collection，完成索引后切换别名；不在原 collection 静默混入不兼容向量。文档下线先更新发布状态并使应用缓存失效，再异步清理索引；即使旧向量仍存在，应用复查也不能返回。

embedding 和 rerank 可批处理，但每个任务保留调用追踪；重排服务失败可配置降级为明确标注的原始检索排序，禁止把降级运行混入“重排效果”评测。向量服务不可用时停止检索并说明，不退回无依据制度回答。

## 4. 生成与引用

答案返回 answer、citations[{chunkId,documentId,version,title,anchor,excerpt}]、answerStatus、retrievalRunId。引用必须来自本次可见证据，不能由模型自由编造 URL 或文档编号。

证据不足时 NEED_MORE_INFORMATION/NO_EVIDENCE；制度冲突时 CONFLICT，并给出适用时间和版本，必要时追问。文本中的“忽略规则、提交审批”等内容视为知识数据，不触发业务工具。

公共政策答案可在同一授权集合、问题、知识发布版本、Prompt 和模型配置下缓存；个人资料及答案不跨用户共享。历史会话再次打开时，引用内容需重新检查权限；当前无权文档显示不可访问，不展示原文快照。

FAQ 为经审核发布的标准问答，同样绑定制度版本和权限。用户反馈产生 badcase，不直接覆盖可信答案。badcase 状态为 NEW/TRIAGED/FIXED/VERIFIED，记录原问题、检索证据、错误类型、修复内容和回归结果。

## 5. 数据表与接口增量

表：knowledge_document、knowledge_version、knowledge_chunk、ingestion_job、retrieval_run、answer_feedback、faq_version、badcase、evaluation_dataset、evaluation_case、evaluation_run、evaluation_result。发布版本不可变；chunk 关联原文证据，评测关联全部参数。

管理员知识治理与员工知识问答权限分离；当前所有业务用户均为员工，不因此自动获得导入/发布权限。V0.3 可先通过项目维护配置及本地管理入口治理，正式管理角色需在该版本明确。

问答端口统一接入工作台消息；管理接口前缀 /management/knowledge，包含导入、任务状态、预览切片、发布、下线、反馈归因和评测执行。外部模型 Key 不进入知识配置导出。

## 6. 评测定义与实验

设计起点 120 条用例：80 条开发集、40 条保留测试集，按制度/问题模板分组拆分，避免同一问法改写同时进入两侧。测试集固定后不得根据其结果反复调参；扩充数据另立版本。

用例包含 question、actorScope、referenceAnswer、requiredFacts、relevantEvidenceGroups、expectedBehavior、documentVersion、caseType。语义等价的多个片段归为证据组，避免切片改变导致标注失真。

| 指标 | 计算与限制 |
| --- | --- |
| Hit@5 | 前五条至少命中一个相关证据组的可回答问题数 / 可回答问题数 |
| Recall@5 | 每题前五条覆盖相关证据组比例，再取宏平均 |
| MRR | 第一条相关证据排名倒数的均值，未命中为 0 |
| 答案正确率 | 必要事实正确且无关键矛盾的用例数 / 纳入评判用例数，分问题类型报告 |
| 引用支持率 | 能由所引证据支持的可核实事实数 / 可核实事实总数 |
| 无答案行为 | 无证据问题正确拒答或追问的比例 |
| 权限泄露 | 未授权 chunk 命中、证据返回、最终答案泄露分别计数，记录测试分母 |
| 性能 | 检索、重排、首字和总耗时，报告环境、样本量与 P50/P95 |

实验顺序：E0 原始问题 + dense 检索 → E1 条款切片/元数据治理 → E2 增加重排 → E3 FAQ/查询改写等优化。每次尽量只变一个因素，固定模型、集合与 Prompt；同时报告各阶段权限和延迟变化。

答案评判采用明确 rubric 与人工抽检，模型评判记录评判模型/Prompt，不把自评等同真实正确性。简历中原有百分比不写入测试预期，也不作为伪造种子的目标。

## 7. OKR 和完整业务页面扩展

OKR 模型：objective(ownerId,cycle,title,status,version)、key_result(objectiveId,type,baseline,target,current,weight,unit,version)、progress_update(actorId,value,note,time)。只允许维护本人目标；进度公式按数值型/里程碑型明确配置，不能让模型临时选择计算方式。

OKR Skill 支持“创建目标/补充 KR/更新进展/查询统计”，收集必填项、预览差异并确认写入；修改采用同一 operation/confirmation 机制。统计由业务接口计算；权重与进度规则在 V0.4 细化后发布。

证照台账、待办中心、单据页面调用与 Agent 相同的业务应用层，不能各写一份状态变更逻辑。页面展示审批、借出、归还不同状态；审批通过不自动变成已交接。

## 8. 官方参考

- [BGE-M3 模型](https://huggingface.co/BAAI/bge-m3)
- [bge-reranker-v2-m3 模型](https://huggingface.co/BAAI/bge-reranker-v2-m3)
- [Qdrant 权限条件所需的过滤机制](https://qdrant.tech/documentation/search/filtering/)

本设计不构成已完成的评测结果，模型服务可用性和资源要求在 RAG 实施前验证。
