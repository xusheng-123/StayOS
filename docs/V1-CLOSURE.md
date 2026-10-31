# StayOS V1.0 本地收口记录

日期：2026-10-30；续跑：2026-10-31。范围：现有 V1.0 的验证、测试隔离修正与交付文档统一，不增加 V1.1 或后续业务。

## 1. 当前结论

**V1.0 本地收口通过。** 2026-10-31 第五批未过滤完整回归通过：284 项 JUnit、130 项真实浏览器测试全部通过，失败、错误、跳过及 flaky 均为 0，9 份浏览器报告及 21 份 JUnit 套件报告均来自本批。前端类型检查、生产构建及后端可执行 JAR 打包通过；192 个候选源码文件指纹与最终回归一致，开发容器 ID、StartedAt 未变，测试容器运行且未暂停。前两批启动失败、第三批中断、第四批计时测试失败均独立保留，没有合并各批部分结果。

即使本地回归通过，也不代表已允许收集真实住客资料或正式运营。生产部署、离机备份、属地要求和现场验收仍以 [V1 发布清单](V1-RELEASE-CHECKLIST.md) 的阻塞项为准。

## 2. 候选版本与证据留存

- Git 基线：`324a5b3482cb72862128fcee08a631d21cd1efd9`；当前分支 `master`。
- 以现有工作树为起点，保留进入本轮前的全部已修改/未跟踪代码，不提交、不重置或清理用户改动。
- 首轮及诊断证据目录：`.local/v1-closure-20261030-210953-e8d5ea/`；下表未注明批次的既有证据均在此目录。
- 第二批证据目录：`.local/v1-closure-20261030-r2/`；`targeted/` 保存恢复场景定向复验，`full/` 保存第二批未过滤完整回归及其受限运行账号启动失败。
- 第三批证据目录：`.local/v1-closure-20261030-r3/`；保存定向复验及中断前的完整回归日志。所有批次均在 Git 忽略范围内，不覆盖或混合原报告。
- 第三批完整回归被用户中断，日志最后更新于 2026-10-30 23:00 左右，没有最终退出结果及汇总，不能判定通过；其定向 6 项通过结果单独保留。
- 第四批证据目录：`.local/v1-closure-20261031-r4/`；2026-10-31 经用户要求重新执行前端检查及未过滤完整回归。`full/prior-surefire-reports/` 保留续跑前残留报告，最终判断只使用本批新报告。
- 第五批证据目录：`.local/v1-closure-20261031-r5/`；保存房态刷新计时测试修正后的定向、完整回归和打包验证，报告不与前四批相加。
- `baseline.json`、`source-baseline.json` 保存初始状态与源码文件 SHA256；`candidate-source.json` 保存回归前 backend/frontend/scripts/deploy 的实际文件指纹，不能只用 HEAD 代表这些未提交变更。
- 旧 Surefire/Playwright 报告归档后再运行本次回归，避免把不同批次结果相加；本次报告按运行时间筛选并单独留存。
- 原始日志和 XML 可能包含虚构夹具、路径或诊断信息，不应未经审查对外发布；本记录只给出脱敏统计与结果。

## 3. 本轮修改

### 3.1 测试入口不再处理开发数据库服务

`scripts/test.ps1` 原先执行未指定服务名的 Compose `up`，启用 test profile 时也会处理默认的开发 PostgreSQL。现已显式使用：

```powershell
docker compose --env-file .env -f deploy/compose.yaml --profile test up -d --wait --no-deps postgres-test
```

另外，在操作容器前检查 `java`、`mvn`、`pwsh` 和 `docker` 是否可解析。测试容器必须独占；完整验收会暂停并恢复测试容器，不能与人工演练或其他测试并行。

### 3.2 便携工具只作用于项目进程

用户确认允许准备便携工具后，使用官方来源下载并核对校验值：

| 工具 | 本次版本 | 安装位置（相对 `%LOCALAPPDATA%/StayOS/tools`） |
| --- | --- | --- |
| Java | Eclipse Temurin 21.0.13+9 LTS | `java-21/` |
| Maven | 3.9.16 | `maven/` |
| PowerShell | 7.6.9 | `powershell-7/` |

