# StayOS V1.0 第7阶段交付报告：正式换房

开发日期：2026-10-20；基于稳定提交 `ed99790` 增量开发。按要求不自动提交 Git。

> 开发与验证已完成：245项JUnit、102项Playwright全部通过，前后端构建成功。开发库安全增量迁移至V12，原业务内容复查一致。未自动提交Git，等待用户验收。

## 1. 实际功能与使用方法

- 原入住及全部住客不变，跨房型、多次换房，实际入住/预计退房时间保持不变。
- 原段结束、新段建立、真实换房事实和完整房号/房型快照；统一服务器时间，只有一个有效使用段。
- 目标房复用统一可入住判断；拒绝占用、DIRTY/CLEANING/INSPECTION、未完成清洁、严重异常、人工停用或档案停用。
- 原房VACANT/DIRTY且恰好生成一条未分配PENDING换房清洁任务；目标OCCUPIED且清洁仍CLEAN。
- 既有人员分房、开始/完成清洁继续处理换房来源；不增加自主领取权限。
- 换房不解决异常、不关闭人工维修停用、不改变住客名单、不创建新入住、不产生费用。
- 当前入住列表及详情显示实际最新房间，完整使用段/多次换房历史可查；最终退房处理最新实际房间。
- 复用Session、CSRF、24小时幂等、真实PostgreSQL事务和行锁；原响应丢失可同标识重试或查询。

页面入口：`http://127.0.0.1:5173/stays`（当前入住），`/stays/{id}`（入住详情）。仅前台和总经理可办理。

1. 点击“办理换房”，核对当前房、主要联系人、登记人数、入住和预计退房时间。
2. 按房号、楼层、房型筛选，选择后端判定可入住的目标房。
3. 选择原因，填写说明；选择房间问题时可关联真实原房异常。
4. 点击“确认办理换房”，核对原房→目标房和原因，再次确认。
5. 成功后查看服务器时间、经办人、原房清洁任务；进入入住详情查看完整使用历史，或刷新房态图。
6. 客房主管/总经理在“人员分房”中将原房任务分给员工，员工按原流程开始/完成。原房故障或停用仍在时，清洁完成也不能入住。

已退房详情无可用换房入口。刷新页面不会自动重新换房；未确认结果时查询原结果，不能直接换新请求标识重做。sessionStorage仅保存随机key，不保存住客资料或换房说明正文。

## 2. 新增与修改文件

| 文件 | 用途 |
|---|---|
| `backend/src/main/resources/db/migration/V12__room_changes.sql` | 换房事实及复合约束、任务来源与幂等结果增量扩展 |
| `backend/src/main/java/com/stayos/stay/dto/RoomChangeDtos.java` | 换房请求、最小结果、房间使用段结构 |
| `backend/src/main/java/com/stayos/stay/mapper/RoomChangeMapper.java` | 使用段变更、换房事实/快照、历史及真实异常关联查询 |
| `backend/src/main/java/com/stayos/stay/service/RoomChangeService.java` | 正式换房事务、身份复查、锁顺序、可入住校验、历史及安全重试 |
| `backend/src/main/java/com/stayos/stay/controller/RoomChangeController.java` | 正式HTTP接口及stay:manage授权 |
| `backend/src/main/java/com/stayos/room/service/RoomEligibility.java` | 原入住和换房共用的可入住判定入口 |
| `backend/src/main/java/com/stayos/stay/service/CheckInService.java` | 复用相同判定与错误原因，原入住规则不变 |
| `backend/src/main/java/com/stayos/stay/service/IdempotencyService.java`、`stay/mapper/StayMapper.java` | 复用原24小时协议，新增ROOM_CHANGE结果外键读写 |
| `backend/src/main/java/com/stayos/stay/service/StayQueryService.java`、`stay/dto/StayView.java` | 详情追加完整使用段及换房历史，保留原字段及脱敏住客 |
| `backend/src/main/java/com/stayos/housekeeping/service/TaskCreationService.java`、`housekeeping/mapper/TaskMapper.java` | 换房事务内创建任务及AUTO_CREATE历史，保留原退房机制 |
| `backend/src/test/java/com/stayos/RoomChangeSchemaTest.java` | 真实迁移、来源/房段/时间/唯一约束 |
| `backend/src/test/java/com/stayos/RoomChangeIntegrationTest.java` | 正式HTTP、PG并发、故障回滚、闭环及浏览器启动 |
| `backend/src/test/java/com/stayos/HousekeepingSchemaTest.java` | V8到最新迁移数更新为4，保留原升级数据保护断言 |
| `frontend/src/components/RoomChangeDialog.vue` | 选房、原因、关联异常、确认/成功、内存原请求重试与刷新查询 |
| `frontend/src/views/StayListView.vue`、`StayDetailView.vue` | 换房入口、当前房及完整房间使用/换房历史；详情复用退房入口 |
| `frontend/src/types/stay.ts`、`types/housekeeping.ts` | 换房/使用段及任务新来源类型 |
| `frontend/src/views/HousekeepingTasksView.vue`、`HousekeepingAssignmentView.vue` | 正确展示换房清洁及自动来源 |
| `frontend/e2e/room-changes.spec.ts` | 五角色、真实换房、连续换房、网络重试、清洁联动及手机测试 |
| `docs/ROOM-CHANGES.md` | 当前实际接口、模型、事务、锁顺序和使用规范 |
| 根README、docs/API、DATABASE、PRD、DEVELOPMENT、HOUSEKEEPING | 同步当前实现，替代旧故障换房强制关联草案 |

