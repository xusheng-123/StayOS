# StayOS V1.0 第 5 阶段交付报告

> 本文是批量分房优化前的验收快照。最新规则已关闭所有自主领取，服务员仅查看本人任务；分房仅客房主管/总经理可用。最新实现与测试见 [批量分房优化交付](PHASE5-BATCH-ASSIGNMENT-DELIVERY.md)。

日期：2026-10-20（Asia/Shanghai）。基于上一阶段提交 `21ccc1b` 增量开发；本阶段不自动暂存或提交 Git，等待用户验收。详细使用说明见 [客房清洁说明](docs/HOUSEKEEPING.md)。

## 1. 实际完成功能

- 在原 `housekeeping_tasks` 上扩展人工创建、领取、主管分配、待处理改派和清洁中受控接管。
- 独立领取与开始：领取后仍 PENDING；开始后 IN_PROGRESS/CLEANING；完成后 COMPLETED/CLEAN。
- 保存当前负责人、领取/分配/开始/完成时间、最初开始人员、实际完成人员和必要备注，实际事件时间由服务器生成。
- 追加任务操作历史，保留创建、领取、分配、接管、开始和完成。旧任务不会因再次入住被覆盖或删除。
- 完成清洁重新影响统一可入住判断，不清除维修阻止、不改变启用状态、不伪造或修改房间占用。
- 任务工作台、授权范围统计、搜索筛选、排序、详情和历史；手机使用卡片和适合触屏的按钮。
- 管理员与前台默认无清洁业务权限；总经理兼具前台和客房业务权限，不增加系统管理权限。
- 共用原 PostgreSQL 24 小时幂等机制，新增任务结果关联和操作类型；版本、行锁及数据库约束共同防止重复事件和并发覆盖。
- 真实业务闭环已在隔离 PostgreSQL 中通过 HTTP 业务接口和浏览器验证，没有直接 SQL 改房态代替闭环。

未增加：主管验收、INSPECTION 流程、住中清洁、批量建单、取消任务、维修工单、换房、预订或任何收费业务。

## 2. 数据库与迁移

仅新增 `backend/src/main/resources/db/migration/V9__housekeeping_workflow.sql`，V1—V8 内容与上一提交一致，未改写历史迁移。

### 原表扩展

`housekeeping_tasks` 新增：

| 字段 | 作用 |
|---|---|
| assigned_to / assigned_by / assigned_at | 当前负责人、本次分配人和时间 |
| claimed_at | 当前归属来自领取时的领取时间；改派后的当前归属不再标为领取，原领取事实保留在历史 |
| started_by / started_at | 最初开始人员和开始时间，接管不重置 |
| completed_by / completed_at | 实际最终完成人员和完成时间 |
| completion_notes | 必要清洁完成备注 |
| version | 每次合法操作递增，阻止旧页面覆盖新状态和新负责人 |

`idempotency_requests` 新增 `result_task_log_id` 外键，允许六种 HK 操作，保留 `result_stay_id` 原含义；结果类型检查不允许任务结果与入住结果混用。

### 新表

`housekeeping_task_logs`：任务 ID、动作、前后状态、前后负责人、操作者、服务器时间、原因、任务版本。任务＋版本唯一，只通过业务服务追加，不开放普通修改和删除接口。

### 约束与兼容

- CHECKOUT：原来源 ID、入住 ID、使用段 ID 必填并相互匹配；原来源唯一、使用段唯一、房间/入住/段复合外键全部保留。
- MANUAL：三项来源关联必须均为空，真实房间/创建人以及明确原因必填，不能伪造入住来绕过外键。
- 未完成房间唯一索引覆盖 `PENDING`＋`IN_PROGRESS`。COMPLETED 保留历史并允许后续新任务。
- 归属、开始、完成的必要人员/时间组合有数据库检查约束。
- 房态视图同步覆盖两种未完成状态；清洁字段仍只保存在 `rooms.cleaning_status`。
- 旧退房任务使用真实创建人和创建时间补记 AUTO_CREATE，不伪造执行过程。
- 升级测试在独立测试 schema 从 V1—V8 建出真实退房任务与原幂等记录后迁移到 V9，验证原任务、关联、房号、幂等结果和创建事实完整保留。

开发库迁移与保护核对结果见第 10 节；本地备份位于 Git 忽略的 `.local/`，不加入代码交付。

## 3. 业务操作与权限规则

