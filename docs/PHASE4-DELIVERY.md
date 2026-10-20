# StayOS V1.0 第 4 阶段交付记录

验收日期：2026-10-20（北京时间）。本文件以实际执行结果为准。开发基线为 `f820d81`，没有修改或删除既有 Git 历史；第四阶段稳定版本提交以 `git log` 为准。

## 1. 实际完成的功能

- 前台、酒店总经理可从当前入住列表正式办理退房，确认窗口显示房号、房型、主要联系人、入住及预计退房时间。
- 后端验证有效入住与当前使用段，记录服务器退房时间和经办人，结束入住及当前段，保留全部住客与历史。
- 同一事务释放占用、房间标脏、创建恰好一条待处理退房清洁任务、保存成功审计和幂等结果。
- 退房后可查询历史入住；房态图从后端重新查询后显示待打扫，不能直接重新入住。
- 客房部、酒店总经理可查询未分配待处理任务池，支持房号搜索、楼层筛选、分页、刷新、任务详情；不返回住客敏感资料。
- 延用 Session、CSRF、角色权限、住客加密及 24 小时持久化幂等。网络结果不确定时重试原请求，刷新后也可恢复，不在浏览器保存住客资料。

没有开发换房、接单、打扫、完成、分配、验收、维修工单或收费功能。

## 2. 新增和修改的主要文件

| 文件或目录 | 用途 |
|---|---|
| `backend/src/main/resources/db/migration/V7__checkout_housekeeping.sql` | 任务表、业务外键/来源唯一、扩展幂等操作和真实任务房态事实 |
| `backend/src/main/resources/db/migration/V8__one_open_cleaning_task_per_room.sql` | 同一房间最多一条待处理清洁任务 |
| `backend/src/main/java/com/stayos/stay/service/CheckoutService.java` | 退房状态校验、锁、统一业务事务 |
| `backend/src/main/java/com/stayos/stay/service/IdempotencyService.java` | 从原入住实现提取的共享防重协议，入住和退房共用 |
| `backend/src/main/java/com/stayos/stay/service/CheckInService.java` | 调用共享幂等服务；入住原规则及加密方式不变 |
| `backend/src/main/java/com/stayos/stay/mapper/CheckoutMapper.java` | 锁入住/段、结束入住/段、标脏、查询稳定退房结果 |
| `backend/src/main/java/com/stayos/stay/mapper/StayMapper.java` | 幂等 SQL 增加操作类型参数 |
| `backend/src/main/java/com/stayos/stay/dto/CheckoutRequest.java`、`CheckoutResult.java`、`StayView.java` | 当前段请求、退房结果、查询增加退房经办人 ID |
| `backend/src/main/java/com/stayos/stay/controller/StayController.java` | 增加正式退房接口 |
| `backend/src/main/java/com/stayos/housekeeping/` | TaskController、TaskQueryService、TaskCreationService、TaskMapper、TaskFilter、TaskView；模块只开放查询，创建仅由内部事务调用 |
| `backend/src/main/java/com/stayos/auth/RolePermissions.java`、`SecurityConfig.java` | 增加独立任务查询权限及受保护路径 |
| `backend/src/test/java/com/stayos/CheckoutSchemaTest.java`、`CheckoutIntegrationTest.java` | 真实数据库迁移、退房、权限、并发、故障注入及浏览器联调 |
| `backend/src/test/java/com/stayos/StayTestFacts.java`、`StayIntegrationTest.java` | 测试后恢复真实任务视图；原历史夹具更新为“已退房拒绝再退房”的验收 |
| `frontend/src/components/CheckoutDialog.vue` | 确认、结果反馈、重复点击防护、原请求重试和刷新恢复 |
| `frontend/src/views/StayListView.vue` | 当前入住退房入口，历史说明更新 |
| `frontend/src/views/HousekeepingTasksView.vue` | 任务表格、筛选、分页、详情 |
| `frontend/src/types/stay.ts`、`types/housekeeping.ts` | 退房及任务接口类型 |
| `frontend/src/router/index.ts`、`layouts/WorkspaceLayout.vue` | 客房任务路由与权限导航，保留现有工作区 |
| `frontend/e2e/checkout.spec.ts` | 12 项真实 Edge 浏览器场景 |
| `docs/CHECK-OUT.md`、`docs/API.md`、`docs/DATABASE.md`、`docs/DEVELOPMENT.md`、`README.md` | 实际契约、规划/已实现边界及进度同步 |

## 3. 数据库及迁移

新增一张业务表 `housekeeping_tasks`，不增加退房表或任何收费表。

