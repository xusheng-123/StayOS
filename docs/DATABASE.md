# 酒店前台与客房协同管理系统｜数据库设计草案

> **这份文档是做什么的？** 它说明系统需要保存哪些数据、不同数据之间是什么关系。数据库可以理解为系统长期保存信息的结构化仓库。
>
> **重要提醒：** 这是面向初学者的“概念设计草案”，不是已经执行的 SQL，也不是最终数据库标准。真正建表前，要根据 PRD 和真实酒店流程复核。表名和字段名可以调整，但业务含义不能随意丢失。

> **当前范围：** 第一版仅建立基本业务所需表，不建立收费业务表；额外服务、房费、账单、结算、押金、收款及退款留到第二阶段。第一版不启用主管验收、住中清洁和预订。第 10 节仅为第二阶段扩展参考。

## 1. 数据库基础概念

- **表（Table）**：一类信息的集合，例如房间表。
- **字段（Column）**：一条记录包含的属性，例如房号、楼层。
- **记录/行（Row）**：某一个具体对象，例如 801 房。
- **主键（Primary Key）**：数据库内部用来唯一识别一条记录的编号。
- **外键（Foreign Key）**：指向另一张表中记录的字段，用来表达关联关系。
- **唯一约束（Unique）**：防止某个值重复，例如同一家酒店不能有两个相同房号。
- **事务（Transaction）**：将多个相关的数据操作作为一组成功或失败。

## 2. 设计原则

1. 房号是给员工看的业务编号，建议保存为字符串；例如 `801`、`0801`、`A801` 都可能是合法房号。
2. 表之间优先通过内部 ID 关联，不要靠客人姓名或房号文本随意关联历史记录。
3. 当前状态与历史过程应分开保存。例如，房间当前状态保存在房间相关数据中，每次入住和每次清洁都应有独立历史记录。
4. 第二阶段金额用定点数类型保存，不使用浮点数表示精确金额；第一版不计算或保存收费结果。
5. 事件时间统一使用 `TIMESTAMPTZ`，实际操作时间由服务器生成，接口使用带时区的 ISO 8601，默认按 `Asia/Shanghai` 显示。预计退房时间由前台填写，不自动释放房间。第一版不支持历史补录。
6. 个人敏感信息应遵循最少必要原则。开发测试使用虚构数据，真实数据设置访问限制。
7. 数据库约束和后端业务校验应同时存在；不能只靠页面阻止错误。

## 3. 第一版核心表概览

| 表名（建议） | 保存什么 | 为什么需要 |
|---|---|---|
| `users` | 员工账号 | 登录和识别操作人员 |
| `roles` | 角色定义 | 区分系统管理员、前台、客房服务员、客房主管、酒店总经理 |
| `user_roles` | 用户与角色对应关系 | 支持一个员工拥有一个或多个角色 |
| `room_types` | 房型信息 | 统一维护大床房、双床房等类型 |
| `rooms` | 房间基础信息 | 保存房号、楼层、房型等 |
| `guests` | 客人基础信息 | 减少重复登记；需要严格保护个人信息 |
| `stay_records` | 每次入住 | 保留每一次入住的开始、结束和房间关系 |
| `stay_room_segments` | 每段房间使用记录 | 保存一次入住期间每段使用哪间房、何时进入和离开 |
| `room_change_records` | 换房历史 | 追踪原房间、新房间、原因和办理人 |
| `housekeeping_tasks` | 清洁任务 | 关联退房、换房来源，支持人工创建初次或重新清洁任务 |
| `housekeeping_task_logs` | 清洁任务的状态变化 | 追踪创建、分配、开始和完成 |
| `room_issues` | 房间异常 | 记录故障、遗留物品等情况 |
| `audit_logs` | 重要业务操作摘要 | 支持问题排查和责任追踪 |

第一版不创建 `housekeeping_inspections` 或收费业务空表。五个固定角色的权限在后端统一配置，不建立可视化权限编辑器；保留 `user_roles` 以支持一人多个角色。

