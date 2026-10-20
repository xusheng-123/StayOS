# 第 2 阶段交付记录：房间管理与房态

验证日期：2026-10-20（Asia/Shanghai）。按用户要求完成 2.1—2.5，沿用既有技术栈和模块化单体结构，没有重建工程、删除原有表或清空开发数据。

## 实际完成范围

- 房型：新增、分页搜索、编辑、启用/停用；名称、编码、描述、床位说明、建议人数与时间信息。
- 房间：逐间新增、分页搜索、编辑基础资料、查看详情、受控启用/停用；字符串房号、内部稳定 ID、楼层、房型、备注。
- 房态：独立占用/清洁/维修/启用维度，统一综合状态和可入住判断；INSPECTION 只支持数据与展示，不实现检查流程。
- 房态图：真实后端卡片、楼层/房型/综合房态/房号组合筛选、分页、八项统计、详情抽屉、手动刷新和手机适配。
- 权限与一致性：五角色查询，管理员维护；后端方法权限、CSRF、中文错误、数据库约束、关键成功审计、资料版本防覆盖。
- 无房价、账单、结算、支付及额外服务表或接口；无入住、退房、换房、清洁任务或完整维修工单业务。

本地开发库没有自动生成演示房型或房间。管理员应先新增房型，再新增房间。新房默认 DIRTY，在后续清洁任务功能完成前，页面不会让用户直接改成 CLEAN；这符合已确认规则。

## 实施顺序及验证

| 小任务 | 验证结果 |
| --- | --- |
| 2.1 数据库及模型 | RoomSchemaTest 8 项 + 原 SchemaTest 3 项，11 项通过后进入下一步 |
| 2.2 后端维护接口 | 首轮发现列表分页反射错误，修复后 RoomIntegrationTest 当时 12 项全部通过 |
| 2.3 房态查询及统计 | RoomIntegrationTest 当时 15 项 + RoomSchemaTest 8 项，23 项通过；包含 64 种状态组合 |
| 2.4 前端页面 | TypeScript 和生产构建通过；真实 Edge 房间场景最终 11/11 通过 |
| 2.5 联调及回归 | 最终 JUnit 53/53，认证浏览器 13/13，房间浏览器 11/11；后端打包成功；开发库增量迁移及原账号实测通过 |

## 主要文件

| 文件或目录 | 用途 |
| --- | --- |
| backend/src/main/resources/db/migration/V3__rooms.sql | 仅新增房型、房间及房态视图，保留 V1/V2 |
| backend/src/main/java/com/stayos/room/entity/ | Room 与 RoomType 的 MyBatis-Plus 基础模型 |
| backend/src/main/java/com/stayos/room/dto/ | 请求校验、筛选、分页返回项、房态统计 |
| backend/src/main/java/com/stayos/room/mapper/ | 参数化查询、分页、统计、行锁和资料更新 |
| backend/src/main/java/com/stayos/room/service/ | 资料规则、版本检查、审计事务及一致房态查询 |
| backend/src/main/java/com/stayos/room/controller/ | 房型/房间维护、房态与楼层接口 |
| backend/src/main/java/com/stayos/auth/RolePermissions.java | ADMIN 增加 room:manage，其他角色保留 room:read |
| backend/src/main/java/com/stayos/auth/SecurityConfig.java | 新接口接入既有认证、CSRF 和权限体系 |
| backend/src/main/java/com/stayos/common/ApiErrors.java | 参数错误、房号/编码重复及不存在接口的中文响应 |
| frontend/src/types/room.ts、api/rooms.ts | 类型、接口参数、统一状态文字和楼层显示 |
| frontend/src/components/RoomFilters.vue、RoomDetail.vue | 共享筛选表单和详情抽屉 |
| frontend/src/views/RoomBoardView.vue | 房态卡片、筛选及统计 |
| frontend/src/views/RoomManageView.vue、RoomTypeManageView.vue | 管理员房间和房型表格、编辑表单 |
| frontend/src/rooms.css | 房间页面、状态配色及响应式布局 |
| frontend/src/router/index.ts、layouts/WorkspaceLayout.vue、views/WorkspaceView.vue | 权限路由、导航和工作区入口；保留原五工作区 |
| backend/src/test/java/com/stayos/RoomSchemaTest.java、RoomIntegrationTest.java | 真实数据库约束、业务规则、HTTP 权限和浏览器入口 |
| frontend/e2e/rooms.spec.ts | 11 项真实 Edge 联调场景 |
| frontend/e2e/auth.spec.ts、playwright.config.ts、backend/src/test/java/com/stayos/AuthIntegrationTest.java | 原认证回归保留，区分两组导航与两份浏览器报告 |
| README.md、docs/PRD.md、DATABASE.md、API.md、DEVELOPMENT.md、ROOMS.md | 同步已确认决定、实际数据结构、接口说明和开发边界 |

