# StayOS V1.0

StayOS 是采用模块化单体架构的酒店前台与客房协同管理系统。第 1 阶段认证工程已完成并通过本地测试，已初始化 Git 和独立 Docker 开发/测试数据库；实现及测试记录见 docs/AUTHENTICATION.md 和 docs/PHASE1-DELIVERY.md。`docs/` 包含四份基础设计文档，以及认证说明和交付记录，本 README 位于项目根目录。文档可随业务需要调整，但相关说明必须同步。

## 四份文档分别做什么？

1. [PRD.md — 产品需求文档](docs/PRD.md)：说明用户、范围、业务流程、权限及验收标准。
2. [DATABASE.md — 数据库设计草案](docs/DATABASE.md)：说明数据表、关联关系、约束、事务及并发规则。
3. [API.md — 接口说明草案](docs/API.md)：说明页面调用的接口、权限、状态和请求响应约定。
4. [DEVELOPMENT.md — 开发入门与项目协作说明](docs/DEVELOPMENT.md)：说明技术栈、目录、开发阶段和各阶段验收方式。

## 第一版与第二阶段边界

第一阶段指 V1.0，完成登录与角色权限、房型和房间维护、房态展示、入住、退房、换房、自动清洁任务、客房部处理任务、必要房间异常管理、酒店总经理角色及关键操作记录。

**额外服务项目、服务订单、房费汇总、账单、结算、押金、收款及退款统一放到第二阶段。第一版退房仅结束入住并生成清洁任务，不检查结算状态，也不代表完成收款。** 文档保留的收费设计仅供第二阶段参考，第一版不建收费表、接口或页面。

## 已确定的基本业务规则

- 清洁任务仅“待处理 → 清洁中 → 已完成”，第一版无主管验收、退回整改和住中清洁。
- 未分配任务只能由客房主管/总经理分配，支持以员工为中心批量分房；所有角色自主领取已关闭，ADMIN 不获得客房业务权限。执行人无法继续时，主管/总经理可填写原因、记录交接受控接管清洁中任务，保留原开始时间；接管不直接完成任务或开放房间，仅新执行人可完成。不允许普通清洁中改派。
- 一次入住同时使用一间房，可以多次换房。`stay_room_segments` 保存每段房间使用时间，当前房间不靠覆盖历史房号表达。
- 换房原因说明必填，ROOM_ISSUE 可选关联真实原房异常；不要求所有故障换房先建异常。异常与维修事实独立保留，换房不代替故障上报或处理。
- 退房和换房均自动创建关联原房间使用记录的清洁任务；同一来源不重复建任务，同房间最多一个未完成任务。
- 房间是否可入住统一结合档案启用、当前占用、清洁状态、未解决的入住阻止及未完成清洁任务判断。新房默认待清洁，完成任务后才能分配。
- 员工可上报阻止入住的异常，主管/总经理确认解决。清洁完成不解除故障，故障解决不自动清洁合格，已入住异常不自动退房。
- 异常按分类及 LOW/MEDIUM/CRITICAL 程度管理；未解决严重异常与有效人工维修停用共同阻止新入住，仅主管和总经理可处理及恢复，见 docs/ROOM-ISSUES.md。
- 五个固定角色支持一人多角色。总经理拥有前台与客房业务权限，但不自动拥有账号、角色分配或房型/房间资料维护权限；管理员需要业务权限时也应追加对应角色。详细权限及记录范围见 PRD 第 2.6 节。
- 实际业务操作时间由服务器生成，数据库事件时间统一用 `TIMESTAMPTZ`，页面默认按 `Asia/Shanghai` 显示；预计退房时间到达不自动释放房间，第一版无历史补录。
- 退房/换房和清洁任务创建必须同一数据库事务成功或回滚；后端检查权限、并发及重复提交，保留关键业务历史，不开放普通历史删除接口。

## 技术路线与推进方式

前端：Vue 3 + TypeScript + Element Plus；后端：Java + Spring Boot；数据库：PostgreSQL；数据访问：MyBatis-Plus；身份权限：Spring Security；数据库迁移：Flyway；版本管理：Git。后端一个应用，按业务分模块，不使用微服务。