| 列 | 作用 |
|---|---|
| id / room_id | 任务内部 ID、稳定房间关联 |
| task_type / source | CHECKOUT_CLEANING（退房清洁）、CHECKOUT（退房来源） |
| source_id / stay_record_id | 来源退房所属入住 ID、明确入住外键；两者必须相同 |
| room_segment_id | 本次离房使用段外键 |
| status | PENDING；本阶段没有状态变更接口 |
| created_by / created_at | 登录经办人、服务器操作时间 |
| notes | 必要任务备注，自动创建时为空，不复制住客或入住备注 |

唯一约束保护来源和离房段；房间部分唯一索引保护同房最多一个待处理任务。三列复合外键确保“使用段—入住—房间”属于同一业务关系，限制删除历史。

原幂等表只扩展 CHECK_OUT 枚举，仍保存原 result_stay_id，无请求正文或完整响应。房态视图开始读取真实任务，清洁状态仍只在 rooms 表保存。

V1—V6 的 SHA256 与开发前一致。V7 执行后继续加强约束使用 V8；未回写已经执行的迁移。开发库及隔离测试库均成功迁移到 V8，Flyway 校验和再次执行检查通过。

## 4. 正式退房与任务创建流程

`预留幂等标识 → 锁入住 → 锁房间 → 重查并锁当前段 → 核对段 ID → 结束入住和段 → 标脏 → 创建任务 → 写成功审计 → 保存幂等结果 → 一起提交`。

实际时间由服务器生成，同一时间写入入住、使用段及任务。经办人取自登录身份。后续换房必须沿用“入住 → 房间按 ID 升序 → 使用段/关联记录”的锁顺序。

任务由客房模块内部服务创建，事务传播为 MANDATORY：必须参加退房已有事务，不能先单独成功。任务来源为本次退房，不靠房号或住客姓名关联，不删除任何入住或住客历史。

## 5. 接口及页面

| 方法与路径 | 权限 / 用途 |
|---|---|
| POST `/api/stays/{stayId}/check-out` | stay:manage；请求头 Idempotency-Key，正文 `{ "segmentId": 123 }` |
| GET `/api/housekeeping-tasks` | housekeeping:read；分页、房号、楼层、PENDING 状态查询 |
| GET `/api/housekeeping-tasks/{taskId}` | housekeeping:read；任务详情 |
| 既有 GET `/api/stays`、`/history`、`/{id}` | 当前/历史入住及脱敏详情，已结束记录显示实际退房时间和经办人 ID |
| 既有房间、房态、统计接口 | 统一读取新的实际任务事实 |

成功退房结果返回 stayId、segmentId、roomId、房号/房型快照、actualCheckOutAt、checkedOutBy、taskId，及退房时 DIRTY、任务创建时 PENDING。它们表示历史结果，实时状态查询房态/任务接口。完整返回字段、错误码见 [CHECK-OUT.md](CHECK-OUT.md)。

本地地址：<http://127.0.0.1:5173>。

1. 使用前台或总经理账号登录 → 入住功能 → 当前入住（`/stays`）→ 办理退房 → 核对信息 → 确认退房。
2. 成功后可点击“查看历史入住”（`/stays/history`）或“查看房态图”（`/rooms`）。
3. 使用总经理或客房部账号 → 客房功能 → 清洁任务（`/housekeeping/tasks`）。
4. 请求未确认时使用“重试原退房请求”，不要另开一笔操作。刷新只恢复 ID 和请求标识，不存住客资料。需要结束过期重试时，先查历史，再明确点击“核对后结束重试”。

本地开发服务已更新并启动，健康接口正常、前端返回 HTTP 200。重启后的 Session 需要重新登录，符合原有单实例内存 Session 设计。

## 6. 五种角色实测

以下均通过后端 HTTP 权限测试及浏览器路由检查；本地五个既有账号也完成登录、查询与退出冒烟检查。

| 角色 | 办理退房/入住历史 | 查询任务 | 系统管理 |
|---|---|---|---|
| ADMIN | 拒绝 403 | 拒绝 403 | 允许 |
| HOTEL_MANAGER | 允许 | 允许 | 拒绝 403 |
| FRONT_DESK | 允许 | 拒绝 403 | 拒绝 403 |
| HOUSEKEEPING_MANAGER | 拒绝 403 | 允许 | 拒绝 403 |
| HOUSEKEEPING_STAFF | 拒绝 403 | 允许（当前未分配池） | 拒绝 403 |

匿名请求拒绝 401，缺少 CSRF 的登录用户写请求拒绝 403。总经理可以连续使用前台与客房功能，无需切换账号；没有将其配置为 ADMIN。多角色仍按既有规则合并权限。

## 7. 并发、防重复及回滚结果

以下均在 `stayos_test` 真实 PostgreSQL 执行；正常退房先通过正式 HTTP 入住接口建立记录。