## 3. Flyway及数据库约束

V1–V11原文件保持不变，仅新增V12。原段结束原因在V4已支持ROOM_CHANGE，无需修改旧文件；占用仍由stay_room_segments派生，不新建占用表。

新增 `room_changes`：入住、原房/新房内部ID、原段/新段、原因类型/说明、可选原房异常、服务器换房时间、真实经办人及创建时间、原新房号/房型快照。

- 两组段/入住/房间复合外键匹配真实同次入住，原房不同于新房；原段和新段分别唯一。
- issue_id和原房复合外键，不能关联其他房间；只有ROOM_ISSUE可带关联。
- 插入触发器核对旧段ROOM_CHANGE结束、旧退出=新进入=换房时间、目标段有效、入住仍有效以及快照一致。
- 原房/入住有效段部分唯一索引不变。历史没有业务修改或删除接口，仍需限制数据库运维账户。

housekeeping_tasks新增 `room_change_id`；ROOM_CHANGE_CLEANING/ROOM_CHANGE要求source_id=room_change_id且真实入住、原房、旧段与换房事实匹配，以四字段复合外键保护。CHECKOUT和MANUAL必须room_change_id为空，原来源及三元段关联约束保留；来源唯一、离房段唯一、同房PENDING/IN_PROGRESS唯一继续生效。

共享idempotency_requests新增 `result_room_change_id`，扩展操作白名单及互斥结果种类，原入住/退房/清洁/分房/异常结果兼容。不记录完整请求或住客资料，不自动清理旧标识。

## 4. 正式换房事务

1. 验证当前用户权限及请求，规范化必填说明，先预留/读取幂等记录。
2. 相同请求已成功时立即返回原结果，不先因原段关闭拒绝。
3. 操作人共享锁→入住锁→原房与目标房内部ID升序锁→当前段锁，再核对有效入住和客户端原段。
4. 目标房重新查询统一可入住事实；原房不能有冲突的未完成清洁任务；关联异常必须真实属于原房。
5. 同一服务器UTC时间关闭旧段并建立新段，保存换房事实和房号/房型快照。
6. 原房标DIRTY，创建唯一换房任务及创建日志，保存成功审计及幂等结果。
7. 整笔提交或整体回滚，不以退房+新入住冒充换房。

换房不修改原stay_records/住客关联、入住时间、预计退房时间或目标清洁状态。历史快照不随基础资料重命名变化；测试的重命名SQL仅为隔离库约束场景，未开放有历史房间改号接口。

## 5. 异常与维修、任务及退房兼容

GUEST_REQUEST/ROOM_ISSUE/OTHER和必填说明取代早期NORMAL/BLOCKING_ISSUE草案。ROOM_ISSUE可选关联真实原房异常，不要求故障换房先建异常或仅允许严重未解决异常；提供关联时验证归属。不从文字推断、伪造或处理异常。

换房不替代异常上报。原房严重异常及独立人工停用保留；清洁完成后仍需合法解决、恢复才能入住。没有凭换房关闭故障、凭修好故障自动清洁或自动退房。

换房任务直接复用原批量分房与清洁执行。多次换房产生多段历史；最终退房只关闭最新段并创建CHECKOUT清洁任务，之前换房任务/日志保留。

## 6. 后端API

