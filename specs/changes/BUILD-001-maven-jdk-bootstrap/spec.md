# BUILD-001 项目内 Maven 与 JDK 21 编译环境

状态：VERIFIED（构建范围；测试与部署未执行）

日期：2026-11-12

关联：NFR-007/NFR-008；用户明确授权安装Maven并验证依赖/JDK/编译，暂不运行测试，不操作Docker。

## 需求与范围

- BUILD-FR-001：安装与Compose验证镜像一致的Maven 3.9.9，复用现有Temurin JDK 21.0.7。
- BUILD-FR-002：工具、下载包、依赖仓库均放在项目被忽略的`.runtime`下，不修改系统PATH/JAVA_HOME/APPDATA，不写入全局Maven配置。
- BUILD-FR-003：使用项目`.mvn/settings.xml`和`backend/pom.xml`解析六个后端模块依赖，执行跳过测试的完整Maven `package`，确保主代码和测试代码均编译。
- BUILD-FR-004：记录实际Java/Maven版本、构建命令、产物及未执行测试；不把构建成功描述为测试或部署通过。

非目标：不运行JUnit/真实PG/Boot/Qwen/浏览器测试，不启动或安装Docker，不部署镜像，不修改业务源码、依赖版本、数据库或发布清单，不提交/推送。

## 设计与契约

- 从Apache官方归档下载`apache-maven-3.9.9-bin.zip`，使用发布方SHA512校验；压缩包与解压目录分离且初始为空，校验路径后解压。
- Maven home：`.runtime/tools/build-001/apache-maven/apache-maven-3.9.9`。
- 依赖仓库：`.runtime/maven-repository`，避免依赖重定向用户目录。
- 仅当前进程设置`JAVA_HOME=C:\Users\admin\.jdks\temurin-21`并前置其`bin`。
- 构建命令：`mvn -s .mvn/settings.xml -f backend/pom.xml -B -ntp -Dmaven.repo.local=<项目绝对路径>/.runtime/maven-repository -DskipTests package`。
- `-DskipTests`跳过执行但仍编译测试源码；不使用`maven.test.skip=true`。无业务契约或配置变化。

## 验收

- AC-BUILD-01：Maven发行包SHA512匹配，`mvn -version`显示3.9.9和Java 21.0.7。
- AC-BUILD-02：六个后端模块依赖解析成功，Reactor六个模块全部BUILD SUCCESS，主/测试源码编译完成。
- AC-BUILD-03：生成后端模块artifact和Boot fat JAR，记录大小/SHA256；不启动应用、不运行测试、不更新发布镜像。
- AC-BUILD-04：全局环境不变，工作区业务差异不因安装/构建增加，证据与HANDOFF同步。

## 任务

- T-BUILD-001（done）：Maven 3.9.9已下载至项目内，官方SHA512为`8beac8d11ef208f1e2a8df0682b9448a9a363d2ad13ca74af43705549e72e74c9378823bf689287801cbbfc2f6ea9596201d19ccacfdfb682ee8a2ff4c4418ba`，路径安全校验后解压。
- T-BUILD-002（done）：`mvn -version`确认Maven 3.9.9和Java 21.0.7；六模块依赖解析及跳过测试package成功，主代码和测试代码均以`release 21`编译。
- T-BUILD-003（done）：已核验六模块artifact、38个class的Java 21主版本号、Boot fat JAR入口和依赖内容；未生成Surefire报告。QUAL-001与HANDOFF已同步实际构建边界。

## 验证与变更

| 验证 | 状态 | 证据/限制 |
| --- | --- | --- |
| V-BUILD-001 Maven下载与SHA512 | pass | 9,202,456字节官方ZIP；SHA512匹配；项目内隔离解压 |
| V-BUILD-002 Maven/JDK版本 | pass | Apache Maven 3.9.9；Eclipse Adoptium Java 21.0.7；Windows 11 amd64 |
| V-BUILD-003 六模块package（跳过测试） | pass | 2026-11-12 18:31:55 +08:00完成；parent及六个后端模块均SUCCESS，`BUILD SUCCESS`，总耗时2分13秒；日志`.runtime/build-001-package.log` |
| V-BUILD-004 产物与字节码 | pass | 六模块JAR齐全；38个主/测试class均为major 65；Boot入口、55个依赖JAR及无Surefire报告检查通过 |
| V-BUILD-005 测试/部署 | not_run（用户本次明确排除） | 未运行测试，未安装、启动或操作Docker，未更新镜像或部署，未提交/推送 |

模块artifact大小：domain 4,074字节、application 5,602字节、business-mock 23,670字节、agent 40,298字节、infrastructure 9,396字节、boot 30,357,599字节。Boot fat JAR为`backend/assistant-boot/target/assistant-boot-0.1.0-SNAPSHOT.jar`，SHA256为`b0651927ef41bef8d00d28a8753bfc269fefe61055b60c5a276cf7a184f9b878`，Manifest入口为`com.guoxin.assistant.AssistantApplication`。

本次结果只证明当前工作区源码在项目隔离Maven、现有JDK 21和项目本地依赖仓库下可以编译打包，不代表业务测试或运行环境验收通过。`.runtime`日志、工具、依赖及`target`产物均为本机文件，不作为已发布镜像证据。