## 4. 建议字段（供讨论）

以下是字段用途说明，不代表每个字段都必须照搬。`BIGINT`、`VARCHAR` 等是数据库字段类型的名字，初学阶段只需先理解它们表达什么数据。

### 4.1 `users`：员工账号

**第 1 阶段实现补充：** 实际建表以 `backend/src/main/resources/db/migration` 为准。当前只建立 `users`、`roles`、`user_roles`、`audit_logs`，不一次性建立后续业务表。`users` 另有 `security_version`、`failed_login_attempts`、`locked_until`，用于撤销旧会话及限制登录尝试；用户名规范化为小写并唯一。BCrypt cost=12，数据库检查哈希格式，应用负责真正计算哈希。账号状态、密码、角色关联变化的迁移触发器会更新安全版本。角色权限映射集中在 Java 中，无额外权限配置表。详见 AUTHENTICATION.md。

| 字段 | 建议类型 | 用途 | 注释 |
|---|---|---|---|
| `id` | BIGINT | 内部用户编号 | 主键，不直接用姓名当 ID |
| `username` | VARCHAR(100) | 登录用户名 | 需要唯一约束 |
| `password_hash` | VARCHAR(255) | 密码哈希 | 保存经过安全算法处理的结果，不能保存明文密码 |
| `display_name` | VARCHAR(100) | 页面显示姓名 | 供员工识别 |
| `enabled` | BOOLEAN | 账号是否启用 | 离职或停用账号时可禁用 |
| `created_at` | TIMESTAMPTZ | 创建时间 | 由服务器生成 |
| `updated_at` | TIMESTAMPTZ | 最近修改时间 | 帮助追踪资料变化 |

### 4.2 `roles` 与 `user_roles`：角色权限关联

`roles` 可包含 `id`、`role_code`、`role_name`。例如 `FRONT_DESK`（前台）、`HOUSEKEEPING_STAFF`（客房服务员）、`HOUSEKEEPING_MANAGER`（客房主管）、`HOTEL_MANAGER`（酒店总经理）、`ADMIN`（系统管理员）。

`user_roles` 用 `user_id` 和 `role_id` 把用户与角色关联起来，对二者组合设置唯一约束，第一版支持一个员工拥有多个固定角色，不开发自定义角色。

【注释】角色只是“岗位类别”。具体每个角色能做什么，还需要由后端权限规则定义；不能因为用户的前端菜单不同就认为权限已经安全。

酒店总经理获得前台和客房部的业务权限集合，包括主管的任务查看、分配、人工创建和异常解决，但不继承 `ADMIN`。管理员默认也不拥有业务办理权限，需要时追加业务角色。账号管理、角色分配、房型和房间资料维护默认仅管理员可操作。第一版权限矩阵以 PRD 第 2.6 节为准。

账号禁用、密码重置和角色变更要留审计记录，并使已有会话及时失效或重新校验授权，不能只影响下次登录。被历史业务引用的员工不硬删除。

### 4.3 `room_types`：房型

**第 2 阶段实际结构：** V3 新增 room_types、rooms 及两个只读视图，字段、约束、事实来源和扩展要求详见 [ROOMS.md](ROOMS.md)。以下表格仍是精简的整体业务草案，不能代替实际 Flyway 迁移文件。

| 字段 | 建议类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 房型内部编号 |
| `type_code` | VARCHAR(50) | 房型编码，可用于系统内部识别 |
| `type_name` | VARCHAR(100) | 房型名称，例如豪华大床房 |
| `enabled` | BOOLEAN | 是否启用 |

第一版不设置房价字段和计费逻辑。第二阶段再设计价格及历史价格快照，不能用最新参考价倒算历史房费。

房型停用仅禁止新增或改绑房间选择该房型，不自动停用已引用房间或结束已有入住，历史关联继续保留。

### 4.4 `rooms`：房间基础资料