`scripts/env.ps1` 自动发现上述目录，并保留旧版目录 fallback；仅修改当前进程的 `JAVA_HOME`/`PATH`，没有卸载系统 Java 11，也没有修改系统全局环境或开发 `.env`。

Java/PowerShell 使用官方元数据或发布清单的 SHA256，Maven 使用官方 SHA512。归档分别放在新的下载/解压目录；本轮脚本不在下载内容目录中执行。来源、版本、校验值和安装路径见证据目录 `tool-versions.json`。

### 3.3 测试密钥指纹与开发配置隔离

`application-test.yml` 现在显式固定与公开测试密钥对应的 SHA256 指纹，避免测试用的零字节夹具密钥继承开发/生产环境中的另一把密钥指纹。没有更改真实密钥、密文格式或数据库数据。

`SecurityInfrastructureTest` 增加测试，验证测试配置中的密钥和指纹匹配。

### 3.4 隔离恢复验收子进程的启动方式与探测

首轮唯一失败发生在恢复库核对之后、第一次启动独立应用时。诊断中主线程仍在类加载与 Spring Bean 初始化，并非已经证实的数据库阻塞或死锁。保持原应用和恢复库不变，默认 JVM 设置在多个 20 秒窗口内未就绪；一次 60 秒观察也未就绪，另一次延长至 150 秒的纯诊断窗口中，实际约 59.8 秒后存活和数据库就绪均返回 200。

仅改变诊断子进程为 `-XX:TieredStopAtLevel=1`：最早一次 20 秒对照仍未完成，后续连续三次分别在约 4.6、5.1、7.3 秒内就绪，随后原失败场景的定向复验中两次启动约为 5.7、5.9 秒。证据支持“本机默认 JVM 启动开销与短生命周期验收的启动预算不匹配”，不据此断言 JDK 缺陷或生产吞吐问题已经解决。

最小修正只落在测试代码：

- `SystemAcceptanceTest` 的恢复子进程及 `OperationsInfrastructureTest` 的受限运行账号子进程使用启动优先编译模式，并显式绑定回环地址；这是测试中仅有的两处独立 Java 应用启动器，生产启动及主性能测试 JVM 默认参数不变。
- 仍保留 20 秒预算，改用单调时钟 deadline，单次探测最多 1 秒且不超过剩余预算；复用并关闭一个 HTTP 客户端，不再每次轮询新建客户端。
- 探测从 `/api/health` 收紧为 `/api/health/ready`，必须验证数据库可用；保留进程存活、停库、SQL 超时及提交回滚等所有断言和原超时配置。
- 新增 `ApplicationStartupProbeTest` 四项检查：必须等待数据库就绪、仅存活不足以通过、无响应探测受剩余预算约束、进程退出立即失败。

这不是把正式测试等待改成 60/150 秒，也没有跳过恢复或安全检查；测试编译模式的结果不能用于承诺目标服务器的默认 JVM 启动时间、长期性能或容量。

### 3.5 文档统一

- README 不再把当前项目描述为仅完成认证；补充 PowerShell 7、测试独占及 JUnit 夹具入口限制。
- 备份主流程使用已有包装脚本；恢复示例显式要求独立核对的 `ExpectedGuestKeyFingerprint`，不把归档 SHA256、`v1` 格式标识和密钥指纹混淆。
- 保留旧归档兼容限制，明确脚本已有不等于任务已部署，也不支持生产覆盖恢复。
- 版本路线标记为已认可，当前仅推进 V1.0；续住、资料更正、收费、预订等没有进入本轮。

### 3.6 房态刷新验收的模拟时钟时序

2026-10-31 第四批中，`OperationalReliabilityTest#operationsBrowserAcceptance` 是唯一失败项；其余 283 项 JUnit 通过，浏览器 129 项通过、1 项失败，均无跳过。失败为 `operations.spec.ts` 中房态自动刷新测试在推进 31 秒后没有观测到请求。