## 数据表及字段

新增两张表，不为楼层建立多余配置表；楼层清单从实际房间汇总。

| 表 | 主要字段 | 说明 |
| --- | --- | --- |
| room_types | id、type_code、type_name、description、bed_description、suggested_occupancy、enabled、version、created_at、updated_at | 房型编码唯一；建议人数 1—20；停用保留已有房间关联 |
| rooms | id、room_number、floor、room_type_id、notes、enabled、cleaning_status、version、created_at、updated_at | 房号全酒店唯一；楼层 -99 至 199 且不能为 0；房型外键；清洁状态受约束 |

两张表均以 BIGINT 内部 ID 关联，时间采用 TIMESTAMPTZ。房号及房型编码去空格、转大写，支持数字/字母/下划线/短横线；0801 与 801 不同，a801 与 A801 相同。没有房型/房间硬删除接口，数据库也阻止删除被房间引用的房型。

新增两个只读视图：

- room_operational_facts：占用、阻止异常、未完成任务、使用历史的统一事实入口。本阶段没有对应业务记录，生产定义明确为 false；没有另存独立可编辑状态。
- room_status：统一计算占用/维修/启用维度、综合标签和 canCheckIn，列表/详情/筛选/统计共同使用。

**后续扩展的必做事项：** 开发入住、清洁和异常模块时，必须新增迁移，把 room_operational_facts 改成查询真实业务记录。新增业务操作须锁同一 rooms 行，重新检查状态。当前的“无业务记录”事实不能原封不动带入未来运营流程。

测试只在 stayos_test 库临时建立 test_room_facts 夹具并替换事实视图，结束后恢复并删除夹具表。它不进入 Flyway、不写入开发库，也不开放给浏览器用户修改。

## 房态和权限规则

可入住 = 档案启用 + 无占用 + CLEAN + 无阻止异常 + 无未完成清洁任务。

综合标签优先级：档案停用 → 已入住 → 维修停用 → 正在打扫 → 待检查 → 待打扫 → 暂不可分配 → 空闲可入住。各维度依然分别返回，例如已入住房间的故障标识不会消失。

新房默认待打扫；停用房不能入住；停用房型只禁止新增/改绑，不自动停用现有关联房间。已有使用历史不能直接改房号。存在占用或未完成清洁任务，不能停用房间。

| 单角色 | 查询房型/房间/房态/统计/楼层 | 新增、编辑、启停房型/房间 |
| --- | --- | --- |
| ADMIN | 200 | 200（合法请求） |
| HOTEL_MANAGER | 200 | 403 |
| FRONT_DESK | 200 | 403 |
| HOUSEKEEPING_MANAGER | 200 | 403 |
| HOUSEKEEPING_STAFF | 200 | 403 |
| 未登录 | 401 | 401（已携带有效 CSRF 的测试请求） |

没有有效 CSRF 的写请求会先被安全保护拒绝。前端菜单遵守同一权限，但真正拦截由后端完成。原来的多角色权限并集、账号禁用撤销登录、总经理不能进入管理员工作区均已回归通过。

## 页面及接口

启动后打开：

- 房态图：http://127.0.0.1:5173/rooms，五角色均可访问。
- 房型管理：http://127.0.0.1:5173/manage/room-types，仅管理员。
- 房间管理：http://127.0.0.1:5173/manage/rooms，仅管理员。
- 点击卡片或表格“详情”打开房间详情。

原五个测试账号仍可使用，见项目根目录本地的测试账号文档。没有修改密码、启用状态或角色关联。后端升级重启后 Session 按既有约定失效，需要重新登录。

完整接口、请求字段、参数范围和错误码见 [ROOMS.md](ROOMS.md) 的“已实现接口说明”，API.md 第 5 节也已链接该规范：

- GET/POST /api/room-types；PUT /api/room-types/{id}；POST /api/room-types/{id}/set-enabled。
- GET/POST /api/rooms；GET/PUT /api/rooms/{id}；POST /api/rooms/{id}/set-enabled。
- GET /api/room-status、/api/room-status/statistics、/api/floors。

页面查询数据来自 HTTP 接口，再由后端 Mapper 查询 PostgreSQL；没有把示例房间硬编码成最终页面数据。房态图同次请求的列表、总数和统计取自同一可重复读快照。统计遵循当前全部筛选条件，覆盖所有分页结果；不同维度可重叠，不能相加当作总数。

## 实际执行的测试

最终后端及浏览器回归命令：

```powershell
. .\scripts\env.ps1
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' package
npm --prefix frontend run build
```

平时也可使用 scripts/test.ps1 -Browser。普通测试不加 -Browser 时，两处浏览器入口会明确标为跳过。

最终 JUnit **53 项通过，失败 0、错误 0、跳过 0**：

