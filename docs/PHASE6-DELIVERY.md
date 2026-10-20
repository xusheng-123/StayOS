# StayOS V1.0 第 6 阶段交付报告

开发日期：2026-10-20。基于第 5 阶段稳定提交 `5dd889f` 增量开发；开发完成后等待验收，现按用户明确指令保存本地 Git 稳定版本。

> 验收完成：193 项 JUnit、86 项 Playwright 全部通过；前后端构建成功。开发库已增量迁移到 V11，现有业务数据快照未变。用户已明确要求将本阶段代码提交 Git，保存稳定版本。

## 1. 实际功能与操作方法

- 六种异常分类与 LOW/MEDIUM/CRITICAL 程度，真实上报人及服务器时间，支持已入住房间上报。
- OPEN → IN_PROGRESS → RESOLVED，允许主管带结论直接解决 OPEN；未解决异常可受控调级，版本检查、原因及前后历史齐全。
- 独立人工维修停用、受控恢复；同房最多一条有效人工停用，历史保留。
- 未解决严重异常或有效人工停用统一阻止新入住；不自动退房、换房或调整清洁/档案状态。
- 列表、房号/楼层/类型/程度/状态筛选、分页、真实统计、详情和历史，复用房态图及房间详情。
- Session/CSRF、五角色权限、服务员数据范围、PostgreSQL 事务与行锁、共享24小时幂等及成功回执查询。

前端入口：`http://127.0.0.1:5173/room-issues`，导航“房间异常”。

1. 前台或客房员工点击“上报异常”，选择真实房间、类型和程度，填写描述。不得填写住客证件号码等敏感信息。
2. 主管或总经理打开异常详情，开始处理、填写解决结论，或填写原因调整严重程度。
3. 人工停用可从异常详情关联创建，也可从页面顶端独立创建。
4. 没有关联异常的停用房间，从“维修记录与恢复”选择房间后解除，不要求先伪造异常。
5. 房态图通过刷新获取最新状态；没有实时推送。清洁完成不等于解除维修停用。
6. 网络结果不确定时使用“重试原请求”或“查询原操作结果”；刷新后可按原标识查询。不在浏览器保存描述/备注正文。

## 2. 主要文件

| 文件 | 用途 |
|---|---|
| `backend/src/main/resources/db/migration/V11__room_issues_and_maintenance.sql` | 增量建表、约束、真实维修事实及幂等结果扩展 |
| `backend/src/main/java/com/stayos/issue/IssueDtos.java` | 请求校验、异常、历史与停用结果结构 |
| `backend/src/main/java/com/stayos/issue/IssueMapper.java` | 参数化SQL、数据范围、行锁、查询与写入 |
| `backend/src/main/java/com/stayos/issue/IssueService.java` | 业务状态、权限复查、事务、历史及幂等流程 |
| `backend/src/main/java/com/stayos/issue/IssueController.java` | 正式HTTP接口和方法权限 |
| `backend/src/main/java/com/stayos/auth/SecurityConfig.java` | 新接口须认证，具体业务仍由细粒度权限控制 |
| `backend/src/main/java/com/stayos/stay/service/IdempotencyService.java`、`stay/mapper/StayMapper.java` | 扩展共用幂等协议的结果种类，保留原操作行为 |
| `backend/src/main/java/com/stayos/room/dto/RoomView.java` | 追加独立异常/严重/人工停用标识 |
| `backend/src/main/java/com/stayos/common/ApiErrors.java` | 不支持的操作返回统一405，不展示堆栈 |
| `backend/src/test/java/com/stayos/IssueIntegrationTest.java` | 真实HTTP、PostgreSQL、并发、故障回滚及浏览器启动 |
| `backend/src/test/java/com/stayos/IssueSchemaTest.java` | Flyway、外键、枚举及同房关联的真实约束测试 |
| `backend/src/test/java/com/stayos/StayTestFacts.java` | 原测试结束时恢复含真实异常/停用的运营视图 |
| `backend/src/test/java/com/stayos/HousekeepingSchemaTest.java` | V8升级到当前版本数量更新，保留数据保护断言 |
| `frontend/src/views/RoomIssuesView.vue`、`types/issue.ts` | 异常/停用页面及数据类型 |
| `frontend/src/components/RoomDetail.vue`、`views/RoomBoardView.vue`、`api/rooms.ts`、`types/room.ts` | 复用房间入口展示真实异常和不可入住原因 |
| `frontend/src/router/index.ts`、`layouts/WorkspaceLayout.vue` | 页面路由和角色导航 |
| `frontend/e2e/issues.spec.ts` | 五角色、业务、手机、网络重试与房态联动测试 |
| `docs/ROOM-ISSUES.md` | 当前业务/API/并发/使用规范 |
| 根README及docs/PRD、DATABASE、API、DEVELOPMENT | 同步当前实现，替代早期类型及阻止标记草案 |

## 3. 数据库与数据保护

V1–V10 保持原内容；仅增加 V11，不删除原表、不覆盖房间或员工资料。