测试原先在进入房态页、定时器已创建后才调用 `page.clock.install()`，并且只等待刷新按钮可见，未等待首次房态加载完成。已安装 Playwright 的类型文档建议在导航前安装时钟；首次加载未完成也会使页面的刷新防重入条件生效。修正仅为在导航前安装模拟时钟，并等待房态总数显示为数字后开始观测；保留 31 秒活跃推进、两次 65 秒闲置推进、手动刷新断言及原等待上限，没有重试、跳过或放宽断言。

第五批定向经原 JUnit 夹具启动，1 项 JUnit 和其内全部 9 项真实浏览器测试通过，错误、失败、跳过及 flaky 均为 0。定向证据见第五批 `targeted/`；此结果不替代最终未过滤完整回归。

## 4. 验证记录

### 4.1 最终第五批（2026-10-31）

本表路径均相对 `.local/v1-closure-20261031-r5/`；完整回归从 10:31:05 至 10:40:18，打包于 10:40:25 完成（北京时间）。

| 项目 | 最终状态 | 同批证据 |
| --- | --- | --- |
| 前端类型检查、生产构建 | 两项退出码 0 | `frontend-typecheck.log`、`frontend-build.log` |
| 未过滤 Maven 全量回归 | 284 passed、0 failures/errors/skipped；21 个套件，BUILD SUCCESS | `full/backend-full.log`、`full/surefire-reports/` |
| 真实浏览器验收 | 130 expected、0 unexpected/flaky/skipped；9 份新报告 | `full/playwright-reports/`、`full/regression-summary.json` |
| 受限运行账号与备份包装 | 两项运维基础设施验收通过；未安装常驻任务 | `full/surefire-reports/TEST-com.stayos.OperationsInfrastructureTest.xml`、`full/runtime-application.log` |
| 隔离恢复及故障验收 | 21 张表核对，正确密钥/电话解密、错误/缺失密钥拒绝、重启、旧会话拒绝、持久化幂等、停库恢复、超时回滚、提交失败回滚与重试均通过 | `full/recovery/recovery.json`、两次启动日志、`full/surefire-reports/TEST-com.stayos.SystemAcceptanceTest.xml` |
| 本地并发、短时稳定性及查询计划 | 系统验收原断言通过；仅代表本机夹具规模 | `full/recovery/performance.json`、`full/recovery/query-plans.json` |
| 测试隔离与最终容器状态 | 开发容器 ID/StartedAt 不变；测试容器 Running=true、Paused=false | `full/regression-preflight.json`、`full/regression-result.json` |
| 源码与后端产物 | 192 个源码文件指纹一致；可执行 JAR 新构建通过，未包含被检查的测试夹具和私有环境文件 | `package/verified-source.json`、`package/package-result.json`、`package/backend-package.log` |

本轮续跑只修正 3.6 节测试时序，没有新增业务、放宽断言或变更生产 JVM 配置。前四批结果如下保留，不计入最终通过数量。

### 4.2 前四批及诊断记录

| 项目 | 本轮状态 | 证据 |
| --- | --- | --- |
| 前端类型检查 | 通过，退出码 0 | `frontend-typecheck.log` |
| 前端生产构建 | 通过，退出码 0 | `frontend-build.log` |
| 官方工具校验、版本及新进程自动发现 | 通过 | `tool-install.log`、`tool-versions.json` |
| 测试入口调用契约 | 5 项通过、0 失败；命令使用替身，不代替真实回归 | `runner-contract.json` |
| 安全基础设施定向单元测试 | 已通过；独立定向结果，不替代完整回归 | `security-unit-tests.log`、`security-unit-reports/` |
| 未过滤的完整 Maven + Playwright | 首轮已结束失败：JUnit 280 项，279 passed、1 failure、0 errors、0 skipped；9 份新 Playwright 报告共 130 expected、0 unexpected/flaky/skipped | `regression-summary.json`、同批次 reports、`backend-full.log`、`regression-driver.log` |
| 开发容器运行前后核对 | 首轮结束已核对：开发容器 ID、StartedAt 未变；测试容器 Running=true、Paused=false | `regression-summary.json`、`regression-preflight.json`、`regression-result.json` |