| 字段 | 建议类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 房间内部唯一编号 |
| `room_number` | VARCHAR(20) | 员工看到的房号，如 `801`、`A801` |
| `floor` | INTEGER | 楼层（如果酒店使用楼层概念） |
| `room_type_id` | BIGINT | 指向 `room_types.id` |
| `enabled` | BOOLEAN | 房间档案是否启用 |
| `cleaning_status` | VARCHAR(20) | `DIRTY`（待清洁）、`CLEANING`（清洁中）、`CLEAN`（清洁合格）、`INSPECTION`（待检查，仅数据与展示，不开发检查流程） |
| `created_at` | TIMESTAMPTZ | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 修改时间 |

建议对 `room_number` 设置唯一性限制。若系统以后支持多家酒店，应考虑加入 `hotel_id`，并使用“酒店 ID + 房号”的组合唯一约束。

占用以未结束的 `stay_room_segments` 为准；清洁以 `cleaning_status` 为准；维修阻止来自未解决 CRITICAL 的 `room_issues` 或有效 `room_maintenance_stops`，不再用一个可任意修改的综合房态字段保存所有事实。

可入住条件为：房间档案启用、无当前占用、清洁合格、无入住阻止、无未完成清洁任务。后端统一判断，接口返回综合展示状态与各维度。主要展示状态优先级见 PRD 第 5.1 节。新建房间默认 `DIRTY`，人工创建并完成任务后才能入住。

存在当前占用或未完成清洁任务时禁止停用房间档案；停用不删除历史。清洁完成不解除异常，异常解决也不自动把清洁状态改成 `CLEAN`。

### 4.5 `guests`：客人基础资料

| 字段 | 建议类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 客人内部编号 |
| `display_name` | VARCHAR(100) | 姓名或业务显示名称 |
| `phone` | VARCHAR(50) | 联系方式（若业务需要） |
| `created_at` | TIMESTAMPTZ | 创建时间 |

**第 3 阶段已确认调整：** 登记全部实际住客，采用 stay_guests 关联；证件和电话加密，查询仅脱敏。guests 是每次登记快照，不按证件自动合并常客档案。实际字段以 V4/V5 增量迁移和 [CHECK-IN.md](CHECK-IN.md) 为准；上表仅保留基础字段概览。姓名、证件类型、证件号必填，电话和备注选填；真实试用前仍需确认个人信息保留期限和身份核验要求。

### 4.6 `stay_records`：入住记录

| 字段 | 建议类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 入住记录内部编号 |
| `stay_guests` 关联 | 独立关联表 | 关联全部实际住客，标记一名主要联系人，不再使用单一 guest_id |
| `check_in_at` | TIMESTAMPTZ | 实际入住时间，由服务器生成 |
| `expected_check_out_at` | TIMESTAMPTZ | 前台填写的预计退房时间，不产生自动退房 |
| `actual_check_out_at` | TIMESTAMPTZ | 服务器生成的实际退房时间，未退房时为空 |
| `status` | VARCHAR(30) | 例如 `IN_HOUSE`、`CHECKED_OUT` |
| `created_by` | BIGINT | 办理员工 ID |
| `checked_out_by` | BIGINT | 退房员工 ID，未退房时为空 |
| `notes` | TEXT | 入住或退房必要备注，避免敏感信息 |
| `created_at` | TIMESTAMPTZ | 创建时间 |

该表表示一次入住，不再保存含义模糊的 `room_id`。当前房间和历史房间都从房间使用记录读取；退房结果保存在本表，不要求另建一张退房表。第一版无结算状态、应收、实收等收费字段。

### 4.7 `stay_room_segments`：房间使用记录

| 字段 | 建议类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 房间使用记录 ID |
| `stay_record_id` | BIGINT | 所属入住 |
| `room_id` | BIGINT | 本段使用的房间 |
| `entered_at` | TIMESTAMPTZ | 进入时间，由服务器生成 |
| `exited_at` | TIMESTAMPTZ | 离开时间，当前使用中为空 |
| `end_reason` | VARCHAR(20) | 未结束为空；结束后为 `CHECK_OUT` 或 `ROOM_CHANGE` |