全部受stay:manage保护，Session和CSRF沿用原方案。写操作携带Idempotency-Key。

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/api/stays/{id}/room-change/available-rooms` | 统一可入住目标房，房号/楼层/房型/分页 |
| POST | `/api/stays/{id}/room-changes` | `{segmentId,targetRoomId,reasonType,reason,issueId?}`正式换房 |
| GET | `/api/stays/{id}/room-changes` | 同次入住全部换房历史 |
| GET | `/api/stays/room-changes/result/{key}` | 只查本人原成功结果 |
| GET | `/api/stays/{id}`（扩展） | 原字段不变，新增roomSegments/roomChanges |

成功结果含换房记录ID、入住ID、原新房及段ID、房号/房型快照、原因、可选异常ID、经办人和服务器时间、原房清洁任务ID，不含完整证件或电话。

400非法参数、401未登录、403越权、404不存在或本人无成功回执、409业务/旧段/幂等冲突；失败不展示SQL异常堆栈。

## 7. 五角色权限

| 角色 | 查询目标/历史/结果及正式换房 | 清洁/分房权限 |
|---|---|---|
| FRONT_DESK | 允许 | 无清洁分配或执行权限 |
| HOTEL_MANAGER | 允许 | 保留主管级分房及合格本人任务执行 |
| HOUSEKEEPING_MANAGER | 拒绝 | 保留分房/改派和合格本人任务执行 |
| HOUSEKEEPING_STAFF | 拒绝 | 仅已分配本人任务执行，无自领 |
| ADMIN | 拒绝 | 无分房/清洁/入住/退房额外权限 |

前后端沿用既有stay:manage；没有改变其他角色映射，没有新增第六种角色。客房查询只返回任务工作所需内容，不通过新来源返回住客资料。

## 8. 并发、幂等及回滚验收范围

真实HTTP/PG测试覆盖：同入住不同key抢换、同key并发重试、两笔入住争同目标、换房与退房、换房与目标新入住、目标严重异常与换房竞争、原新房互换请求无死锁。确定性锁测试等待PG确认真实房间锁等待后提交严重异常，后取得锁的换房被拒绝；SQL只作为隔离并发夹具，不操作开发库。

同标识同内容24小时内返回原快照，即使之后再次换房或退房；修改目标/原因/分类/异常关联拒绝；过期旧key不重执行，仍可查询本人历史结果；他人同key不能读取该回执。

分别对 room_changes、stay_room_segments、rooms、housekeeping_tasks、housekeeping_task_logs、audit_logs、idempotency_requests 注入真实数据库错误。验证无换房、无新占用、原入住仍在原房、无换房任务/成功审计/成功key残留，撤除故障后原key重试一次成功。

## 9. 正式HTTP完整闭环

隔离stayos_test中：准备两间清洁房→正式入住并登记两位虚构住客→原房上报CRITICAL及人工停用→关联故障换房→原房DIRTY及唯一ROOM_CHANGE任务→主管按员工批量分房→服务员开始/完成→原房CLEAN但仍因故障/停用不可入住→主管解决异常并解除停用→原房可入住并实际新入住→原住客在目标房最终退房→唯一CHECKOUT任务→批量分配及清洁完成→目标房恢复可入住→查询原入住，全部使用段、换房、住客和任务历史保留。

普通住客要求换房、连续多次换房和最终退房也通过正式HTTP验证。正常流程不以SQL修改开发房态模拟；SQL夹具只用于隔离库约束、边界和故障注入。

## 10. 实际测试与构建

实际执行命令：

```powershell
. ./scripts/env.ps1
mvn -B -f backend/pom.xml '-Dtest=RoomChangeSchemaTest,HousekeepingSchemaTest' test
mvn -B -f backend/pom.xml '-Dtest=RoomChangeIntegrationTest' test
mvn -B -f backend/pom.xml '-Dtest=RoomChangeIntegrationTest#roomChangeBrowserTestsUseOnlyIsolatedPostgres' '-Dstayos.e2e=true' test
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' test
mvn -B -f backend/pom.xml -DskipTests package
npm --prefix frontend run build
```

| 检查 | 通过 | 失败/错误 | 跳过 |
|---|---:|---:|---:|
| 完整JUnit（实际245项） | 245 | 0/0 | 0 |
| 原前六阶段JUnit | 193 | 0/0 | 0 |
| 本阶段RoomChangeSchemaTest | 8 | 0/0 | 0 |
| 本阶段RoomChangeIntegrationTest | 44（43项HTTP/PG验证＋1项浏览器启动验证） | 0/0 | 0 |
| Playwright完整102项 | 102 | 0（不稳定0） | 0 |
| 原浏览器回归 | 86：认证13、房间11、入住10、退房12、清洁/分房26、异常14 | 0 | 0 |
| 本阶段浏览器 | 16 | 0 | 0 |

JUnit与Playwright分别统计；浏览器启动测试不代表另外44套浏览器测试。Playwright retries=0，没有自动重跑。完整回归mvn test退出0，BUILD SUCCESS，结束于2026-10-20 22:03:53；之后单独打包-DskipTests package退出0，22:04:31成功，此打包步骤未重新执行测试。前端vue-tsc类型检查及Vite构建退出0；可运行JAR已实际启动。

新增浏览器覆盖五角色、列表入口、确认取消、连续换房、最终退房、关联故障、旧页面目标失效、空房筛选、响应丢失原key重试、刷新仅查结果、不持久化正文、主管分配换房任务及服务员执行。在1440px桌面和390px手机视口检查溢出，截图已实际查看。

开发迭代曾修复：数据库测试夹具最初使用非法密码哈希（改为运行时随机BCrypt）；Java通配导入的Result类歧义（显式导入）；前端异常类型名称不匹配（使用已有RoomIssue）。最终完整测试及构建覆盖修复后代码；没有关闭数据库约束或放松权限来通过测试。

证据在Git忽略路径：.local/phase7-schema.log、phase7-backend-tests.log、phase7-browser-tests.log、phase7-full-tests.log、phase7-backend-build.log、phase7-frontend-build.log、phase7-test-summary.json、各Playwright JSON及backend/target/surefire-reports/TEST-*.xml。截图为.local/phase7-desktop.png与phase7-mobile.png。

## 11. 数据保护与运行情况

开发前实时查询：80间房、6种房型、7个用户、0入住/住客、4条清洁任务、16条清洁历史、1条异常及2条异常历史、0人工停用、11条幂等记录。不能用早期快照覆盖这些现状。

`.local/phase7-before.sql`及逐字段JSON快照已保存且被Git忽略。所有业务自动化仅用stayos_test。V12新增可空列后，原任务及幂等记录按原字段逐项比对，新增关联字段应为空；不会把合理的增量结构变化误判为业务数据变化。

开发库由实际应用启动时Flyway从V11升至V12，12个迁移校验成功。迁移后原业务/账号字段逐项核对：房间、房型、住客及关联、任务/历史、异常/历史、停用及幂等内容不变，新增room_change_id/result_room_change_id均为空。仍为80间房、6房型、7账号、4任务、1异常，4 CLEAN/76 DIRTY，没有新增开发库换房或虚构入住。

需要区分：5角色实际登录复查会由原V2用户触发器更新对应users.updated_at，新增合法登录/权限审计；这些预期元数据变化已单列核对，没有回写旧时间掩盖变化。用户名、密码哈希、角色、启用状态、安全版本及其他账号内容未改变。

新后端于2026-10-20 22:04:45启动，8080健康UP，前端5173返回HTTP200，两个StayOS PostgreSQL容器健康。5角色开发账号登录/退出与原权限、新换房查询权限复查通过；只做查询/拒绝验证，没有在开发库执行正常换房。内存Session重启失效，请重新登录。

V1–V11与HEAD逐文件规范化比对不变，仅执行新V12。密钥、备份、账户密码和本地测试材料保留在Git忽略路径；最终敏感检查不将它们纳入交付。

## 12. 影响、边界与后续建议

不新增收费、差价、补退款、账单、支付、押金、财务、预订、自动换房、批量换房、维修调度或主管清洁验收。仅增量扩展原模块，无微服务或消息队列。当前代码未自动提交Git，等待用户验收。

当前未解决的核心业务问题：无已知阻塞项；未执行项目按下方边界列明，不记为通过。

未执行生产部署、真实手机硬件、压力容量、断电恢复演练及不可篡改审计系统建设；手机测试是Edge中390px浏览器视口。房态靠刷新/重新查询，不宣称实时推送。自由文本提示及控制字符/长度检查不等于自动识别全部个人敏感信息。

后续可在V1.0边界内完善业务历史检索、操作审计查看、异常场景引导和备份恢复演练；生产试用前仍需确认个人资料保留及身份核验要求。收费功能继续留到单独业务第二阶段，不因换房扩展提前引入。

## 13. 初学者解释

**换房为什么不能新建入住：** 同一批客人还在酒店，重新建入住会把一次住宿拆成两次，丢失原时间和共同历史。换房只改变正在使用的房间。

**为什么需要使用段：** 每段记录“何时开始用哪间房、何时离开”，像行程的一站；换房新增下一站，过去的房间不会被覆盖。

**为什么锁两间房：** 原房要释放，新房要占用；两边都需按统一顺序排队，防止同一目标被不同前台同时分配，也避免互相等待。

**为什么原房自动建清洁任务：** 客人离开后房间需要打扫，自动建单让前台和客房部不会漏交接；任务没完成前不能直接接待下一位。

**为什么换房与退房会冲突：** 两个操作都要结束同一个当前使用段，必须先锁同一笔入住。先成功后，另一请求的旧使用段就不再有效。

**为什么异常解决与换房互不替代：** 客人搬到新房不代表旧房空调修好，修好空调也不代表客人已搬走；它们记录不同事实。

**为什么事务和幂等都需要：** 事务保证一次操作的所有步骤一起成功或撤回；幂等保证同一操作因网络重试不会再做一遍。一个防止“成功一半”，另一个防止“重复成功”。