| 测试类 | 项数 |
| --- | --- |
| AuthIntegrationTest | 22 |
| BootstrapTest | 3 |
| IpRateLimitTest | 1 |
| SchemaTest | 3 |
| RoomSchemaTest | 8 |
| RoomIntegrationTest | 16 |

其中两项是浏览器测试入口；浏览器的认证 13 个场景、房间 11 个场景不要与 JUnit 重复统计为 77 项独立测试。

已覆盖用户要求的全部 15 类场景：新增房型、编码唯一、新增房间、房号唯一、编辑、停用、停用不可入住、房态筛选、楼层/房型筛选、统计、状态组合、普通用户不能维护、总经理查询、未登录拦截、迁移执行。

额外验证：64 种状态组合、两个管理员同时修改仅一人成功、旧版本拒绝、房型停用后的关联保护、历史禁止改号、占用/任务禁止停用、错误字段不能改变房态、不存在任意改房态接口、中文错误、手机无横向溢出。浏览器页面写入的备注和启停结果由 JUnit 直接查询 PostgreSQL 再确认。

测试中曾出现并已修复：

1. MyBatis 对 record 的计算分页属性反射不兼容，改为显式参数。
2. 不存在的房间子接口被错误转换为 500，现返回明确 404。
3. Element Plus 表格行插槽类型需明确为业务类型，修正后 TypeScript 通过。
4. 下拉框无障碍标签没有传到输入控件、占位文字拦截点击，修正后全部浏览器场景重新通过。

原始记录（运行产物不提交 Git）：

- .local-phase2-final.log：最终全量测试及 JAR 打包。
- backend/target/surefire-reports：53 项 JUnit 结果。
- .local/browser-run.log、.local/playwright-results.json：13 项认证浏览器场景。
- .local/room-browser-run.log、.local/playwright-rooms-results.json：11 项房间浏览器场景。
- .local-room-frontend.log：TypeScript 与前端构建。
- .local/room-board.png、room-board-mobile.png、room-management.png：已查看的真实浏览器截图。

开发库额外验证：从 V1/V2 增量迁移到 V3，原有 5 用户、5 角色及角色关联数量不变，账号与密码哈希摘要一致；五个现有测试账号逐一登录成功并成功查询房态。迁移后开发库房型与房间均为 0，未灌入测试夹具。后端健康接口与前端房态页 HTTP 均正常。

## 未解决事项、既有功能及下一步

没有发现阻塞本阶段已交付功能的未解决故障。Git 保持尚未提交的工程状态，没有重置用户修改或代替用户提交代码；测试账号文档仍被 Git 忽略。

未执行生产 HTTPS/代理部署、压力测试、备份恢复或多实例 Session 验证。不实现 Excel 导入、检查流程、业务房态变更或入住闭环，均不是本阶段已经验证的能力。

原有认证、Cookie/CSRF、登录失效、限流、管理员初始化、角色工作区均已通过回归。V1/V2 文件没有修改，开发库认证数据未改变。后端重启导致原会话失效属于既有设计。

下一阶段建议开发入住：先按 PRD 第 9 节确认住客字段/同住人范围，以及关键写接口的幂等保存、期限与重试策略；这些尚未确认，触及时必须暂停等待反馈。再新增入住及房间使用段迁移，把事实视图接到真实记录，复用可入住判断、事务锁、权限与审计。清洁和异常模块到相应阶段再接入，不能以直接改房态接口代替。

## 给初学者的解释

- **为什么需要内部 ID？** 房号是门牌，ID 是档案编号。关联记录使用固定 ID，改门牌也不会让历史指向另一间房。已有历史是否允许改门牌是另外一条业务规则；本项目已经决定不直接改。
- **为什么房号用字符串？** 0801 的开头 0、A801 的字母都有意义，整数不能完整保存它们。
- **为什么房态不能只有一个字段？** 已入住的房间也可能有设备故障，空房也可能正在打扫。分开记录事实，才能同时表达这些情况，再计算一个方便阅读的标签。
- **什么是数据库唯一约束？** 数据库自己的防重规则。即使两个管理员同一时刻提交相同房号，也最多只有一条能保存。
- **前端如何获取数据？** 浏览器请求 /api/room-status，后端检查登录和权限，通过 Mapper 查询 PostgreSQL，返回 JSON；Vue 把 JSON 中的房间画成卡片。
- **后端为什么检查权限和状态？** 用户可以绕过菜单直接发请求；页面数据也可能已过时。后端必须自己决定谁能修改，以及当前房间是否满足操作条件。
- **Flyway 为什么不能改已执行的文件？** 它会记录每份迁移的版本和内容校验值。修改旧文件会让不同机器的数据库经历不一致，也会让校验失败。新需求应增加 V4 等新迁移，让大家按同样顺序升级。