一次入住同时只有一段未结束记录，一个房间同时也只能有一段未结束记录；分别以 `exited_at IS NULL` 为条件建立部分唯一索引。部分唯一索引可以理解为“只约束当前占用，允许保留许多历史记录”。时间应满足离开不早于进入。

入住时创建第一段；换房时关闭旧段、创建新段；退房时关闭当前段。关闭与创建使用同一服务器业务时间。`IN_HOUSE` 的入住必须有且仅有一段未结束记录，`CHECKED_OUT` 不得有未结束记录；这类跨表规则由业务事务校验。

示例：入住 S001 先使用 801，换到 805，再退房，共保留两段记录，历史不会被新房号覆盖。

### 4.8 `room_changes`：换房历史（V12已实现）

实际表为 room_changes，详见 [ROOM-CHANGES.md](ROOM-CHANGES.md)。字段包括 id/stay_record_id、old/new_room_id、old/new_segment_id、reason_type/reason、可选issue_id、changed_at/operated_by/created_at，原新房号与房型快照。原因 GUEST_REQUEST/ROOM_ISSUE/OTHER，说明必填；异常仅ROOM_ISSUE下选填，须属于原房。最新阶段指令取代旧强制关联阻止异常草案。

两组复合外键绑定同一次入住的真实房/段，原段和新段分别唯一；原新房不同。插入触发器核对旧段结束原因为ROOM_CHANGE，旧退出时间等于新进入时间和换房时间、快照一致。当前占用仍只由有效stay_room_segments派生，原有部分唯一索引不变。

### 4.9 `housekeeping_tasks`：清洁任务（第 5 阶段实际结构）

原 V7/V8 表增量扩展为退房、人工及V12换房任务。实际字段及约束见 [HOUSEKEEPING.md](HOUSEKEEPING.md) 与 `V9__housekeeping_workflow.sql`。

- 来源 `CHECKOUT` 配 `CHECKOUT_CLEANING`，`source_id=stay_record_id`，三项来源关联必填；保留 `room_segment_id/stay_record_id/room_id` 复合外键，不能关联无关房间或入住。
- 来源 `MANUAL` 配 `MANUAL_CLEANING`，`source_id/stay_record_id/room_segment_id` 必须全部为空，真实房间/创建人及非空 `notes` 原因必填。
- V12来源 `ROOM_CHANGE` 配 `ROOM_CHANGE_CLEANING`，source_id=room_change_id且真实入住、原段、原房必填，以复合外键匹配room_changes；保留来源唯一/段唯一/同房未完成唯一。CHECKOUT和MANUAL不得填写room_change_id。
- V12共用幂等新增result_room_change_id外键、ROOM_CHANGE操作及互斥结果约束；旧业务结果保持兼容。
- 状态 `PENDING/IN_PROGRESS/COMPLETED`；未完成房间部分唯一索引同时覆盖前两种状态，完成历史允许保留并再次建任务。
- `assigned_to/assigned_by/assigned_at/claimed_at` 保存当前归属；`started_by/started_at` 保存最初开始事实；`completed_by/completed_at/completion_notes` 保存最终完成事实，`version` 防止旧页面覆盖。
- 自主领取已关闭，claimed_at 与历史 CLAIM 仅兼容既有数据。主管分配后仍 PENDING，开始才 CLEANING；清洁中重新分配必须带原因，保留原开始时间/人员并记录交接。完成仅当前负责人，时间和人员组合由数据库检查约束保护。
- 人工任务必须空闲、DIRTY、无未完成任务；不直接把 CLEAN/INSPECTION/占用房间改脏。清洁流程不解除异常或修改占用/启用。

### 4.10 `housekeeping_task_logs`：清洁任务历史（第 5 阶段已实现）