1. 主管/总经理选空闲、DIRTY、无未完成任务的房间，填写原因创建 MANUAL_CLEANING/PENDING 任务。
2. 员工领取未分配任务，或主管选择启用且具有执行权限的员工分配；任务仍为 PENDING。
3. 负责人点击开始，后端重检版本、归属、资格和房间无人占用/DIRTY，原子设 IN_PROGRESS/CLEANING 并记录开始人员/时间。
4. 负责人完成，后端重检 IN_PROGRESS、归属及无人占用/CLEANING，原子设 COMPLETED/CLEAN，并记录完成事实。
5. 员工无法继续时，主管/总经理选择其他合格员工、填写原因重新分配。清洁中保持 IN_PROGRESS/CLEANING，保留最初开始事实，历史记录原/新员工；原负责人失去操作资格。
6. 房间空闲、启用、CLEAN、无维修/其他阻止、无未完成任务时才可入住。停用或维修阻止不因完成清洁被清除。

服务员只能看未分配 PENDING 池和自己当前负责的任务（含已完成历史）；主管/总经理看全部。详情和历史采用相同范围，服务员不能改参数绕过。主管筛选可找到禁用的历史负责人，但分配候选不包含无效员工，写入时再次检查资格。

## 4. 事务与锁顺序

任务状态、房间清洁、负责人、必要历史、成功审计、幂等结果均在同一事务提交。任何关键写入失败一起回滚，不会出现任务已完成、房间仍 CLEANING，或房间 CLEAN 但任务未完成。

清洁：请求预留 → 员工按 ID 升序共享锁 → 房间 → 任务 → 历史/审计/结果。先读任务以获取房间 ID，真正写入前按该顺序加锁并重查；不反向锁入住记录。退房仍按入住 → 房间 → 使用段处理，入住仍以房间锁保护，兼容原流程。

资格共享锁与账号管理的用户行更新互斥，避免资格校验后被并发禁用/改角色。任务行锁与版本同时校验，确保“完成”和“重新分配”竞争时只接受当前版本上的合法操作。

## 5. 幂等与网络不确定性

复用原 IdempotencyService、相同表、相同 HMAC 摘要及 24 小时策略。新增 `HK_MANUAL_CREATE/HK_CLAIM/HK_ASSIGN/HK_REASSIGN/HK_START/HK_COMPLETE`，结果仅关联操作历史，不保存完整请求/响应或客人资料。

相同操作人、操作类型、UUID 和内容在 24 小时内返回原回执；内容不同拒绝；过期旧标识保留且拒绝再次执行；失败随事务回滚，修复后可用同一标识重试。入住/退房的原操作类型、摘要及结果规则保持不变。

前端提交期间禁用重复按钮。响应丢失/损坏时保留原标识和内存请求安全重试；可先关闭操作弹窗查询。sessionStorage 只保留标识、动作和房间/任务 ID，不保存清洁备注或整份请求。刷新后先查询详情/历史，核对后明确结束本机重试提示，不自动重发或撤销业务。

## 6. 接口与页面

前缀 `/api/housekeeping-tasks`：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | 根路径、`/{id}` | 复用列表/详情并加入后端范围控制 |
| GET | `/{id}/history` | 操作历史 |
| GET | `/statistics` | 授权范围五项统计，今日按上海自然日 |
| GET | `/employees` | 主管分配候选 ID/姓名 |
| GET | `/assignees` | 主管筛选负责人，含已有任务历史负责人 |
| POST | `/manual` | 人工创建 |
| POST | `/{id}/claim` | 领取，保持待处理 |
| POST | `/{id}/assign` | 首次分配待处理任务 |
| POST | `/{id}/reassign` | 改派或受控接管，原因必填 |
| POST | `/{id}/start` | 正式开始 |
| POST | `/{id}/complete` | 正式完成 |

请求字段、错误码、筛选及回执见 [HOUSEKEEPING.md](docs/HOUSEKEEPING.md)。所有写入必须 Session、CSRF、UUID；按业务动作校验权限、状态和数据范围。