- `room_issues`：真实房间、分类、程度、描述、状态、各阶段人员/时间、结论、版本。
- `room_issue_logs`：REPORT/START/RESOLVE/SEVERITY 的前后状态与程度、真实操作人、原因、时间及异常版本；同异常版本唯一。
- `room_maintenance_stops`：人工停用周期、可选同房异常关联、停用原因/人员/时间、恢复原因/人员/时间；有效记录部分唯一。
- `room_maintenance_logs`：STOP/RESUME 的追加回执；同停用周期同动作唯一，供幂等结果引用。
- 共用 `idempotency_requests` 新增 `result_issue_log_id` / `result_maintenance_log_id`；操作白名单及结果种类约束保持旧业务兼容。
- `room_operational_facts` 统一计算严重异常或人工停用造成的阻止；`room_status` 追加三个事实标识，原房态优先级及可入住判断保持一致。

异常类型/程度/状态、非空描述、真实用户/房间外键、同房异常复合外键、事件时间配对、有效停用唯一及异常状态转换触发器共同保护数据。没有异常或历史删除API；数据库运维账户仍需受控，不宣称已建设不可篡改审计存储。

开发前实际状态：80间房、6种房型、7个用户、0条入住、4条清洁任务，4间 CLEAN、76间 DIRTY。已在 Git 忽略的 `.local` 保存本地备份及结构化快照；没有用旧“80间全DIRTY”快照覆盖现状。所有业务自动化在隔离 `stayos_test` 执行。

## 4. API

详见 [ROOM-ISSUES.md](docs/ROOM-ISSUES.md)，统一 ApiResponse、Session及CSRF。全部写操作带 `Idempotency-Key`。

- `GET/POST /api/room-issues`
- `GET /api/room-issues/statistics`
- `GET /api/room-issues/{id}`、`/{id}/history`
- `POST /api/room-issues/{id}/start`、`/resolve`、`/severity`
- `POST /api/rooms/{id}/maintenance-stop`、`/maintenance-resume`
- `GET /api/rooms/{id}/maintenance-history`
- `GET /api/room-operations/{operation}/{key}`（只查本人成功回执）

处理异常提交 version，解除人工停用提交当前 stopId，防止旧页面误操作新记录。非法参数400、未登录401、越权403、不可见记录404、业务冲突409；无对应删除方法返回405。

## 5. 权限

| 角色 | 上报 | 异常详情/历史 | 处理/调级 | 人工停用/恢复 | 原分房权限 |
|---|---|---|---|---|---|
| ADMIN | 拒绝 | 拒绝 | 拒绝 | 拒绝 | 拒绝 |
| FRONT_DESK | 允许 | 全部 | 拒绝 | 拒绝 | 拒绝 |
| HOUSEKEEPING_STAFF | 允许 | 仅自己提交 | 拒绝 | 拒绝 | 拒绝 |
| HOUSEKEEPING_MANAGER | 允许 | 全部 | 允许 | 允许 | 允许 |
| HOTEL_MANAGER | 允许 | 全部 | 允许 | 允许 | 允许 |

服务员不能通过筛选条件、URL或历史接口越过范围。原权限合并机制继续适用；ADMIN基础房态查询可见必要标识，不获得异常业务详情或住客数据。自主领取继续全部禁止，清洁仍仅由已分配合法负责人执行。

## 6. 并发、幂等、事务与联动

- 锁顺序：幂等预留 → 当前员工共享锁 → 房间 → 异常/停用 → 历史/成功审计/幂等结果。不反向锁入住，兼容原退房“入住→房间”和清洁/批量分房的房间锁。
- 严重异常先提交，等待房间锁的入住取得锁后重新查询而被拒绝；入住先成功，异常可记录但不结束该入住。测试通过实际PG锁等待确认，非仅靠时间猜测。
- 并发不同请求的人工停用、解除及解决异常各只允许一次状态变化；数据库有效停用唯一和版本检查共同保护。
- 原24小时协议不变：本人同操作同标识同内容重放原不可变回执；不同内容拒绝；过期旧标识拒绝；失败回滚后原标识可重试；不清理历史、不保存完整请求正文。
- 异常创建/解决、人工停用/恢复分别对业务表、历史、成功审计、幂等结果注入真实数据库故障，检查无半成品并修复后原标识重试。
- 严重异常与有效人工停用取“或”。处理一个异常不能解除其余异常；解决异常不能关闭独立停用；完成清洁不移除维修，恢复维修不修改清洁或占用。

## 7. 实际测试及构建

实际执行完整测试：

```powershell
. ./scripts/env.ps1
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' package
npm --prefix frontend run build
```