字段：`id/task_id/action/from_status/to_status/from_assignee/to_assignee/operator_id/operated_at/reason/task_version`。动作 `AUTO_CREATE/MANUAL_CREATE/CLAIM/ASSIGN/REASSIGN/START/COMPLETE`；`REASSIGN` 同时记录待处理改派和清洁中接管，清洁中接管的前后状态均为 IN_PROGRESS，原因必填。

`(task_id,task_version)` 唯一，创建为版本 0，以后每个合法动作递增并追加日志；无普通修改/删除历史接口。旧退房任务按真实创建人/时间补记 AUTO_CREATE。任务、房间状态、历史、成功审计、请求结果均在同一事务中保存。

共用幂等表新增 `result_task_log_id` 外键及六个 HK 操作类型，结果类型检查防止入住结果与任务结果混用。原入住/退房结果及 24 小时协议保持不变。

### 4.11 房间异常与人工维修停用（V11 已实现）

详见 [ROOM-ISSUES.md](ROOM-ISSUES.md)。room_issues 保存房间、类型、严重程度、OPEN/IN_PROGRESS/RESOLVED、上报/开始/解决人员时间、结论和版本；room_issue_logs 保留每次动作的前后状态与程度及原始上报事实。

room_maintenance_stops 保存独立人工停用及恢复周期；room_maintenance_logs 保存唯一的 STOP/RESUME 回执供历史及幂等结果关联。有效人工停用以 resumed_at 为空识别，同房部分唯一索引防重复。可选关联异常用同房复合外键，不能关联其他房间。

room_operational_facts 把未解决 CRITICAL 或有效人工停用归为 has_blocking_issue，沿用既有字段语义表示维修阻止；room_status 追加 has_open_issue / has_critical_issue / has_manual_maintenance_stop，所有可入住判断共用派生事实。清洁状态仍只在 rooms 保存，不增加可写维修状态副本。

idempotency_requests 新增两种日志结果外键和操作白名单，原有入住、退房、清洁和批量分房结果保持兼容。V1–V10 不改，V11 无清空或覆盖现有数据操作。

### 4.12 `audit_logs`：关键操作日志

建议字段：`id`、`operator_id`、`action`、`target_type`、`target_id`、`result`、`created_at`、`request_id`（如有需要）。

`created_at` 使用 `TIMESTAMPTZ`。成功审计与关键业务同事务保存；失败或拒绝记录在业务回滚后独立保存，避免被一起回滚。查询范围按 PRD 第 2.6 节控制，不开放普通修改和删除接口，不记录密码、完整证件号码等敏感数据。

未能识别登录用户的失败/拒绝记录允许 `operator_id` 为空，不伪造员工 ID；保留必要请求标识与失败原因，且不记录提交的密码。

## 5. 表之间的关系（简化理解）

- 一个房型可以对应多个房间。
- 一个客人可以有多次入住记录。
- 一次入住有一段或多段房间使用记录，同时最多一段未结束；已退房时没有未结束段。
- 一个房间可有许多历史使用记录，同时最多一段未结束。
- 一次换房关联同一次入住的原段和新段，并保存原因与经办人。
- 一个房间可以有很多条清洁任务历史。
- 一个清洁任务可以有多条状态变更日志。
- 一个员工可以创建或执行多条任务和业务记录。
- 一段已结束的房间使用记录只产生一个离房清洁任务；人工清洁不关联来源段。
- 一个房间可有多个异常，所有未解除的入住阻止都必须检查。

【注释】这就是数据库关系设计。它决定数据如何互相找到对方，是系统长期扩展时很重要的基础。

## 6. 必须考虑的数据完整性规则