首轮唯一失败为 `SystemAcceptanceTest#actualBackupRestoreKeyVerificationRestartOutageTimeoutAndCommitRollback`：首轮源码第 270 行调用 `ready`，第 245 行报 `Isolated application startup timeout`。首轮此前的恢复、数据快照、密钥解密及约束核对已通过，其后的重启、停库、超时和提交回滚分支未完成；这些原始结果不因后续复验通过而改写。首轮统计及报告保留在首轮目录 `regression-summary.json` 及同批次 reports。

第二批定向复验共 5 项通过、0 failures/errors/skipped（4 项启动探测单元测试及原失败场景 1 项）。其中实际比对 21 张表，完成正确密钥解密、错误/缺失密钥拒绝、进程重启、旧会话拒绝、持久化幂等、停库恢复、查询超时回滚及提交失败回滚和重试。证据为第二批 `targeted/result.json`、`targeted/surefire-reports/`、`targeted/recovery/recovery.json` 和两次启动日志；这仍不是未过滤完整回归通过的证明。

第二批未过滤完整回归已结束失败：JUnit 284 项，283 passed、1 failure、0 errors、0 skipped；9 份新 Playwright 报告仍为 130 项全部通过。首轮的恢复用例及新增 4 项探测检查在本批通过，唯一失败转为 `OperationsInfrastructureTest#restrictedRuntimeRoleCanServeRealHotelFlowButCannotRewriteHistoryOrRunDdl`，首轮该类曾通过，但第二批在原源码第 94 行的启动等待处失败。该次运行日志仅有启动横幅，没有权限或业务断言失败证据。第二批 `ops-startup-diagnostic/`、`full/regression-summary.json` 与同批报告保留原结果。

随后仅将这另一处短生命周期测试 JVM 改为相同的启动优先模式，并复用已验证的 20 秒 `/api/health/ready` 探测，不扩大等待或改变运行账号授权。第三批定向共 6 项通过、0 failures/errors/skipped（4 项探测器检查及 2 项运维基础设施验收）；受限应用约 4.1 秒启动，备份/指纹/任务预览、受限账号真实业务及 DDL/历史改写拒绝等原断言通过，开发容器元数据未变。证据在第三批 `targeted/`。第三批未过滤完整回归被中断，未形成最终结论，不能将前两批已通过部分拼接为一次全量通过。

调用契约的 5 项覆盖：只选择 `postgres-test` 且不启动依赖、Browser 标志传递、Docker 失败时不执行 Maven、Maven 失败向外传播、工具缺失时不操作 Docker。它们使用独立临时脚本夹具，不连接真实数据库。

实际执行入口：

```powershell
npm --prefix frontend run typecheck
npm --prefix frontend run build
# 定向验证新增的测试密钥指纹约束；不作为完整回归结果。
mvn -B -f backend/pom.xml -Dtest=SecurityInfrastructureTest test
# 正式回归不带 -Dtest 过滤条件。
.\scripts\test.ps1 -Browser
```

完整回归前已确认 Spring 数据源/Flyway/config/profile 未受到继承变量覆盖，测试与开发端口不同，测试端口仅绑定回环地址且连接的是 `stayos_test`。没有直接运行缺乏 JUnit 账户与数据夹具的前端全量 `npm run test:e2e`。

## 5. 不在本轮执行的事项

- 不部署生产代理/HTTPS，不修改开发数据库账号或密钥，不读出真实住客数据。
- 备份恢复验证仅使用测试数据库和虚构夹具；计划任务仅 `-WhatIf`，不安装常驻任务，不向外部位置复制数据。
- 不新增续住、住客更正、误退房恢复、正式交班、收费、预订、OTA 或会员功能。
- 生产环境、离机副本、值班告警、RPO/RTO、实名/住宿登记、留存及实际设备验收由发布清单单独管理，不能以本次本地结果替代。

## 6. 最终复核与交付边界

第五批最终退出码、同批新报告、恢复分支、容器状态、候选源码指纹及后端打包均已核对，当前工作树可作 V1.0 本地验收候选。此前失败与中断报告保持原状；没有自动提交 Git、部署生产或安装常驻备份任务。真实上线仍须逐项完成发布清单中的目标环境、备份告警、密钥保管、酒店规则及现场验收。
