# QUAL-001 后端工程可读性格式化

状态：VERIFIED_PARTIAL（格式/结构及跳过测试构建通过，后端回归未运行）

日期：2026-11-12

基线：`b79abf4` 及工作区已有 GOV-001 未提交文档变更

关联：NFR-008 可维护性；变更路径1（代码可读性不符合维护需要，不改变正确业务规范）

## 需求与范围

### 目标

- QUAL-FR-001：整理 `backend/` 下21个Java主代码/测试文件的缩进、换行、参数与语句排版，提升审查和后续迭代可读性。
- QUAL-FR-002：整理7个Maven POM及2个PostgreSQL Schema资源的排版；检查 `application.yml`，已清晰的配置不做无意义改写。
- QUAL-FR-003：保持所有Java可执行token与注释文字、XML元素/属性/文本、SQL非空白token及YAML值不变，不修改业务行为、接口、依赖版本或数据库定义。长行注释允许仅作换行。
- QUAL-FR-004：记录实际格式/语法/结构检查与后端测试可用性，不能把环境阻塞或历史结果当本次测试通过。

### 非目标与边界

- 不修改前端、capabilities、prompts、Compose、部署脚本、API/组件契约、Prompt/Skill/Workflow、SQL语句语义或配置值。
- 不增删类/方法/字段/依赖，不调整import内容，不改注释文本，不顺手修复业务问题或升级依赖。
- 不格式化 `target/` 等生成物，不覆盖已有GOV-001文档差异，不提交、推送、部署或重启服务。
- 格式化工具仅放被忽略的 `.runtime/tools/`，不向项目POM增加插件或生产依赖，不修改系统Java/Docker设置。

## 设计与契约

- Java使用现有Temurin 21运行官方google-java-format，采用AOSP四空格排版；关闭import排序、未使用import删除、长字符串重排和Javadoc重排，仅允许空白/换行差异。
- POM使用本地XML解析/缩进工具，格式化前后比较元素路径、属性、文本和命名空间；不增加XML声明或改依赖。
- SQL仅对现有语句作等价缩进和逗号换行，格式化前后比较非空白token；不执行Schema、不连接或修改数据库。
- YAML已采用稳定两空格缩进，保留原值和顺序。
- 无Java、HTTP、SSE、组件、数据库、配置或部署契约变化；INV-01至INV-08的代码内容必须保持。

## 验收

- AC-QUAL-01：Given 原后端源码，When 完成格式化，Then 所有目标Java/POM/SQL可读性改善，YAML保持清晰，生成物不进入差异。
- AC-QUAL-02：Given 格式化前后文件，When 做忽略空白差异和XML/SQL结构比较，Then 无业务token、元素、属性、文本、值或依赖变化。
- AC-QUAL-03：Given 当前机器工具，When 运行可用检查，Then Java/XML解析和格式冪等检查及跳过测试Maven package通过；Agent离线/真实PG/Boot回归按用户要求不执行并明确记为not_run。
- AC-QUAL-04：Given 最终工作区，When 审查差异，Then 只包含QUAL-001排版与其Spec/证据以及此前已有变更，无秘密、生成物或无关重构。

## 任务

- T-QUAL-001（done）：完成接管、工作区保护、后端31个工程文件盘点及Java/Docker/Maven可用性检查。
- T-QUAL-002（done）：仅修改 `backend/**/*.java`、`backend/**/pom.xml`、`backend/pom.xml`、`backend/**/*.sql`；完成21个Java、7个POM和2个SQL格式化，YAML未改。
- T-QUAL-003（done）：完成可执行token/注释文字、XML/SQL结构和YAML内容比较；Java/XML二次格式化无新增差异。
- T-QUAL-004（not_run，用户本次明确排除）：BUILD-001已解除Maven缺失阻塞并完成六模块跳过测试package；Agent离线、真实PG和Boot HTTP回归仍未执行，不以编译成功替代测试结果。
- T-QUAL-005（done）：更新本Spec、spec导航、编码排版规则及HANDOFF第8/9节，披露未运行层、已构建本机JAR和未部署状态。

### T-QUAL-002.B-01 SQL手工排版类型笔误（已关闭）

- 发现/根因：格式化过程中手工展开SQL时，`gx_biz_workflow.document_id`一度误写为 `uuid`，原定义为 `varchar(64)`；属本任务排版引入问题，不是存量业务Bug。
- 修复与证据：立即恢复原类型，最终2个SQL均通过与HEAD的非空白token比较，确认没有残留字段类型或其他SQL定义变化。后续SQL排版必须执行自动token对比，不凭肉眼判断。

## 验证与变更

| 验证 | 方法 | 当前状态 | 证据/限制 |
| --- | --- | --- | --- |
| V-QUAL-001 | 接管、文件盘点、Git基线 | pass | 31个后端工程文件；已有GOV-001差异保持不动 |
| V-QUAL-002 | Java/XML/SQL纯格式与结构检查 | pass | 21个Java可执行token/注释文字、7个POM结构、2个SQL非空白token和1个YAML内容均与HEAD一致；Java/XML冪等；`git diff --check`通过 |
| V-QUAL-003 | Agent离线/真实PG/Boot回归 | not_run（用户本次明确排除） | Maven构建阻塞已由BUILD-001解除，但未执行任何测试，不形成通过声明 |
| V-QUAL-004 | 真实Qwen、API桩、真实浏览器 | not_run | 纯后端排版，无模型/前端变更；不以历史结果替代本次回归 |
| V-QUAL-005 | Maven六模块跳过测试package | pass | 见BUILD-001：Reactor全部SUCCESS，主/测试源码均编译，38个class为Java 21；未生成Surefire报告 |

最终backend源码树SHA256：`49e5c1f6b8efcfa0290382ccd88537755fb17b3d9da7169108ec053e0d070808`。格式化器为官方google-java-format 1.24.0 all-deps，下载自项目指定Maven Central，发布方SHA1校验为`3b55f08a70d53984ac4b3e7796dc992858d6bdd8`；工具与校验脚本均位于被忽略的`.runtime`，未进入项目依赖。

本变更不改变版本需求或发布状态。当前为格式/结构及跳过测试构建证据完整、后端回归未运行的 `VERIFIED_PARTIAL`；BUILD-001生成的本机JAR未部署，V0.1镜像和发布清单不更新，旧运行产物不得被描述为包含此次源码排版。