第 1 阶段认证已经完成，见 [AUTHENTICATION.md](docs/AUTHENTICATION.md) 和 [PHASE1-DELIVERY.md](docs/PHASE1-DELIVERY.md)。第 2 阶段房型、房间及房态已完成，规则和接口见 [ROOMS.md](docs/ROOMS.md)，实际测试和交付见 [PHASE2-DELIVERY.md](docs/PHASE2-DELIVERY.md)。第 3 阶段入住规则与接口见 [CHECK-IN.md](docs/CHECK-IN.md)，验收结果见 [PHASE3-DELIVERY.md](docs/PHASE3-DELIVERY.md)。DEVELOPMENT 保留原始整体路线编号，用户最新阶段编号优先；第 4 阶段已实现正式退房、自动清洁任务及只读任务列表，规则见 [CHECK-OUT.md](docs/CHECK-OUT.md)，验收结果见 [PHASE4-DELIVERY.md](docs/PHASE4-DELIVERY.md)。第 5 阶段已实现人工清洁、人员批量分房、受控接管、开始/完成、历史及房态闭环，见 [HOUSEKEEPING.md](docs/HOUSEKEEPING.md) 和 [PHASE5-DELIVERY.md](docs/PHASE5-DELIVERY.md)；第 6 阶段异常及维修停用见 [ROOM-ISSUES.md](docs/ROOM-ISSUES.md) 和 [PHASE6-DELIVERY.md](docs/PHASE6-DELIVERY.md)。第 7 阶段正式换房已实现，实际规则见 [ROOM-CHANGES.md](docs/ROOM-CHANGES.md)，交付见 [PHASE7-DELIVERY.md](docs/PHASE7-DELIVERY.md)。

**未决事项等待规则：** 首个管理员、登录方式及第 2 阶段房间规则已确认；第 3 阶段已确认全部实际住客、证件/电话加密脱敏，以及 PostgreSQL 入住防重与 24 小时策略，见 CHECK-IN.md。真实试用的数据保留期限、身份核验、部署等仍按 PRD 第 9 节确认。触及未决事项或与已有功能冲突时立即暂停，等待明确反馈，不得把建议或沉默视为授权。按钮样式等普通细节可采用合理默认值并记录。