1. 房间 ID、用户 ID、任务 ID 等内部编号必须唯一。
2. 同一家酒店的房号必须唯一。
3. 关联字段应指向真实存在的记录，避免出现“不存在的房间却有入住记录”。
4. 关键入住操作必须防止同一房间产生冲突的有效入住。
5. 退房业务和清洁任务创建必须保证一致性，不能只完成其中一个。
6. 删除历史业务数据应十分谨慎。通常应保留业务历史，并用状态/归档标识处理，而不是随意硬删除。
7. 对关键状态和字段使用必要的非空、唯一、外键及检查约束。
8. 需要考虑数据库备份、恢复、权限和敏感信息保护。
9. 房间、入住的当前占用分别由部分唯一索引保护；来源段唯一、同房间未完成任务唯一也由数据库保护。
10. 使用外键和必要检查约束验证状态、来源空值、时间先后；跨表关联含义由业务事务检查。不能只靠数据库唯一约束代替业务规则。
11. 入住、退房、换房、任务和异常操作使用统一锁定顺序：需要时先锁入住记录，再按房间 ID 升序锁相关房间，再处理关联记录。异常上报/解决和清洁操作也锁相关房间，保证与分房互斥。
12. 换房在一个事务内完成关闭原段、创建新段、写换房历史、原房标脏、创建清洁任务和审计；退房与换房针对同一次入住不能同时成功。
13. 并发分房使用房间行锁、任务状态/版本和执行人校验，只能一个批次成功；任务、清洁状态和任务日志同事务变化。接管需验证当前执行人未变，并与完成串行处理；若任务已完成则拒绝接管，若先接管则旧执行人完成请求被拒绝。
14. 重复请求与业务状态检查共同防重。入住使用独立 idempotency_requests 表，保存请求标识、带密钥内容摘要和成功入住 ID；同内容 24 小时内返回原结果，过期旧标识拒绝且不自动清理，失败随业务回滚。已获第 3 阶段确认，详见 CHECK-IN.md；不使用普通审计日志代替。

【注释】行锁相当于让冲突操作排队；事务保证不成功一半；唯一约束防止重复数据。三者用途不同，需要配合。

## 7. 备份与环境

- 开发环境：使用虚构测试数据。
- 测试环境：用于验证功能和升级，不直接影响真实酒店业务。
- 生产环境：真实营业时使用的数据环境，需要更严格的权限、备份与监控。

数据库备份应保存到独立的位置，并定期实际执行恢复测试。备份任务提示“成功”不等于恢复一定可用。

## 8. 已确定规则与待确认边界

清洁不验收、仅主管/总经理手动或批量分房、受控接管、换房可选关联真实原房异常、固定异常类型规则、房间使用分段、异常解决权限和收费分期均已确定，见 PRD，不再反复确认。第一版不按预计退房时间自动创建任务或自动调整优先级。

2026-10-20 已确认单酒店、房号全局唯一、无楼栋、非零整数楼层（地下层为负数）；无使用历史可改房号，已有历史禁止直接改号。首个管理员采用一次性终端初始化。第 3 阶段又确认全部实际住客、证件与电话加密及入住幂等策略，见 CHECK-IN.md；真实试用的数据保留期限、身份核验等仍按 PRD 第 9 节暂停确认。房间实际迁移与视图规则见 ROOMS.md，房型和房间均有防覆盖版本号。同住人通过独立关联表登记，不复制入住记录占用同一房间。

阶段 3、4 可使用仅限开发/测试环境的虚构夹具构造清洁合格及异常房间，用于验证查询和入住规则。夹具不属于生产初始化业务，不得开放直接改房态的业务接口；清洁、异常功能完成后必须改用正常页面操作复验完整流程。

第二阶段再确认房价、计价单位、押金、优惠、分次收款、退款和账单明细。第一版保留稳定入住 ID 和完整房间使用历史即可，不提前创建收费表或约定房费算法。

## 9. 常见字段类型解释

- `BIGINT`：较大的整数，常用作内部编号。
- `VARCHAR(n)`：有长度上限的文本，适合房号、用户名等。
- `TEXT`：较长文本，适合说明或备注。
- `INTEGER`：整数，例如楼层。
- `BOOLEAN`：真假值，例如账号是否启用。
- `DECIMAL(10,2)`：定点小数，适合金额。
- `TIMESTAMPTZ`：表示一个明确的时间点；数据库不替代保存酒店时区配置，页面按约定时区显示。
- **价格快照**：订单产生时保存的名称、单位和价格，用于确保历史金额不受价目表变化影响。