| 场景 | 实际结果 |
|---|---|
| 正常入住后退房 | 入住 CHECKED_OUT，当前段正确关闭且保留历史，服务器时间和经办人正确 |
| 两名已登录前台/总经理并发退同一笔入住，不同标识 | 一次 HTTP 200，一次 409；任务只有一条 |
| 同操作人同标识并发 | 返回同一退房结果，仅一条任务和一条成功审计 |
| 同标识同内容重试 | 返回原结果，响应内容相同 |
| 同标识换内容 | IDEMPOTENCY_MISMATCH，不产生额外数据 |
| 旧标识超过 24 小时 | IDEMPOTENCY_KEY_EXPIRED，保留旧标识、不重新执行；测试通过调整测试库时间模拟过期 |
| 换新标识再次退已结束入住 | STAY_ALREADY_CHECKED_OUT |
| 提交他房的段或换房前旧段 | STALE_ROOM_SEGMENT，当前入住不受影响；换房历史仅测试夹具，未开发换房接口 |
| 清洁任务插入故障 | 所有退房变更回滚，入住仍有效、房间仍占用、无任务/成功审计/退房幂等残留；移除故障后原标识重试成功 |
| 成功审计插入故障 | 同上完整回滚及原标识重试成功 |
| 最后保存幂等结果故障 | 同上，连已写入的任务和成功审计一起回滚 |
| 重复来源任务直接插入 | 数据库唯一约束拒绝 |
| 使用段和房间错配的任务插入 | 数据库复合外键拒绝 |
| 请求结果丢失 / 刷新 / 成功响应内容损坏 | 浏览器沿用原标识重试成功，不多建任务；浏览器存储无住客资料 |

故障通过隔离测试库触发器注入，不新增生产故障开关或绕过业务的接口。

## 8. 房态及资料保护结果

- 退房后 occupied=false、清洁 DIRTY、真实待处理任务存在、canCheckIn=false；再次正式入住被拒绝。
- 原房态接口、卡片和统计共享同一数据来源。即使测试中把清洁误改为 CLEAN，未完成任务仍阻止入住。
- 退房不操作启用状态；隔离测试验证 disabled 标记和维修阻止事实保留。当前未实现异常模块，维修组合使用测试视图注入，没有开发完整维修流程。
- 当前段结束不删除历史；住客详情继续脱敏。客房任务响应不包含住客姓名、证件、电话或密文，也不复制入住备注。
- 开发库前后核对：仍为 **80 间房、6 个房型、0 条入住、0 名住客、0 个任务、80 间 DIRTY、0 间可入住**。没有为测试批量改开发库房态或生成虚构入住。
- 房间和房型完整行摘要、用户账号/密码哈希/启用状态/安全版本摘要、原入住及住客摘要均一致。成功登录的时间和审计属于正常冒烟运行，不是业务资料覆盖。
- 对 139 个 Git 候选文件检查，真实配置密钥、本地账号密码、开发库用户密码哈希均零命中；备份、`.env`、账号文档和 Excel 快照继续被 Git 忽略。

开发前数据库备份保存在被 Git 忽略的 `.local/` 内，未执行备份恢复测试，不将“有备份文件”描述成“恢复已验证”。

## 9. 实际执行的测试

最终全量运行命令：

```powershell
. .\scripts\env.ps1
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' package
npm --prefix frontend run build
```

| JUnit 类 | 数量 | 失败 / 错误 / 跳过 |
|---|---:|---|
| AuthIntegrationTest | 22 | 0 / 0 / 0 |
| BootstrapTest | 3 | 0 / 0 / 0 |
| IpRateLimitTest | 1 | 0 / 0 / 0 |
| RoomIntegrationTest | 16 | 0 / 0 / 0 |
| RoomSchemaTest | 8 | 0 / 0 / 0 |
| SchemaTest | 3 | 0 / 0 / 0 |
| StayIntegrationTest | 14 | 0 / 0 / 0 |
| StaySchemaTest | 8 | 0 / 0 / 0 |
| CheckoutIntegrationTest | 17 | 0 / 0 / 0 |
| CheckoutSchemaTest | 3 | 0 / 0 / 0 |
| **合计** | **95** | **0 / 0 / 0** |

| Playwright 套件（真实 Edge） | 通过 | 失败 / 不稳定 / 跳过 |
|---|---:|---|
| auth.spec.ts | 13 | 0 / 0 / 0 |
| rooms.spec.ts | 11 | 0 / 0 / 0 |
| stays.spec.ts | 10 | 0 / 0 / 0 |
| checkout.spec.ts | 12 | 0 / 0 / 0 |
| **合计** | **46** | **0 / 0 / 0** |

JUnit 中有 4 个入口负责启动浏览器套件，故 95 和 46 是两层统计，不应相加宣称 141 个独立业务测试。前端 TypeScript 检查与 Vite 构建成功，后端打包成功。