页面：[清洁任务工作台](http://127.0.0.1:5173/housekeeping/tasks)。主管用现有本地账号登录后先创建人工任务，员工领取/开始/完成；前台可在 [房态图](http://127.0.0.1:5173/rooms) 点击“刷新房态”。任务操作后本页自动重查，前台手动刷新/重新进入查询，没有 WebSocket 推送。

初始房间需逐间创建任务；没有擅自给现有 80 间房批量建单或批量设 CLEAN。开发测试账号仍保存在根目录本地账号文档，未写入本报告或 Git。

## 7. 五种角色验证

| 角色 | 任务查询 | 清洁执行 | 人工创建/分配/接管 | 系统管理 |
|---|---|---|---|---|
| ADMIN | 默认 403 | 403 | 403 | 保留 |
| FRONT_DESK | 默认 403，房态可查询 | 403 | 403 | 403 |
| HOUSEKEEPING_STAFF | 未分配＋自己的任务 | 仅自己的任务，符合状态才成功 | 403 | 403 |
| HOUSEKEEPING_MANAGER | 全部 | 必须先成为负责人 | 允许，资格/原因/版本受校验 | 403 |
| HOTEL_MANAGER | 全部，另有前台权限 | 同主管 | 同主管 | 403 |

以上是单角色默认值，复用多角色权限并集。JUnit HTTP 与浏览器均验证角色入口和业务权限，未登录写入返回 401；员工越范围详情/历史为 404，越权负责人筛选为 403；禁用后原 Session 无法继续执行。接口不返回住客证件、电话或加密字段。

## 8. 并发、回滚和闭环验证

- 两人同时领取仅一人成功，只有一条 CLAIM 历史。
- 同一房间并发人工建单一成功一冲突；数据库直接拒绝第二条 PENDING 或 IN_PROGRESS 任务。
- 完成与接管竞争只有一方在原版本上成功，最终状态、负责人和房间清洁保持一致。
- 同标识并发完成返回相同历史回执，只有一条完成历史和一次房态变化；重复开始/完成的新标识被拒绝。
- 已分配任务不可被其他员工领取；已禁用/失去资格的员工不能被重新指派；接管后旧负责人无法继续完成。
- 注入任务更新、房间更新、历史插入、成功审计插入、幂等结果更新失败，验证全部回滚，去除故障后同标识成功重试。
- 人工创建和首次分配的历史失败也验证整体回滚；原退房任务创建/审计/请求结果失败回滚测试继续执行。
- 完成不改变占用或清除停用/维修事实。维修模块尚未实现，因此维修阻止保持测试使用隔离测试库的测试专用事实视图，不作为正式维修功能宣称。
- 实际闭环：初始 DIRTY → 人工建单 → 领取 → 开始 → 完成 → 可入住 → 正式入住 → 正式退房 → 自动新任务 → 再次领取/开始/完成 → 再次可入住 → 第二笔正式入住。任务和入住历史完整保留，旧任务重复完成被拒绝。

正常闭环只用公共 HTTP 业务操作；隔离测试库中的初始空房夹具、非法数据约束测试和故障注入使用 SQL，不作为生产状态修改入口，未批量修改开发库房态。

## 9. 实际测试与构建

最终完整回归于 **2026-10-20 13:32（上海时间）** 完成，Maven `BUILD SUCCESS`，退出码 0。

| JUnit 测试组 | 实际数量 | 通过 | 失败/错误/跳过 |
|---|---:|---:|---|
| AuthIntegrationTest / BootstrapTest / IpRateLimitTest | 26 | 26 | 0 / 0 / 0 |
| RoomIntegrationTest / RoomSchemaTest | 24 | 24 | 0 / 0 / 0 |
| StayIntegrationTest / StaySchemaTest | 22 | 22 | 0 / 0 / 0 |
| CheckoutIntegrationTest / CheckoutSchemaTest | 20 | 20 | 0 / 0 / 0 |
| HousekeepingIntegrationTest / HousekeepingSchemaTest | 29 | 29 | 0 / 0 / 0 |
| SchemaTest | 3 | 3 | 0 / 0 / 0 |
| **合计** | **124** | **124** | **0 / 0 / 0** |

| Playwright 套件（Headless Edge） | 实际通过 | 失败/不稳定/跳过 |
|---|---:|---|
| auth.spec.ts | 13 | 0 / 0 / 0 |
| rooms.spec.ts | 11 | 0 / 0 / 0 |
| stays.spec.ts | 10 | 0 / 0 / 0 |
| checkout.spec.ts | 12 | 0 / 0 / 0 |
| housekeeping.spec.ts（本阶段新增） | 15 | 0 / 0 / 0 |
| **合计** | **61** | **0 / 0 / 0** |

后端编译、测试及 Spring Boot 可执行 JAR 打包成功；前端 `vue-tsc --noEmit` 类型检查与 Vite 构建成功。测试使用隔离的真实 PostgreSQL 17，浏览器请求对接测试后端，并核对数据库结果。

本地证据：`backend/target/surefire-reports/TEST-*.xml`、`.local/phase5-final-tests.log`、`.local/phase5-test-summary.json`、五份 `.local/playwright-*-results.json`（认证为 `playwright-results.json`）以及 `.local/phase5-frontend-build.log`，均为本地构建/测试产物，不加入 Git。

中断说明：上午一次完整回归因运行中断未完成，未计为通过；Docker 恢复后，从干净的隔离测试库重新执行了上面的完整命令，最终结果取本次已完成报告。

执行命令：

```powershell
. .\scripts\env.ps1
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' package
npm --prefix frontend run build
```

单阶段先执行迁移、人工建单、领取分配、状态/事务测试，再执行新增浏览器及完整回归。首次尝试出现并已修复的问题：Java 注解字符串引号和 Vue 表格类型编译错误；手机用例未等请求完成就断言；迁移升级测试连接未恢复原 schema。修复后重新执行相关测试，以最终报告为准，不将失败尝试计为通过。

JUnit 中的五个浏览器入口负责创建临时账号/房间、启动对应 Playwright 套件并核对数据库，浏览器数量单独统计，不能与 JUnit 数量相加当作互相独立的业务场景。

未执行：生产部署、高负载/长时间压力、进程断电/数据库宕机恢复、备份还原演练、跨设备真实酒店试用、密钥轮换。Windows Headless Edge 的手机视口测试不等于真实手机硬件测试。

## 10. 开发库和前四阶段保护

2026-10-20 13:33 完成本地新版本运行核对：

- 现有开发 PostgreSQL 容器正常，后端从 V8 **增量执行 V9 成功**，V1—V9 均为成功记录。
- `http://127.0.0.1:8080/api/health` 返回 `UP`；前端 `http://127.0.0.1:5173` 返回 HTTP 200，后端与前端进程保持运行供验收。
- 使用根目录本地测试账号逐一登录五种角色，核对房态、入住、任务、任务统计、候选/筛选员工与账号管理查询权限，全部符合预期；每次均正常退出。没有通过这些检查修改房间、入住或清洁任务。
- 迁移前后房间 **80**、房型 **6**、入住 **0**、住客 **0**；房间/房型完整行及账号关键资料、已有入住/住客的校验值一致。DIRTY **80**、可入住 **0**，未给开发库批量建单或改房态。主管可以从这些真实房间开始逐间建单。
- 测试结束后隔离库房间、房型、入住、任务、任务历史夹具均清理为 0；临时升级 schema 与故障注入触发器无残留，只保留既有账号安全触发器。
- 检查 150 个可纳入仓库的文件，没有匹配本地真实密钥、开发密码或密码哈希，没有发现数据库备份/私钥等意外文件。`.env`、测试账号文档、备份与运行日志均被 Git 忽略。
- 8 份本阶段变更 Markdown 的本地链接检查通过；历史 V1—V8 与 HEAD 内容一致；Git 差异空白检查通过。

本地核对记录位于 `.local/phase5-before.json`、`phase5-after.json`、`phase5-dev-smoke.json`、`phase5-sensitive-scan.json` 和 `phase5-doc-check.json`。登录/退出产生正常审计记录，未把审计表增长误称为“数据库完全没有写入”。

V1—V8 与上一提交内容完全一致；没有清空开发库或覆盖现有房间、房型、账号、入住/客人数据。备份和开发环境密钥保留在 Git 忽略路径。本阶段继续采用原技术栈、单体模块、Session/CSRF、住客加密和同一幂等系统。

第三、四阶段的 24 小时重试/冲突/过期、并发分房/退房、故障回滚继续回归。对既有测试的必要调整仅包括清洁任务新增历史的夹具清理、两种未完成状态的事实视图、旧“接口未实现”断言改为实际输入校验，以及新任务工作台标题；没有放松原业务不变量。

## 11. 主要文件

| 文件或目录（相对项目根目录） | 作用 |
|---|---|
| `backend/src/main/resources/db/migration/V9__housekeeping_workflow.sql`（新增） | 扩展原任务表、条件来源约束、未完成唯一索引、任务历史和原幂等表 |
| `backend/src/main/java/com/stayos/housekeeping/service/TaskWorkflowService.java`（新增） | 人工建单、领取、分配、接管、开始、完成的事务与行锁 |
| `backend/src/main/java/com/stayos/housekeeping/service/TaskAccess.java`（新增） | 当前员工资格及任务数据范围 |
| `backend/src/main/java/com/stayos/housekeeping/dto/TaskRequests.java`、`TaskLog.java`、`TaskStatistics.java`（新增） | 写入参数、操作回执、工作台统计 |
| `backend/src/main/java/com/stayos/housekeeping/controller/TaskController.java`、`mapper/TaskMapper.java` | 业务接口、SQL、历史及授权范围查询 |
| `backend/src/main/java/com/stayos/housekeeping/service/TaskQueryService.java`、`TaskCreationService.java` | 查询统计、退房自动创建历史的事务衔接 |
| `backend/src/main/java/com/stayos/housekeeping/dto/TaskFilter.java`、`TaskView.java` | 搜索、范围、排序和任务详情字段 |
| `backend/src/main/java/com/stayos/stay/service/IdempotencyService.java`、`mapper/StayMapper.java` | 在原幂等机制内增加任务历史结果，不改变入住/退房语义 |
| `backend/src/main/java/com/stayos/common/ApiErrors.java` | 重复未完成任务的中文错误映射 |
| `frontend/src/views/HousekeepingTasksView.vue`、`frontend/src/types/housekeeping.ts` | 工作台、统计、桌面/手机列表、详情历史及安全重试 |
| `frontend/src/views/WorkspaceView.vue` | 更新工作区功能说明 |
| `backend/src/test/java/com/stayos/HousekeepingIntegrationTest.java`、`HousekeepingSchemaTest.java`（新增） | 真实 PostgreSQL、HTTP、并发、回滚、升级及浏览器入口测试 |
| `frontend/e2e/housekeeping.spec.ts`（新增） | 15 项浏览器测试，含手机视口、接管、重试和闭环 |
| `backend/src/test/java/com/stayos/CheckoutIntegrationTest.java`、`StayTestFacts.java`、`frontend/e2e/checkout.spec.ts` | 保留原场景，适配新增历史清理、状态范围和页面标题 |
| `docs/HOUSEKEEPING.md`（新增） | 本阶段使用方法、接口、权限和并发说明 |
| `README.md`、`docs/API.md`、`DATABASE.md`、`DEVELOPMENT.md`、`PRD.md`、`CHECK-OUT.md` | 同步实际方案，标明第四阶段历史快照与本阶段变化 |
| `PHASE5-DELIVERY.md`（新增） | 本次交付与验收证据 |

没有修改依赖版本、已有认证配置、V1—V8 迁移或 Git 历史。

## 12. 遗留问题及下一阶段建议

没有已知阻断本阶段核心闭环的问题。以下为明确范围限制或后续事项：

- 逐间人工建单已完成，批量建单属于可选项，本阶段未实现。
- 无主管验收、任务取消、住中清洁、质量评分或智能派单，正常流程直接清洁完成。
- 异常及换房模块尚未实现。下一阶段建议先落实已确认的房间异常与入住阻止，再开发依赖它的故障换房，继续按入住→房间 ID 升序→关联记录锁顺序及原事务/幂等方案扩展。
- V9 条件来源目前仅 CHECKOUT/MANUAL，新增换房来源时需增量扩展来源约束、来源唯一性与段关联，不能伪造退房来源或改写 V9。
- 真实试用前的个人信息保留期限、身份核验、部署等仍按此前未决事项等待用户明确确认，不在本阶段自行决定。
- 统计与任务页采用查询刷新；本阶段不提供实时推送，也未做高负载性能结论。

## 13. 用简单的话理解本阶段

- **任务状态与房间状态**：任务说明“这次清洁做到哪一步”；房间状态说明“房间现在是否清洁”。两者在合法动作里一起变化，维修、占用和启用仍各自独立。
- **事务**：把一组相关修改当成一个整体。任务完成、房间变干净、历史和成功记录必须一起成功，任何关键步骤失败就全部撤回。
- **行锁**：处理某房间/任务时先锁住相关记录，让同时来的操作按顺序检查，而不是两个员工都认为自己抢到了同一任务。
- **版本号**：页面看到的是第几次更新。主管或员工提交旧版本时，服务器要求刷新，避免旧页面覆盖新的负责人和状态。
- **部分唯一索引**：数据库只对“未完成任务”施加每房一条限制。旧完成任务可以永久保留，新退房仍能生成新任务。
- **幂等**：网络不好时同一次请求可以安全重试；服务器认出原标识并返回原操作记录，不再做第二次清洁完成或建单。
- **受控接管**：不是删除重建任务，而是主管填写原因换负责人，留下交接记录，保留已经发生的开始时间和人员。
- **权限与数据范围**：权限决定“能做什么”，范围决定“能对哪些记录做”。即使把按钮显示出来或改接口 ID，后端也不会允许服务员处理别人的任务。
- **Flyway 增量迁移**：已经执行的迁移相当于历史步骤，不能偷偷改内容；新增 V9 清楚记录本阶段结构变化，其他环境才能按相同顺序安全升级。