## 10. 第二阶段收费扩展参考（第一版不建表、不实现）

以下四类表仅保留概念草案，字段和流程在第二阶段重新核对。服务订单关联入住及发生时的房间使用记录；账单与实际收款分开保存。历史价格需要快照，不能从最新房型价格倒算。第二阶段功能不追溯猜测第一版期间未记录的费用。

### 10.1 `service_items`：额外服务项目目录（第二阶段）

建议字段：

| 字段 | 建议类型 | 用途 | 注释 |
|---|---|---|---|
| `id` | BIGINT | 项目内部编号 | 主键 |
| `service_code` | VARCHAR(50) | 项目编码 | 可选，但建议保持唯一，方便系统识别 |
| `service_name` | VARCHAR(100) | 项目名称 | 例如餐食、果盘、加床、加被 |
| `category` | VARCHAR(50) | 项目类别 | 例如餐饮、客房用品、其他服务 |
| `unit_name` | VARCHAR(20) | 计价单位 | 例如份、件、次、晚；需由酒店确认 |
| `unit_price` | DECIMAL(10,2) | 当前标准单价 | 管理员可以维护；已下单订单不跟随改价 |
| `enabled` | BOOLEAN | 是否可继续下单 | 停用项目不应出现在新订单的可选清单中 |
| `description` | TEXT | 项目说明 | 可选 |
| `created_at` / `updated_at` | TIMESTAMPTZ | 创建、修改时间 | 按项目统一的时间规则保存 |

【注释】这张表相当于酒店的“额外服务价目表”。例如管理员设置“果盘 38 元/份”。如果以后果盘涨价到 42 元，只影响新订单，不应回头修改已经下单的订单。

### 10.2 `stay_service_orders`：入住期间额外服务订单（第二阶段）

建议字段：

| 字段 | 建议类型 | 用途 |
|---|---|---|
| `id` | BIGINT | 服务订单内部编号 |
| `order_number` | VARCHAR(40) | 员工可读的订单编号，可设置唯一 |
| `stay_record_id` | BIGINT | 关联本次入住记录，不能只关联房间 |
| `stay_room_segment_id` | BIGINT | 关联服务发生时的房间使用记录 |
| `room_id` | BIGINT | 发生服务时的房间，便于查询 |
| `service_item_id` | BIGINT | 对应服务目录；若项目被停用，历史订单仍需保留快照 |
| `service_name_snapshot` | VARCHAR(100) | 下单时的服务名称快照 |
| `unit_name_snapshot` | VARCHAR(20) | 下单时的计价单位快照 |
| `unit_price_snapshot` | DECIMAL(10,2) | 下单时的单价快照 |
| `quantity` | DECIMAL(10,2) | 数量，例如 2 份；后端需校验允许的数量精度 |
| `total_amount` | DECIMAL(10,2) | 订单金额快照，通常为单价乘数量 |
| `status` | VARCHAR(30) | 例如 `REQUESTED`、`IN_PROGRESS`、`COMPLETED`、`CANCELLED` |
| `ordered_by` | BIGINT | 登记员工 ID |
| `ordered_at` | TIMESTAMPTZ | 登记时间 |
| `completed_at` | TIMESTAMPTZ | 实际提供服务的时间，可为空 |
| `notes` | TEXT | 备注，例如客人要求；避免保存不必要的敏感信息 |
| `cancelled_by` / `cancelled_at` | BIGINT / TIMESTAMPTZ | 取消人和取消时间（按实现需要） |

【注释】“加床按晚收费”与“加被按件收费”涉及不同计价方式。第二阶段开始前确认计价单位和数量规则，不能默认所有服务都按一次性收费。