| 检查 | 实际结果 |
|---|---|
| JUnit 总计 | **193，通过193，失败0，错误0，跳过0** |
| 本阶段 IssueIntegrationTest | 46通过：45项HTTP/数据库/并发/回滚验证＋1项浏览器启动验证 |
| 本阶段 IssueSchemaTest | 5通过，真实PostgreSQL约束和迁移校验 |
| 前五阶段 JUnit 回归 | 原142项全部通过 |
| Playwright 总计 | **86通过，失败0，不稳定0，跳过0；retries=0** |
| 本阶段浏览器测试 | 14通过，五角色、完整处理、独立停用恢复、数据范围、房态详情、丢失响应、刷新恢复、桌面和390px手机 |
| 原有浏览器测试 | 72通过：认证13、房间11、入住10、退房12、清洁及批量分房26 |
| 前端构建 | vue-tsc类型检查与Vite构建通过 |
| 后端构建 | Java编译及Spring Boot可运行JAR打包通过，已实际启动 |
| 迁移 | 测试库及开发库均完成V11；校验V1–V10文件内容未变 |
| 数据保护 | 迁移前后80间房、6种房型及4条任务的逐字段快照相同，入住仍0条，账号仍7个 |
| 运行复查 | 后端8080健康UP，前端5173可访问；5角色认证、原权限及新异常权限复查通过 |

需要区分：完整 `mvn ... package` 中193项测试全部通过，但最后打包时旧后端进程占用JAR，Windows拒绝重命名，命令因此以失败退出。停止已验证的旧StayOS进程后执行 `mvn -B -f backend/pom.xml -DskipTests package` 成功；这个重打包步骤没有再次运行测试，没有把它记为新的测试通过数量。

迭代中曾遇到旧迁移条数断言、接口安全路径注册、控制字符检查、测试过期时间夹具、Java测试编译及下拉框无障碍名称问题，均已修复。补下拉框名称时的TypeScript分支类型提示也已修复，最终构建和完整回归覆盖当前代码。无未解决的测试失败。

证据保留在Git忽略目录：`.local/phase6-full-tests.log`、`.local/phase6-backend-build.log`、`.local/phase6-frontend-build.log`、`.local/phase6-test-summary.json`、各 `playwright-*-results.json` 和 `backend/target/surefire-reports/TEST-*.xml`。桌面及手机截图已实际检查。开发库仅做登录/权限查询等复查，没有写入异常或停用测试数据。

当前未解决的核心功能问题：**无已知阻塞项**。未执行项目见第8节的生产/负载/真机边界，不把这些描述为通过。

## 8. 影响与边界

完整回归通过，保持认证、账号、房间、房型、入住、退房、清洁和按员工批量分房的原业务规则；没有新增收费、支付、财务、预订、换房、维修派单或清洁验收。

V1–V10内容未变；旧“按类型决定blocksCheckIn”的未实现草案依据本阶段明确指令更新为分类＋严重程度＋独立人工停用。未新增角色、技术栈、消息队列或微服务。

本阶段未做生产部署、真实手机硬件实测、压力容量测试、断电恢复演练或不可篡改审计存储。自由文本有长度/控制字符检查及敏感信息提示，没有声称能自动识别和清除所有误填的个人资料。

## 9. 第 7 阶段建议

正式换房沿用稳定 stay_records 与 stay_room_segments，先锁入住，再按房间内部ID升序锁原房/目标房，重新验证当前段及目标房统一可入住性；同事务结束原段、建立新段、记录换房、原房标脏、创建唯一清洁任务、审计及幂等结果。退房/换房竞争只能一方成功。

房间异常与维修停用在换房后继续保留，不因客人离开而自动解决。故障换房关联原房真实未解决异常，关联条件在第7阶段结合实际模型核对；本阶段没有提前实现换房API或任务来源。

## 10. 初学者概念解释

**异常记录与维修停用的区别：** 异常是“发现了什么问题”，可以有很多条；人工维修停用是主管作出的“这间房暂不接待新客”的独立决定。严重异常本身也能阻止入住，不要求先手动停用。

**维修为什么独立于清洁：** 打扫干净并不能修好漏水，修好空调也不代表床铺已清洁。分开记录才能同时表达真实情况，避免一个操作把另一个问题掩盖。

**严重异常为什么影响可入住：** 前台看到的页面可能已过时，后端必须用最新严重异常和停用记录决定是否允许新入住，不能只相信卡片颜色。

**解决异常为什么不能直接可入住：** 还要检查其他严重异常、人工停用、清洁任务、住客占用和档案启用。解决一个问题只是减少一个阻止条件。

**已入住异常为什么不能自动退房：** 发现故障并不代表客人已经离店。系统须保留入住事实，由前台之后按正式退房或换房流程处理。

**事务与行锁如何配合：** 事务像“整套操作一起交卷”，任一步失败就一起撤回；行锁像“同一间房依次办理”，故障和入住不会同时根据旧信息作决定。先提交的事实由后取得锁的操作重新检查。


## 11. 当前运行与Git状态

前端 `http://127.0.0.1:5173`，后端 `http://127.0.0.1:8080/api/health`。新后端已启动；旧内存Session因重启失效，请重新登录。已有测试账号继续使用本地忽略的“测试账号.md”，本报告不重复记录密码。

开发阶段按要求未自动提交；验收后依据用户的明确指令创建本地稳定版本提交，不推送、不重置历史。提交前复核既有测试记录（193项JUnit、86项Playwright）及构建记录，检查运行状态与变更范围；本次提交操作未重新运行完整测试。密钥、数据库备份和本地测试账号文档仍被Git忽略；最终变更检查无实际密钥或密码命中。