第 5 阶段页面：[清洁任务工作台](http://127.0.0.1:5173/housekeeping/tasks)，主管/总经理创建与分配，已分配负责人开始、完成；前台刷新房态图获取结果。页面支持手机卡片。当前阶段不自动提交 Git。

## 推荐阅读顺序

先读 `PRD.md`，确认需求；再读 `DATABASE.md`，理解数据；接着读 `API.md`，理解前后端如何沟通；最后读 `DEVELOPMENT.md`，按阶段推进开发。

## 本地启动（Windows）

需要 Docker Desktop（Linux 容器模式）、Java 21、Maven 3.9、Node.js 20.19+ 或 22.12+、Git。当前机器使用已安装的 Node.js 24，以及 `LocalAppData/StayOS/tools` 中的便携 Java/Maven；脚本会优先使用这套便携工具，不修改系统环境变量。

在 `E:\StayOS` 的 PowerShell 终端执行。首次配置 `.env` 已在当前机器完成，**已有 `.env` 时跳过初始化命令，不覆盖数据库密码**。

```powershell
# 仅全新环境执行一次；生成随机数据库密码，不创建任何默认管理员。
.\scripts\init-local.ps1
# 第 3 阶段新增：补充独立住客加密密钥，已有密钥会保留，不能随意替换。
.\scripts\init-guest-key.ps1

docker compose --env-file .env -f deploy/compose.yaml up -d --wait
npm --prefix frontend ci
. .\scripts\env.ps1
mvn -f backend/pom.xml -DskipTests package

# 首次创建管理员，必须在真实终端执行；密码隐藏输入。
.\scripts\bootstrap-admin.ps1
```

随后分别在两个终端中启动：

```powershell
# 终端一
.\scripts\start-backend.ps1
# 终端二
.\scripts\start-frontend.ps1
```

浏览器打开 [StayOS 本地工作台](http://127.0.0.1:5173)，使用刚创建的账号登录；右上角“退出登录”立即撤销当前登录。不要混用 `localhost` 与 `127.0.0.1`，它们的 Cookie 不共享。

第 2 阶段页面：五角色可通过导航进入 [房态图](http://127.0.0.1:5173/rooms)；管理员另外可进入 [房型管理](http://127.0.0.1:5173/manage/room-types) 和 [房间管理](http://127.0.0.1:5173/manage/rooms)。先新增房型，再新增房间；新房默认待打扫，主管可创建人工任务，分配后由负责人开始并完成清洁，不能直接修改房态。开发库不自动注入演示房间，各种房态组合仅在独立测试库验证。已有本地测试账号保存在根目录的测试账号文档中，该文件已被 Git 忽略。

第 3 阶段页面：前台与总经理可进入 [办理入住](http://127.0.0.1:5173/stays/new)、[当前入住](http://127.0.0.1:5173/stays)、[历史入住](http://127.0.0.1:5173/stays/history)，成功后查看脱敏详情。已有 80 间开发房间的实时状态以 PostgreSQL 为准；初始待打扫房间可通过第 5 阶段的合法清洁流程恢复可入住，不通过 SQL 批量改房态演示。

升级现有环境时先运行 `scripts/init-guest-key.ps1`，再重启后端加载 `.env`。缺少合法加密密钥时后端拒绝启动；随机密钥不会显示到终端，也不写进 Git。数据库备份与密钥需分别妥善保存，丢失密钥会无法还原加密证件/电话。

开发数据库仅监听 `127.0.0.1:15432`；测试库监听 `127.0.0.1:15433`，使用独立临时数据目录。Compose 项目名为 `stayos`，开发卷为 `stayos_pgdata`，不会使用其他项目的容器或数据卷。开发库不会自动插入测试员工。

账号维护接口已经实现，账号管理表单留作后续界面工作；管理员现已可维护房型和房间。创建员工、分配角色等接口见 API 第 4、11 节。浏览器自动化测试临时创建五角色、多角色账号和房间夹具，结束后清理，不在源码内提供固定演示密码。

## 运行测试

```powershell
cd E:\StayOS\frontend
npm ci
npm run build
cd ..
.\scripts\test.ps1
# 运行真实 Edge 浏览器联调；需已安装 Microsoft Edge。
.\scripts\test.ps1 -Browser
```

普通测试命令会明确跳过浏览器测试入口；加 `-Browser` 时全部执行。测试只写 `stayos_test`，不会清空开发库。浏览器测试的随机密码仅通过子进程环境传递，不写入源码或测试报告。报告在 `backend/target/surefire-reports` 和 `.local/playwright-results.json`。

房间浏览器测试另存 `.local/playwright-rooms-results.json` 与 `.local/room-browser-run.log`；认证浏览器测试仍保存原来的报告路径。房间接口、统计口径、已确认规则及后续视图扩展要求见 ROOMS.md。

本机 `.env`、构建产物、浏览器截图及依赖目录已通过 `.gitignore` 排除。当前工程尚未部署上线；正式部署使用 `prod` 配置（Secure Cookie）、HTTPS 和受限数据库账号，入口代理限流等在部署阶段核对。

## 给初学者的提示

- 你不需要先把每个技术词都学会才能读这些文档。每份文档中都加入了“注释”和术语解释。
- 文档中的表名、字段、接口路径和示例数据都是建议草案，开发前可以结合真实酒店业务调整。
- 所有示例客人和号码都应当视为虚构数据；不要将真实客人的证件信息放进开发文档或代码仓库。
- 在真实酒店使用之前，必须完成测试、安全检查、备份恢复验证，并确认酒店实际操作流程。

第五阶段批量优化：[人员分房](http://127.0.0.1:5173/housekeeping/assignment)，仅客房主管和总经理可用。选择员工、多选房间、一次确认；合法空闲 DIRTY 房间无需预先逐间建单。所有角色自主领取关闭，服务员仅执行本人任务。见 [优化交付报告](docs/PHASE5-BATCH-ASSIGNMENT-DELIVERY.md)。

## 第八阶段：系统验收与运营准备

复用现有审计表新增只读[操作记录](http://127.0.0.1:5173/audit-logs)页面。管理员查看账号、安全及基础配置审计；总经理查看酒店业务审计；客房主管仅查看清洁、异常和维修审计。前台及普通服务员继续使用原业务历史，角色操作权限没有扩张。

备份和隔离恢复操作见 [BACKUP-RECOVERY.md](docs/BACKUP-RECOVERY.md)；正式试运行前逐项检查 [V1-RELEASE-CHECKLIST.md](docs/V1-RELEASE-CHECKLIST.md)。实际测试、故障恢复及性能数据见 [PHASE8-DELIVERY.md](PHASE8-DELIVERY.md)。开发环境验收通过不代表可以直接上线处理真实住客资料。