### 10.3 `billing_records`：账单与结算汇总（第二阶段）

可以为每次入住建立一份账单记录，建议字段包括：`id`、`billing_number`、`stay_record_id`、`room_charge_amount`、`service_charge_amount`、`adjustment_amount`（若启用优惠/调整）、`total_amount`、`paid_amount`、`due_amount`、`status`、`created_at`、`settled_at`、`settled_by`。

【注释】房费的计价规则需要酒店确认。此表保存汇总结果，详细的额外服务消费仍应保留在 `stay_service_orders` 中或通过账单明细关联，不能只有一个无法解释来源的总金额。实际实现也可以增加 `billing_line_items` 账单明细表，将房费、每项服务费和调整项逐条记录。

### 10.4 `billing_payments`：收款记录（第二阶段）

建议字段：`id`、`billing_record_id`、`amount`、`payment_method`、`paid_at`、`received_by`、`reference_number`（可选）、`notes`。第二阶段可先人工登记收款结果，不代表接入了第三方支付。

【注释】应收与实收分开保存；押金、分次收款、退款及是否只允许全额结算等规则均在第二阶段确认，第一版不保存收款结果。


## 第 4 阶段实际数据库补充（当时快照，第 5 阶段扩展见 4.9/4.10）

新增 `V7__checkout_housekeeping.sql` 和 `V8__one_open_cleaning_task_per_room.sql`；V1—V6 保持原文件。V8 是 V7 已在隔离测试库执行后继续增加的约束，没有回写已执行迁移。

- `housekeeping_tasks`：`id`、`room_id`、`task_type`、`source`、`source_id`、`stay_record_id`、`room_segment_id`、`status`、`created_by`、`created_at`、`notes`。目前仅 `CHECKOUT_CLEANING / CHECKOUT / PENDING`；`source_id=stay_record_id`，来源业务指本次入住的退房，来源段另用 `room_segment_id` 明确关联。
- `UNIQUE(source,source_id)`：一次退房一个任务；`UNIQUE(room_segment_id)`：同一离房段不重复建任务；待处理任务的房间部分唯一索引：同房最多一个未完成任务。
- 三列复合外键确保任务的使用段、入住、房间是同一组业务关系；所有业务外键限制删除，不级联删除入住历史。
- 原 `idempotency_requests.operation` 允许 `CHECK_IN`、`CHECK_OUT`，沿用原主键、24 小时规则、内容摘要和 `result_stay_id`；不增加退房记录表，不保存完整请求或响应，不新建第二套幂等表。
- `room_operational_facts.has_open_cleaning_task` 改为查询真实 PENDING 任务；`occupied` 仍从未结束使用段推导。`rooms.cleaning_status` 仍是唯一清洁状态字段，任务只记录处理进度。
- 所有本阶段任务均未分配，属于客房部可读的未分配任务池，不含住客资料。第 5 阶段新增任务状态时需增量扩展状态约束、未完成索引和房态视图，保持三者一致。

详细设计和验收证据见 [CHECK-OUT.md](CHECK-OUT.md)、[PHASE4-DELIVERY.md](PHASE4-DELIVERY.md)。

## 第五阶段批量分房增量 V10

新增 housekeeping_assignment_batches（操作人、目标员工、服务器时间、必要备注）及 housekeeping_assignment_items（批次、房间、任务、房号快照、可选创建日志及必填分配日志）。条目通过复合外键验证房间/任务、日志/任务对应关系，批次内房间/任务唯一，分配日志唯一。原任务仍是唯一清洁业务模型。

idempotency_requests 新增 result_assignment_batch_id，与原 result_stay_id/result_task_log_id 按操作类型互斥；新增 HK_BATCH_ASSIGN。HK_CLAIM 仅为历史兼容保留，服务和 HTTP 均拒绝新领取。V1—V9 不修改，不清空或覆盖业务资料。批次字段不包含住客资料或完整请求正文。参见 HOUSEKEEPING.md 批量优化规则。