最初出现过测试编译的泛型推断问题、浏览器定位重复匹配和关闭按钮定位失败，均已修正；表中是修正后完整运行结果，不隐去早期失败，也不把未运行项目列为通过。测试截图已检查桌面退房确认、客房详情和手机退房布局。

本地证据（生成物均不提交）：`backend/target/surefire-reports/TEST-*.xml`、`.local/phase4-final-tests.log`、`.local/phase4-junit-summary.json`、四份 `.local/playwright*-results.json`、`.local/phase4-before.json`、`phase4-after.json`、`phase4-dev-smoke.json`、退房/任务截图。

## 10. 对前三阶段的影响

- 没有重建项目、删除表、清空数据库、更换依赖或技术栈。
- 原认证、登录/退出、角色、多角色权限、房型/房间维护、房态筛选统计、入住、并发入住和住客加密测试全部通过。
- 入住仅把原幂等逻辑提取为共享服务，原 CHECK_IN 摘要方式及数据库键不变；原角色的既有权限没有收回，新增任务读取权限只授予客房部及总经理。
- 测试清理后恢复真实任务房态视图，隔离库无遗留入住或任务。
- 开发完成时没有自动提交 Git；用户随后授权检查敏感文件、运行状态和变更范围，确认无误后保存稳定版本提交。

## 11. 当前边界和未执行项目

本阶段没有阻塞性遗留问题。以下是明确边界，不算已经完成的功能：

- 任务只能创建和查询，尚不能完成，因此退房房间会继续保持不可入住，直至第 5 阶段正常清洁流程落地。
- 现有开发库 80 间房原本全部 DIRTY，当前没有正常可入住样本。完整入住—退房验收在隔离库进行，未开放生产状态修改接口制造演示数据。
- 任务分配、本人任务范围、接管、开始/完成及其状态历史均待第 5 阶段。
- 没有执行生产部署、真实酒店运营、负载压测、机器崩溃恢复或备份恢复测试。
- 住客保留期限、真实身份核验要求、生产密钥管理/轮换等仍按第三阶段遗留边界，在真实试用前确认；本阶段只使用虚构测试资料。

## 12. 第五阶段建议

按此前已经确认的清洁流程实现领取/分配、开始、完成和执行人无法继续时的受控接管；保留“清洁不自动解除故障、异常解决不自动变为已清洁”的独立规则。主管验收是否纳入仍以已确认 V1 范围为准，不因当前 INSPECTION 枚举自行增加流程。

先增量扩展任务状态/执行人及必要历史，再实现状态流转事务、权限范围和房间锁。同步扩展未完成任务唯一索引和房态视图，完成任务时才能受控恢复 CLEAN；普通用户不得任意修改房态。验证重复完成、并发领取、接管与完成竞争、执行人禁用后的权限，并通过正常页面准备可入住房间，再回归入住与退房。收费业务继续不进入 V1。

还应按此前人工清洁任务设计，为最初已标脏但没有退房来源的房间提供受控建任务方式；当前 80 间房即属此情况。届时通过新增迁移扩展来源与条件外键约束，人工任务不能伪造入住/使用段来凑数据，退房任务仍必须保留完整关联。本阶段没有提前实现人工建任务。

## 13. 用通俗语言理解关键技术

- **事务**：把退房当成一张必须全部完成的清单。结束入住、标脏、建任务和记审计必须一起成功；少一项就全部撤回，不能留下“房间空了但没人打扫”的半成品。
- **行锁**：操作前先锁定这笔入住和相关房间。第二名前台要等第一人处理完，再按最新状态判断，避免同时退两次。统一锁顺序是大家按同一顺序拿钥匙，减少互相等着对方的情况。
- **幂等**：一次操作有一个固定编号。网络断了再次提交同一编号，服务器识别出已经做过，就返回原结果。换编号不能绕过“已退房”的业务检查。
- **唯一约束**：数据库的最后一道防重门，即使程序遗漏判断，也不能插入相同来源的第二条任务。
- **外键**：任务不仅记一个房号，还牢靠地关联真实房间、入住和使用段。复合外键进一步防止把甲房的任务错误挂到乙房的使用段上。
- **房间使用段**：记录某次入住具体使用哪间房、何时进入和离开。退房结束当前段，而不是抹去记录，因此以后换过房也能查清完整历史。
- **占用与清洁是两回事**：住客离开后房间确实空了，但还没打扫。VACANT 只表示无人占用，必须同时清洁合格、无待处理任务、无阻止异常且已启用，后端才允许安排入住。
- **权限**：前端按钮方便操作，后端检查才决定操作能不能执行。即使手工发送接口请求，客房员工也不能越权退房，总经理也不能越权管理系统账号。
- **Flyway 增量迁移**：像连续编号的数据库施工记录。已经执行的文件有校验记录，修改旧文件会让不同环境失去共同历史，因此用 V7、V8 追加变更。
