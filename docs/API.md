# 酒店前台与客房协同管理系统｜接口说明草案

> V1.2 已实现住客受控更正，新增契约见本文末尾 V1.2 节；权限/脱敏/重试规则及最终本地验收见 [V1.2 交付记录](releases/V1.2-DELIVERY.md)。旧章节保留其原版本边界。

> **这份文档是做什么的？** 它约定前端页面如何向后端请求数据，以及后端会返回什么。可以把“接口/API”理解成网页和服务器之间的办事窗口：网页提交请求，服务器验证规则、处理业务，再把结果返回。
>
> **初学者提醒：** 本文同时保留实际接口和后续设计草案。已实现部分按下方“当前实现进度”及各节标注识别；未实现的后续扩展示例不能当作已经运行的程序。

> **版本边界：** 第 4—10 节及第 11 节的管理/历史接口属于 V1.0。第 12 节为第二阶段收费参考，V1.0/V1.1 不实现；退房不要求账单、支付信息或结算状态。V1.0 无续住接口；V1.1 增量见下节与 [交付记录](releases/V1.1-DELIVERY.md)，验收状态以交付记录为准。主管验收、住中清洁、预订及客资更正不在 V1.1 范围。

## V1.1 增量：续住与预计离店调整

仅 `stay:manage`（前台/总经理），使用既有 Session、CSRF 和统一 JSON。入住列表/详情新增 `version`；入住初始为 0，续住、换房、退房成功增加版本。原退房和换房请求仍兼容原字段；新增续住提交强制检查版本和当前使用段。

| 方法与路径 | 输入/输出 | 规则 |
| --- | --- | --- |
| `POST /api/stays/{id}/extensions` | `{segmentId,version,newExpectedCheckOutAt,reason}` + UUID `Idempotency-Key` | 新时间带时区且晚于当前/入住时间；原因 1～1000 字；仅在住；允许延长和缩短 |
| `GET /api/stays/{id}/extensions?page=1&pageSize=20` | `{items,total,page,pageSize}`，pageSize 1～100 | 按变更 ID 倒序；已退房仍可查询 |
| `GET /api/stays/extensions/result/{key}` | 当前操作人的原始成功回执 | 其他操作人即使有前台权限也查不到该 key；404 不代表原操作确定失败 |

回执字段：`id,stayRecordId,segmentId,roomId,roomNumber,oldExpectedCheckOutAt,newExpectedCheckOutAt,reason,operatorId,operatorName,operatedAt,stayVersion`。输入时间规范化为 UTC 微秒；界面明确北京时间。回执不包含住客姓名、证件或电话；员工姓名读取当前员工显示名。

`STALE_STAY_VERSION`/`STALE_ROOM_SEGMENT`/`STAY_ALREADY_CHECKED_OUT` 为 409；参数、无变化时间为 400。继续复用 24 小时幂等：相同 key/payload 返回原回执，即使之后又续住/退房；异 payload 和过期 key 拒绝。已过期 key 的历史回执仍可查询，查询不产生新操作。写操作重放仍复核账号、权限及会话安全版本。

成功仅更新预计离店时间和入住版本，追加事实与审计；不释放房间、不建入住/使用段/清洁任务、不涉及费用。`reason` 不应填写敏感住客资料。

> **当前实现进度：** 第 1 阶段完成认证及账号/角色；第 2 阶段完成房间与房态，见 ROOMS.md；第 3 阶段入住、当前与历史入住查询见 CHECK-IN.md 和第 6 节。第 4 阶段退房见 CHECK-OUT.md；第 5 阶段清洁处理及人员批量分房见 HOUSEKEEPING.md 和PHASE5-BATCH-ASSIGNMENT-DELIVERY.md（PHASE5-DELIVERY 为优化前快照）；第 6 阶段异常及维修停用已实现，见 ROOM-ISSUES.md；第7阶段正式换房见ROOM-CHANGES.md和第8节。各阶段交付文档是当时的验收快照。

## 1. 什么是接口？

例如，前台页面需要显示所有可入住的房间。页面不会直接读取数据库，而是向后端发送请求。后端查询数据库、判断哪些房间符合条件，然后将结果返回给页面。

流程通常是：

`浏览器页面 → HTTP 请求 → 后端接口 → 数据库处理 → 后端响应 → 页面显示结果`

【注释】前端负责界面与交互；后端负责可靠的业务规则、权限检查和数据读写。不要把关键业务规则只写在浏览器里。

## 2. 基本约定（建议）

- 接口前缀：`/api`。
- 数据格式：请求和响应通常使用 JSON。
- 传输方式：正式环境使用 HTTPS。
- 登录认证：按本阶段已授权的方案采用服务器 Session 与 Cookie，保留 CSRF 防护，空闲 30 分钟过期，退出立即失效；配置见 AUTHENTICATION.md。后端每次检查当前身份与权限，禁用、重置密码或角色变更后及时使旧会话失效或重新校验。
- 时间格式：带时区的 ISO 8601，数据库事件时间使用 `TIMESTAMPTZ`，页面默认按 `Asia/Shanghai` 显示。实际入住、退房、换房、清洁操作和异常时间由服务器生成；预计退房时间由前台填写。第一版不支持历史补录，关键写请求不接受客户端指定实际操作时间。
- 分页列表：建议统一使用 `page`、`pageSize` 等参数。
- 错误响应：返回可识别的错误代码和适合员工理解的提示；不要把数据库密码、堆栈或内部敏感信息直接返回给浏览器。
- HTTP 状态：未登录或凭证失效用 401，无权限用 403，输入错误用 400，记录不存在用 404，房间/任务状态冲突用 409，未预期错误用 500；同时返回统一 JSON 错误，不把所有失败都作为 HTTP 200。
- 重复提交：入住、退房、换房、任务创建/开始/完成/接管、异常上报/解决等关键写请求使用 `Idempotency-Key` 标识同一次操作；网络重试沿用原标识，新操作生成新标识。入住和退房已确认并共用实现：PostgreSQL 保存操作人/操作/标识、带密钥内容摘要及成功入住 ID；同内容 24 小时内返回原结果，不同内容拒绝，过期旧标识拒绝且暂不清理；失败全事务回滚允许重试。其他业务在其开发阶段核对，详见 CHECK-IN.md、CHECK-OUT.md。
- 幂等标识不代替状态检查、事务和数据库唯一约束；即使提交不同标识，同一退房也不能再生成任务。操作人来自登录身份，不接受客户端指定经办人 ID。

示例响应格式（只是建议）：

```json
{
  "success": true,
  "message": "操作成功",
  "data": {}
}
```

失败时可以使用：

```json
{
  "success": false,
  "code": "ROOM_NOT_AVAILABLE",
  "message": "该房间当前不可入住，请重新选择。",
  "data": null
}
```

【注释】团队也可以选择其他规范格式。关键是同一个项目中要统一，前端才能稳定处理成功与失败。

## 3. 角色权限说明

建议定义以下业务角色：
- `ADMIN`：系统管理员，负责账号、固定角色分配、房型和房间基础资料维护。
- `FRONT_DESK`：前台员工，负责入住、退房、换房和必要入住历史查询。
- `HOUSEKEEPING_STAFF`：客房服务员，负责处理已分配给自己的清洁任务和上报异常。
- `HOUSEKEEPING_MANAGER`：客房主管，负责查看全部任务、分配待处理任务、受控接管清洁中任务、人工创建任务和处理异常；可执行自己的清洁任务，无验收流程。
- `HOTEL_MANAGER`：酒店总经理，具备前台与客房业务权限，包括主管的任务管理和异常解决权限。

重要边界：`HOTEL_MANAGER` 不等同于 `ADMIN`，默认不能管理员工、分配角色、维护房型/房间基础资料或修改系统安全设置；`ADMIN` 默认也不办理入住等业务，需要时追加相应角色。第一版不开发自定义角色或可视化权限编辑器。第二阶段服务价目维护另行授权，不由总经理角色自动开放。

第一版支持一人多个固定角色，具体权限矩阵见 PRD 第 2.6 节。后端检查功能权限和具体记录范围；知道任务 ID 并不代表有权处理该任务。前台和客房部房间查询不应暴露超出岗位需要的个人信息。

## 4. 登录与账号接口

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| POST | `/api/auth/login` | 登录 | 未登录用户 |
| POST | `/api/auth/logout` | 退出登录 | 已登录用户 |
| GET | `/api/auth/me` | 获取当前用户、角色和具体权限 | 已登录用户 |
| GET | `/api/auth/status` | 验证登录状态，返回与 me 相同的用户信息 | 已登录用户 |
| GET | `/api/auth/csrf` | 获取当前会话对应的 CSRF 请求头名称和校验值 | 所有人 |

**第 1 阶段实际约定：** `GET /api/health` 返回服务状态。写请求前先 GET `/api/auth/csrf`，读取 `data.headerName`、`data.token` 并放进请求头，浏览器须保留 Cookie。登录和退出也受 CSRF 保护，不使用 Bearer token。响应包含统一的 `success`、`code`、`message`、`data`；登录成功的 data 以及 me/status 的 data 都包含 `id`、`username`、`displayName`、`enabled`、`roles` 和 `permissions`，不含密码哈希。

登录失败（不存在、错误密码、禁用、临时锁定）统一 401 + `LOGIN_FAILED`；未登录访问受保护接口 401 + `UNAUTHORIZED`；越权 403 + `FORBIDDEN`；CSRF 缺失或不匹配 403 + `CSRF_INVALID`；来源 IP 尝试过多 429 + `TOO_MANY_REQUESTS`，含 Retry-After。会话默认空闲 30 分钟过期，退出后原 Cookie 失效。

权限验收专用的基础工作区接口：GET `/api/workspaces/admin`、`/hotel-manager`、`/front-desk`、`/housekeeping-manager`、`/housekeeping-staff`（后四项同属 `/api/workspaces` 前缀）。只返回工作区名称和说明，不办理酒店业务；各接口按具体权限拦截，规则见 AUTHENTICATION 和交付记录。

登录请求示例：

```json
{
  "username": "frontdesk01",
  "password": "示例密码，仅用于演示"
}
```

【安全注释】真实密码不应写入代码仓库、日志或文档。系统只保存安全处理后的密码哈希，不保存明文密码。生产环境应限制登录尝试并使用 HTTPS。

## 5. 房间接口

**第 2 阶段已实现规范：** 以 [ROOMS.md](ROOMS.md) 的“已实现接口说明”为准，包含资料字段、版本防覆盖、房态图/统计/楼层接口、筛选及错误码。下表为原接口概览，入住、退房和清洁按下方实际章节及专题文档执行，换房仍为设计参考，异常按第 10 节实现。

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| GET | `/api/rooms` | 查询房间列表 | 全部五类角色 |
| GET | `/api/rooms/{roomId}` | 查看房间必要详情 | 全部五类角色，按岗位裁剪 |
| POST | `/api/rooms` | 新增房间 | 管理员 |
| PUT | `/api/rooms/{roomId}` | 修改房间基础资料 | 管理员 |
| POST | `/api/rooms/{roomId}/set-enabled` | 启用或受控停用档案 | 管理员 |
| GET | `/api/room-types` | 查询房型 | 全部五类角色 |
| POST | `/api/room-types` | 新增房型 | 管理员 |
| PUT | `/api/room-types/{typeId}` | 修改房型基础资料 | 管理员 |
| POST | `/api/room-types/{typeId}/set-enabled` | 启用或停用房型 | 管理员 |

列表查询示例：

`GET /api/rooms?floor=8&roomTypeId=1&availability=VACANT_CLEAN&page=1&pageSize=20`

这里的参数表示：查询 8 楼、指定房型、符合空闲可入住条件的房间，并返回第一页。

示例响应：

```json
{
  "success": true,
  "message": "查询成功",
  "data": {
    "items": [
      {
        "id": 10001,
        "roomNumber": "801",
        "floor": 8,
        "roomTypeName": "豪华大床房",
        "availability": "VACANT_CLEAN",
        "enabled": true,
        "occupied": false,
        "cleaningStatus": "CLEAN",
        "hasOpenCleaningTask": false,
        "hasBlockingIssue": false,
        "canCheckIn": true
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

【注释】`10001` 是内部 roomId，`801` 是房号。`availability` 是后端计算的主要展示状态，不是允许前端任意修改的数据库字段；各维度和 `canCheckIn` 也由后端产生。主要状态优先级为 `DISABLED → OCCUPIED → OUT_OF_SERVICE → CLEANING → INSPECTION → DIRTY → UNAVAILABLE → VACANT_CLEAN`，对应 PRD 第 5.1 节，同时保留异常等标记。若数据条件不一致，按不可入住处理，不能显示 `canCheckIn=true`。待检查只支持数据、查询、展示及统计，不提供检查流程。

筛选支持 `roomNumber`、`floor`、`roomTypeId`、`availability`。房间资料修改接口不接受占用、清洁或异常解除字段；启用状态仅通过受控接口修改，存在当前占用或未完成清洁任务时禁止停用档案。新建房间默认待清洁，先完成人工清洁任务再分房。停用房型仅禁止新增或改绑房间选择该房型，不自动停用已引用房间或结束入住，不删除历史。

## 6. 入住接口

**第 3 阶段实际接口：** 以 [CHECK-IN.md](CHECK-IN.md) 为准。本节替换旧的单住客草案，不实现退房或换房接口。

| 方法 | 路径 | 用途 | 允许角色 |
|---|---|---|---|
| POST | `/api/stays` | 办理入住，必须带 Idempotency-Key | 前台、酒店总经理 |
| GET | `/api/stays/available-rooms` | 可入住房间分页，复用房间筛选且强制 canCheckIn=true | 前台、酒店总经理 |
| GET | `/api/stays` | 入住分页，默认 IN_HOUSE，可传状态和筛选 | 前台、酒店总经理 |
| GET | `/api/stays/history` | 已结束入住分页 | 前台、酒店总经理 |
| GET | `/api/stays/{stayId}` | 查看入住必要详情 | 前台、酒店总经理 |

入住请求示例（使用虚构信息）：

```json
{
  "guests": [{
    "name": "虚构住客甲",
    "documentType": "OTHER",
    "documentNumber": "FICTIONAL-ONLY-001",
    "phone": "",
    "notes": "开发虚构资料",
    "primary": true
  }],
  "roomId": 10001,
  "expectedCheckOutAt": "2026-11-20T12:00:00+08:00",
  "notes": "演示数据"
}
```

后端至少应检查：
1. 当前用户是否有办理入住权限。
2. 必填信息是否完整。
3. 房间是否存在且启用。
4. 房间当前是否真的可入住。
5. 是否会与现有有效入住或其他业务安排冲突。
6. 是否因重复提交而重复创建记录。

成功后 data 返回 `id`、`segmentId`、房间摘要、服务器 `checkInAt` 和脱敏住客详情；失败时返回明确原因。创建全部住客、入住、第一段使用记录、防重结果和成功审计同事务保存。预计退房时间到达不自动结束入住，不传入或计算收费字段。完整字段、查询参数及错误码见 CHECK-IN.md。

## 7. 退房接口（第 4 阶段已实现）

`POST /api/stays/{stayId}/check-out`，权限 `stay:manage`（前台、酒店总经理）。请求头 `Idempotency-Key` 为 UUID；Session 和 CSRF 规则不变。

```json
{ "segmentId": 20001 }
```

本阶段使用 `segmentId` 与已有入住详情返回字段对齐，替代早期草案的 `fromSegmentId`；不接收实际退房时间、经办人或收费信息，不实现额外退房备注功能。

成功 `data` 包含 `stayId`、`segmentId`、`roomId`、`roomNumber`、`roomTypeName`、`actualCheckOutAt`、`checkedOutBy`、`taskId`、`cleaningStatusAtCheckout=DIRTY`、`taskStatusAtCreation=PENDING`。后两个字段表示退房当时的结果，实时状态应查询房态/任务接口。

结束入住、关闭当前段、房间标脏、创建来源为 `CHECKOUT` 的 `CHECKOUT_CLEANING` 任务、成功审计、幂等结果必须同事务成功或回滚。房间使用段的 `end_reason` 沿用 `CHECK_OUT`，不要与任务来源的 `CHECKOUT` 混淆。

同操作人、同操作类型、同标识、同内容在 24 小时内返回既有结果；不同内容拒绝；过期旧标识拒绝且保留。更换标识再次退房返回 `STAY_ALREADY_CHECKED_OUT`；过期使用段返回 `STALE_ROOM_SEGMENT`。失败不保留成功结果，可修复后用原标识重试。完整流程及错误码见 [CHECK-OUT.md](CHECK-OUT.md)。

## 8. 正式换房接口（第7阶段已实现）

实际参数、返回值、事务和重试见 [ROOM-CHANGES.md](ROOM-CHANGES.md)。仅前台与总经理，沿用 stay:manage、Session/CSRF；POST带Idempotency-Key。

- GET `/api/stays/{id}/room-change/available-rooms`：原有效入住的目标筛选，复用统一可入住性。
- POST `/api/stays/{id}/room-changes`：`{segmentId,targetRoomId,reasonType,reason,issueId?}`。
- GET `/api/stays/{id}/room-changes`：同次入住完整换房历史。
- GET `/api/stays/room-changes/result/{key}`：只查本人成功回执，不重执行业务。
- 原 GET `/api/stays/{id}` 新增 `roomSegments`、`roomChanges`，当前房显示最新实际使用段。

reasonType 为 GUEST_REQUEST/ROOM_ISSUE/OTHER，说明必填；ROOM_ISSUE 的 issueId 选填，提供时验证属于原房，不要求严重或未解决。最新第7阶段指令取代 NORMAL/BLOCKING_ISSUE 及强制关联旧草案。换房不修改异常、维修或住客名单，不新增入住和收费。

同事务关闭原段、建立新段、写换房事实、原房DIRTY、创建唯一ROOM_CHANGE清洁任务/历史、审计及幂等结果。原房异常停用保留，目标必须真正可入住。服务器统一时间，旧段校验，入住→房间ID升序→使用段的锁顺序；同内容原标识24小时内重放原结果，即使原段已经关闭；不同内容或过期旧标识拒绝。

## 9. 清洁任务接口（第 5 阶段已实现）

实际接口、字段及权限以 [HOUSEKEEPING.md](HOUSEKEEPING.md) 为准。第五阶段新增人工建单、人员批量分房、分配、带原因重新分配/接管、开始、完成、历史、员工候选及范围统计，正常路径为 `PENDING → IN_PROGRESS → COMPLETED`，没有主管验收。人员分房与六个新增批次查询/写入接口见 HOUSEKEEPING.md 的批量优化节；仅客房主管/总经理可分配，ADMIN 不增加业务权限。

- GET `/api/housekeeping-tasks`、`/{id}`、`/{id}/history`、`/statistics`。
- 主管候选/筛选选项：GET `/api/housekeeping-tasks/employees`、`/assignees`。
- POST `/api/housekeeping-tasks/manual`；POST `/api/housekeeping-tasks/{id}/assign`、`/reassign`、`/start`、`/complete`。旧 `/{id}/claim` 已关闭，所有登录角色均拒绝。
- 人工建单 `{roomId, reason}`；任务动作 `{version, assigneeId?, reason?}`，全部写入复用 UUID `Idempotency-Key` 和原 24 小时机制。
- 列表支持房号、楼层、三种状态、`scope=ALL/MINE/UNASSIGNED`、负责人和创建时间排序。服务员始终只能自己的任务，不能通过查询参数、详情和历史 ID 越权。
- 首次分配只接受未分配待处理任务；`reassign` 用于已分配待处理改派及清洁中受控接管，原因必填，新执行人不同且合法。版本号校验替代旧草案中的 `expectedAssignedTo`，同时保护状态与归属。
- 所有角色自主领取已关闭；创建人工任务要求当前已经 DIRTY、无人占用、无未完成任务，不将其他房态任意改脏。开始设 CLEANING，完成设 CLEAN，均不改变维修/启用/占用事实。

原规划 `/logs`、`/takeover`、`/api/housekeeping-assignees`、根路径 POST 和合并领取开始不作为实际接口。写入返回历史回执，查询当前状态使用详情；具体错误、事务及安全重试说明见 HOUSEKEEPING.md。

## 10. 房间异常与维修停用接口（第 6 阶段已实现）

实际接口、参数、状态、权限和重试方法见 [ROOM-ISSUES.md](ROOM-ISSUES.md)。支持上报、分页筛选、统计、详情、历史、开始/解决、受控调级、人工维修停用/恢复及本人操作结果查询。

类型 FACILITY/ELECTRICAL/PLUMBING/SANITATION/SAFETY/OTHER，程度 LOW/MEDIUM/CRITICAL。状态 OPEN/IN_PROGRESS/RESOLVED。未解决 CRITICAL 与有效人工停用共同决定维修阻止，不再接受早期草案 blocksCheckIn 字段控制房态。写请求使用 Session、CSRF、Idempotency-Key；主管处理提交 version，解除停用提交 stopId，防止旧页面覆盖。

只有客房主管和总经理可处理及管理停用。前台可查询全部异常，服务员仅自己提交，ADMIN 不拥有异常业务权限。没有删除异常或历史的接口。

## 11. 员工管理及操作记录接口（第一版）

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| GET | `/api/users` | 分页查询员工 | 管理员 |
| POST | `/api/users` | 新增员工账号 | 管理员 |
| POST | `/api/users/{userId}/set-enabled` | 启用或禁用账号 | 管理员 |
| POST | `/api/users/{userId}/reset-password` | 重置密码并使旧登录凭证失效 | 管理员 |
| GET | `/api/roles` | 查询五个固定角色 | 管理员 |
| PUT | `/api/users/{userId}/roles` | 分配一个或多个固定角色 | 管理员 |
| GET | `/api/audit-logs` | 按时间、操作人、动作及业务对象查询操作记录 | 管理员、主管、总经理，限定范围 |

账号、角色、房型和房间修改需审计；接口不得返回密码哈希或现有密码，不提供普通历史删除接口。管理员默认查询管理记录，主管查询客房业务记录，总经理查询前台及客房业务记录，不因可查询审计而开放所有个人信息。

第 1 阶段已实现上表中的账号与角色接口；审计查询接口留到历史查询阶段。新增账号提交 `{username,password,displayName,roles}`，roles 为非空角色编码数组；设置启用提交 `{enabled:true/false}`；重置密码提交 `{password}`；角色分配提交 `{roles:[...]}`。这些 JSON 表达字段结构，不包含默认密码。查询账号使用 page、pageSize，返回 items、total、page、pageSize；单页最多 100 条。角色列表返回五个编码。新增与密码重置遵守 AUTHENTICATION 中密码规范；至少保留一名启用管理员，不能禁用或移除最后一名启用管理员的 ADMIN 角色。密码和角色变更后旧会话失效，需重新登录。

## 12. 第二阶段收费接口参考（第一版不实现）

以下目录、订单、账单及收款接口仅保留扩展草案，第二阶段重新核对字段、权限及计费规则。第一版不建立这些接口、空控制器或前端入口。

### 12.1 额外服务项目与服务订单

第二阶段增加餐食、果盘、加床、加被等服务。需要区分服务价目表与某次入住的实际订单：管理员或单独授权人员维护价目表，员工为某次入住建立订单，总经理不默认拥有价目表维护权限。

#### 12.1.1 服务目录接口

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| GET | `/api/service-items` | 查询启用的额外服务项目和价格 | 前台、客房部、酒店总经理（按授权）和管理员 |
| POST | `/api/service-items` | 新增服务项目 | 管理员 |
| PUT | `/api/service-items/{itemId}` | 修改服务名称、单位、价格或说明 | 管理员 |
| POST | `/api/service-items/{itemId}/disable` | 停用服务项目，不影响历史订单 | 管理员 |

示例项目：果盘，单位“份”，单价 38.00 元；加被，单位“件”，单价 10.00 元。这里仅为演示金额，正式价格应由酒店管理员设置。

【注释】修改价目表只影响新订单。历史订单应保存下单时的价格快照，不能因为管理员今天改了价格，就改变客人昨天已经产生的消费。

#### 12.1.2 入住期间服务订单接口

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| POST | `/api/stays/{stayId}/service-orders` | 为当前入住登记一项额外服务 | 前台、客房部授权员工、酒店总经理 |
| GET | `/api/stays/{stayId}/service-orders` | 查询本次入住的服务清单 | 前台及授权角色 |
| GET | `/api/service-orders/{orderId}` | 查看服务订单详情 | 按权限控制 |
| POST | `/api/service-orders/{orderId}/complete` | 确认服务已提供 | 有权限的员工 |
| POST | `/api/service-orders/{orderId}/cancel` | 取消服务订单并说明原因 | 按酒店规则授权 |

新增订单请求示例：

```json
{
  "serviceItemId": 3,
  "quantity": 2,
  "notes": "果盘送至房间"
}
```

后端应根据服务目录读取名称、计价单位和当前单价并计算金额，不能信任客户端单价或总金额。订单关联当前有效入住和发生时的房间使用记录，并保存价格快照；换房后不能随当前房间改写历史服务归属。按晚等计价规则在第二阶段确认。

默认只有已提供且应收费的服务才能计入最终应收账单。取消或尚未提供的订单不应自动计费；如果实际业务存在其他规则，应在 PRD 中明确。

### 12.2 账单与退房结算接口

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| GET | `/api/stays/{stayId}/bill` | 查询本次入住的房费与额外服务费明细 | 前台及授权角色 |
| POST | `/api/stays/{stayId}/bill/preview` | 预览当前结算金额，不正式完成结算 | 前台、酒店总经理 |
| POST | `/api/stays/{stayId}/settlement` | 确认结算并记录收款 | 前台、酒店总经理或其他经授权人员 |
| GET | `/api/bills/{billId}` | 查看账单和结算结果 | 按权限控制 |

第二阶段结算明细展示房费、应收费服务明细、应收、已收和待收；可先记录人工收款，不默认接入真实支付平台。退房与结算的关系、房价、押金、退款等在第二阶段明确，不能反向变成第一版退房的前置条件。

示例结算请求（示意，字段需要按最终业务规则确定）：

```json
{
  "paymentMethod": "CASH",
  "paidAmount": 418.00,
  "notes": "前台人工登记收款"
}
```

后端重新计算金额。第二阶段若只支持全额结算，则核对实收与应收；历史账单改动需有权限的调整/冲正记录，不能直接覆盖金额。

【注释】“预览账单”是先看清楚费用，“确认结算”才是正式记账。房费计算（按晚、按小时、跨日规则）、押金、优惠、部分收款和退款等尚未确认的规则，应先向真实酒店核实。

### 12.3 第二阶段错误代码参考

| 错误代码 | 含义 | 页面建议提示 |
|---|---|---|
| `SERVICE_ITEM_DISABLED` | 服务项目已停用 | 该服务目前不可下单，请刷新列表 |
| `SERVICE_ORDER_NOT_COMPLETABLE` | 服务订单不能完成 | 请核对服务订单状态 |
| `BILL_ALREADY_SETTLED` | 账单已经结算 | 账单已结算，如需更正请走调整流程 |
| `BILL_AMOUNT_MISMATCH` | 收款金额与结算规则不符 | 请重新核对账单金额 |
| `STAY_NOT_ACTIVE` | 本次入住已结束 | 已退房的入住记录不能新增普通服务订单 |

## 13. 第一版常见错误代码

| 错误代码 | 含义 | 页面建议提示 |
|---|---|---|
| `UNAUTHORIZED` | 未登录或登录已失效 | 请重新登录 |
| `FORBIDDEN` | 当前用户无权限 | 你没有权限执行此操作 |
| `VALIDATION_ERROR` | 输入数据不符合要求 | 请检查必填信息 |
| `ROOM_NOT_FOUND` | 房间不存在 | 未找到该房间 |
| `ROOM_NOT_AVAILABLE` | 房间当前不可入住 | 请重新选择房间 |
| `STAY_ALREADY_CHECKED_OUT` | 入住记录已经退房 | 该记录已办理退房 |
| `STAY_ROOM_CHANGED` | 请求中的原房间使用记录已不是当前段 | 房间已变更，请刷新后再操作 |
| `ROOM_CHANGE_CONFLICT` | 入住结束、目标等于原房、来源冲突任务或异常不匹配 | 核对入住及关联异常后刷新重试 |
| `ISSUE_TYPE_INVALID` | 异常类型不在白名单内 | 请选择有效的异常类型 |
| `TASK_ALREADY_EXISTS` | 已有对应来源或同房间的未完成任务 | 请查看已有清洁任务 |
| `TASK_ALREADY_ASSIGNED` | 未分配任务已被其他主管分配 | 任务已分配，请刷新 |
| `TASK_STATE_INVALID` | 当前任务状态不允许此操作 | 请刷新任务状态后重试 |
| `TASK_ASSIGNEE_CHANGED` | 接管时预期执行人已变化 | 执行人已变更，请刷新后核对 |
| `DUPLICATE_REQUEST` | 同一请求仍在处理中 | 请稍后查询结果，重试沿用原请求标识 |
| `IDEMPOTENCY_KEY_REUSED` | 同一请求标识被不同内容复用 | 请求标识与原内容不一致，请重新确认操作 |
| `INTERNAL_ERROR` | 未预期的系统错误 | 操作失败，请稍后重试或联系管理员 |

## 14. 接口安全与可靠性注意事项

1. 所有关键接口都在后端进行身份与权限校验。
2. 入住、退房、换房和清洁状态变化需要后端验证合法状态流转。
3. 关键写操作需要事务和并发控制。
4. 对重复提交有风险的操作设计幂等处理。
5. 不返回密码、内部堆栈或不必要的个人信息。
6. 正式环境使用 HTTPS，妥善保护登录凭证。
7. 记录必要的业务审计信息，但避免日志泄露身份证号码等敏感数据。
8. 第一版操作成功后刷新，房态页定时查询且可手动刷新，不要求 WebSocket；最终是否可入住由提交时后端判断。
9. 关键业务成功审计与业务同事务；失败和拒绝记录独立保存，不能随回滚丢失。所有接口使用统一房态规则及数据库锁定顺序，见 DATABASE 第 6 节。

## 15. 第一版接口开发顺序（建议）

1. 登录和获取当前用户。
2. 员工账号和固定角色分配，审计基础能力。
3. 房型和房间查询、管理员维护及统一可入住判断。
4. 办理入住与查询当前入住。
5. 办理退房并自动创建清洁任务。
6. 清洁任务列表、分配、人员批量分房和开始、受控接管、完成、人工创建和历史。
7. 办理换房和查询换房历史。
8. 异常上报、解决和操作历史查询。
9. 完整业务、权限、重复提交、并发和事务回滚验收。收费接口留至第二阶段。

【注释】接口应当与具体页面一起逐步完成和测试。不要只创建许多空接口就认为功能已经开发完成。

开发顺序的测试依赖：房间/入住阶段可用仅限开发与测试环境的虚构夹具准备清洁合格、待清洁和故障房。夹具是预先准备的数据，不是业务接口；不能为早期验收增加任意改房态或关闭异常的后门。清洁与异常功能完成后，必须用正常页面创建任务、完成清洁、上报和解决异常，再复验入住条件。其他未决事项按 PRD 第 9 节遇到即暂停等待用户反馈，不由此处的草案自动确认。

## 第8阶段实际新增：集中操作审计（只读）

继续使用原Session、CSRF和统一响应格式，不新增日志写入、删除或批量导出接口。

| 接口 | 作用 |
|---|---|
| GET /api/audit-logs | 按当前岗位范围查询真实审计，数据库分页 |
| GET /api/audit-logs/options | 返回当前账号可筛选的模块、动作及确实存在相应审计的操作人员 |
| GET /api/audit-logs/{id} | 查询授权范围内单条事实，不在范围内返回404 |

筛选：`page`默认1、`pageSize`默认20且最多100；`from`和`to`采用带时区的ISO时间，范围为 `[from,to)`，默认最近30天，每次最多366天；可选`operatorId`、`module`、`action`、`result`。结果按发生时间降序、ID降序排列。同一只读事务返回列表与总数。未知/越权模块或操作不能扩大后端范围。

- ADMIN：SECURITY（登录、安全拒绝和通用请求失败）、ACCOUNT（账号管理）、CONFIGURATION（房间/房型配置）。
- HOTEL_MANAGER：STAY、HOUSEKEEPING、ISSUE、MAINTENANCE、CONFIGURATION，不自动查看账号安全审计。
- HOUSEKEEPING_MANAGER：HOUSEKEEPING、ISSUE、MAINTENANCE，通过`audit:housekeeping`授权。
- FRONT_DESK、HOUSEKEEPING_STAFF：没有集中查询权限，保留原业务历史范围。
- 多角色按授权范围合并，客户端不能传入角色或权限范围覆盖服务端判断。

只返回事件ID、操作人员ID/当前姓名及账号、动作、业务对象类型/ID、结果和服务器时间，不返回正文、住客资料、凭据或密钥。旧审计没有记下的字段不补造；人员姓名是当前资料，不是历史姓名快照。自动清洁建单记录见原任务历史；批量分房按每条HK_ASSIGN事实展示，不伪造历史批次审计。历史HK_CLAIM允许在授权范围只读显示，所有角色自主领取接口仍拒绝。

数据库暂不可用/查询超时可返回503 `SERVICE_UNAVAILABLE`。失败审计本身也不可写时仅输出不含SQL及请求内容的固定运维提示；业务成功所需的审计仍必须随事务成功，否则回滚。客户端保留不确定操作的原请求标识，不能把503误认为操作一定没有执行。
## 验收后安全与运营接口补充

此节记录第八阶段验收后的增量完善，不改变原有业务幂等、五角色或V12数据结构。

| 方法与路径 | 用途 | 权限 |
|---|---|---|
| GET /api/health/ready | 实际数据库就绪检查；成功200、不可用503，无连接详情 | 无需登录，仅读状态 |
| GET /api/operations/summary | 当前岗位的运营待办、数量和提醒阈值 | 前台/总经理/客房主管/服务员，后端限制数据范围；ADMIN默认403 |
| GET /api/users?search=&page=1&pageSize=20 | 原账号列表新增账号/姓名搜索，search最多100字 | system:manage |

运营汇总：`generatedAt`、`overdueStays`、`unassignedTasks`、`delayedTasks`、`strandedTasks`、`criticalIssues`、`maintenanceStops`、`pendingWarningMinutes`、`cleaningWarningMinutes`、`items`。不授权的计数返回null，列表不返回住客姓名、证件或电话。

前台获得在住超预计时间和授权异常信息；客房主管获得全部客房任务及异常信息；总经理合并；普通服务员只获得本人清洁任务和本人上报的严重异常，不开放未分配任务池。人工维修停用数量属于已有房态查询范围。每类提醒明细最多20项，计数为完整统计，不允许客户端扩展查询范围。

每个HTTP响应带服务器生成的 `X-Request-ID`，客户端传入的同名头不作为日志编号。5xx页面附该编号便于查脱敏日志；不把请求正文、原始异常、SQL或凭据返回客户端。

退出仍需POST和有效CSRF。数据库不可用时CSRF与会话退出可继续，退出审计失败不阻断会话撤销；业务成功审计仍保持失败回滚。入住/退房新增事务内操作人共享锁及启用、角色、会话版本复查，与既有换房/清洁/异常锁顺序一致。

## V1.2 住客资料受控更正

仅 `stay:guest-correct`（前台/总经理）；入住详情的住客含 `version`。仅在住登记笔误；不替换住客、不增减同住人、不改变主要联系人。

- `POST /api/stays/{stayId}/guests/{guestId}/corrections`，UUID `Idempotency-Key`；请求 `{version, changes, reason}`。
- `changes` 只允许 `name/documentType/documentNumber/phone`，每项必须字符串；未提交字段保留原值，`phone:""` 明确清空，`null` 拒绝。证件号码规范化为大写，遵循入住同一套格式与同次入住重复校验。掩码不能作为号码提交。无变化拒绝，原因 1—1000 字且不填写敏感号码。
- `GET /api/stays/{stayId}/guests/{guestId}/corrections?page=1&pageSize=20`，每页 1—100，历史入住仍可查。返回 `{items,total,page,pageSize}`。
- `GET /api/stays/guest-corrections/result/{key}` 仅当前员工自己的回执；404 不证明原操作失败，过期成功结果仍可查询。
- 回执：`id,stayRecordId,guestId,guestVersion,operatorId,operatorName,operatedAt,reason,changedFields,before,after`。`before/after` 仅脱敏四字段；姓名同样掩码，证件类型保留枚举。操作人名称为当前员工显示名。无完整号码或密文读取接口。
- 写事务先预留幂等，再复核真实操作人，锁入住和按 ID 排序的住客快照；与退房/换房串行。版本不匹配 409 `STALE_GUEST_VERSION`，已退房 409，错误入住/住客 404；参数无效 400。
- 重放返回原始脱敏回执；24 小时过期 key 不自动复用。业务事实、当前投影、审计 `GUEST_CORRECT` 和回执指针共同提交/回滚。
- 浏览器只持久化 UUID。关闭时清空敏感输入及内存 payload；重开/刷新查询回执，绝不自动用新 key 重发。

## V1.3 补登记：非财务交班

V1.3 当时漏登记本文件，这里补记接口边界；完整规则与验收见 [V1.3 交付记录](releases/V1.3-DELIVERY.md)。权限 `handover:front-desk`（前台/总经理）、`handover:housekeeping`（客房主管/总经理），使用既有 Session、CSRF、幂等与审计。

- `GET/POST /api/handovers`、`GET /api/handovers/result/{key}?operation=`、`GET /api/handovers/candidates?scope=`、`GET/PUT /api/handovers/{id}`、`POST /api/handovers/{id}/submit|reassign|acknowledge`。
- 状态机 DRAFT→SUBMITTED→ACKNOWLEDGED，数据库触发器冻结；事项只存 category/content/sourceId，不含住客证件或电话。

## V2.0 账务基础接口

按开发手册第 11 节实现；权限 `billing:read|charge|receive`（前台/总经理）、`billing:adjust|refund|configure`（仅总经理）；客房岗位与系统管理员无账务权限。金额一律十进制字符串（两位小数），单币种 CNY；`净应收 = 费用 − 有效贷项`，`净实收 = 收款 − 退款`，余额正数未付、负数预收剩余。所有写操作带 UUID `Idempotency-Key`，24 小时内同键同内容返回原回执；全部写路径有权限、版本乐观锁、审计与幂等。

### 价目与政策维护（billing:configure）

| 方法与路径 | 输入/输出 | 规则 |
| --- | --- | --- |
| `GET /api/billing/policies` | `{active,history}`；无政策时 active 为 null | history 只含已归档版本 |
| `POST /api/billing/policies` | `{billingCutoffTime,checkoutDeadline?,earlyCheckinMode,lateCheckoutMode,earlyCheckoutMode,roomChangePriceMode,extensionPriceMode,notes?}` | 发布新版本并自动归档旧版本；同时仅一个生效版本 |
| `GET/POST /api/billing/rate-plans`、`PUT /api/billing/rate-plans/{id}` | 房型、编码、名称、门市价、启用 | 同房型编码唯一；改价只影响之后入账 |
| `GET/POST /api/billing/rate-plans/{id}/rate-days` | `{stayDate,nightlyPrice}` 列表 | 特殊日价覆盖该日门市价，重复保存即覆盖 |
| `GET/POST /api/billing/service-catalog`、`PUT /api/billing/service-catalog/{id}` | 编码、名称、计价单位（按份/按次）、单价 | 编码全局唯一 |

### 客账与动作

| 方法与路径 | 规则 |
| --- | --- |
| `GET /api/billing/folios?status=&page=&pageSize=` | 客账分页列表（应收/实收/余额分项） |
| `GET /api/folios/{id}` | 客账详情：汇总、费用、收退款、事件；金额分项展示，不合成"收入" |
| `GET/POST /api/stays/{id}/folio` | 查询该入住客账；受控开账（需生效政策版本，一入住一账） |
| `POST /api/folios/{id}/charges` | `{category:ROOM_NIGHT\|MANUAL,...}`；房费按营业日覆盖的住房段核对方型与当日价，手工费用存名称/单价/数量快照；费用只增不改 |
| `POST /api/folios/{id}/adjustments` | `{originalChargeId,mode:CORRECTION\|REVERSAL,amount?,reason}`；更正形成 CREDIT 贷项，全额冲正形成 REVERSAL；累计不超过原费用 |
| `POST /api/folios/{id}/payments` | `{method,purpose:PAYMENT\|DEPOSIT,amount,methodReference?,note?}`；非现金同凭证号防重，现金不要求全局唯一单号 |
| `POST /api/folios/{id}/refunds` | `{paymentRecordId,amount,reason}`；不超过该笔收款未退部分与当前预收余额的较小者，客账行锁 + 数据库约束防并发超退 |
| `POST /api/folios/{id}/close`、`POST /api/folios/{id}/reopen` | 结账带版本与是否带余额结转确认；重开需原因（billing:adjust），仅 CLOSED 可重开 |
| `GET/POST /api/stays/{id}/service-orders`、`PUT /api/service-orders/{id}` | 消费请求登记/修改；按价目快照记名与价 |
| `POST /api/service-orders/{id}/fulfill`、`POST /api/service-orders/{id}/cancel` | 实际提供后才履约入账（唯一费用来源 SERVICE_ORDER）；取消不产生费用，履约/取消各一次性 |

回执与详情不含住客证件、电话；账务表不复制住客身份资料。普通收据明确标注为酒店内部凭证，不代表法定发票；系统不接入真实自动支付。`/api/folios/result/{key}` 查询本人幂等回执。

主要错误码：`BILLING_POLICY_REQUIRED`（无生效政策）、`FOLIO_REQUIRED`/`FOLIO_NOT_OPENED`/`FOLIO_STATE`（客账不存在或状态不符）、`RATE_PLAN_NOT_FOUND`（价目不存在或不适用，含他房型方案）、`RATE_PLAN_DISABLED`、`NO_ROOM_SEGMENT`（该营业日无覆盖住房段）、`CHARGE_ALREADY_POSTED`/`CHARGE_BEFORE_EFFECTIVE`/`ZERO_AMOUNT_CHARGE`/`CREDIT_EXCEEDS_CHARGE`/`NOTHING_TO_CREDIT`（费用与调整边界）、`REFUND_EXCEEDS_LIMIT`/`NOTHING_TO_REFUND`（退款额度）、`OUTSTANDING_BALANCE`（余额未确认）、`STALE_VERSION`（版本冲突）。一入住一账由 `folios.stay_record_id` 唯一约束保证（冲突返回 `FOLIO_ALREADY_EXISTS`）；非现金同凭证号防重由部分唯一索引保证（同客账、同方向、同方式、同参考号，现金除外，冲突返回 `PAYMENT_REFERENCE_EXISTS`）；同来源重复入账返回 `CHARGE_ALREADY_POSTED`，政策并发发布返回 `POLICY_ACTIVE_CONFLICT`。

## V2.1 收银交班、营业日与经营报表接口

按开发手册第 12 节实现；权限 `cashier:operate`（前台/总经理，班次查询/开启/提交、营业日查询）、`cashier:close`（仅总经理，班次确认、营业日关账）、`report:finance`（仅总经理，日报查询）；客房岗位与系统管理员无收银权限，看不到金额。金额一律十进制字符串（两位小数）；营业日按 Asia/Shanghai 自然日，资金归日按营业日固定政策的日切时间（凌晨 00:00 至日切前的收退款归前一营业日）。三个写操作全部带 UUID `Idempotency-Key`（24 小时同键同内容回放），班次提交/确认带版本乐观锁。

| 方法与路径 | 规则 |
| --- | --- |
| `GET /api/cashier-shifts?scope=&status=&page=&pageSize=` | 班次分页；`scope=all` 需 `cashier:close`，否则只看本人 |
| `POST /api/cashier-shifts` | `{openedFloat}`；同一收银员同时仅一个进行中班次（`SHIFT_ALREADY_OPEN`）；当日营业日行随开班惰性创建并固定生效政策 |
| `GET /api/cashier-shifts/{id}` | 班次详情（本人或 `cashier:close`）：金额分项 receipts/refunds/expectedCash |
| `POST /api/cashier-shifts/{id}/submit` | `{countedCash,differenceReason?,version}`；账面应存 = 备用金 + 实收现金 − 退款现金；差异非零必须填原因，差异只写入不可变实盘记录，**不回改任何已入账收款**；版本不符 `STALE_VERSION` |
| `POST /api/cashier-shifts/{id}/confirm` | 仅 `cashier:close`；SUBMITTED → CONFIRMED 一次性确认 |
| `GET /api/business-days?limit=` | 最近营业日列表（日切、状态、关账人、是否已有固化日报、是否当天） |
| `POST /api/business-days/{date}/close` | 仅 `cashier:close`；人工关账三段事务：锁营业日并插入 RUNNING 批次 → 校验房费（未开立客账/缺少房费/日价不符逐条具名）→ 汇总校对 → 固化日报快照 → 标记 CLOSED。失败留 FAILED 原因、营业日保持 OPEN 可重试；并发关账被每营业日唯一 RUNNING 索引拒绝（`DATA_CONFLICT`）；不因日切自动退房，关账后不重开 |
| `GET /api/reports/daily?from=&to=&page=&pageSize=` | 仅 `report:finance`；固化快照分页：charges（费用/冲减/净应收/房晚/房费收入）、payments（收/退/净收/现金收/现金退）、occupancy（已售房晚/在住/可售/出租率/ADR/数据不足标记）、laterAdjustments（关账后关联原营业日的当期调整）、notes（口径说明） |

分母口径：可售房 = 启用房 − 维修停售覆盖该日的房间；无可售房或无已售房晚时出租率/ADR 为 `null` 并在 `dataGaps` 标记"数据不足"，不做推算。原营业日关闭后不改原日报；当期调整记入当前营业日并按 `reversal_of` 关联原费用。

主要错误码：`SHIFT_ALREADY_OPEN`（已有进行中班次）、`SHIFT_NOT_FOUND`、`SHIFT_STATE`（状态不符）、`STALE_VERSION`（版本冲突）、`BILLING_POLICY_REQUIRED`（无生效政策）、`DAY_ALREADY_CLOSED`（重复关账）、`CLOSE_FAILED`（关账校验未通过，message 含逐条问题明细）、`DATA_CONFLICT`（并发关账或约束冲突）、`VALIDATION_ERROR`（金额/日期格式）。

## V3.0 预订与未来库存接口

按开发手册第 13 节实现；权限 `reservation:manage`（前台/总经理）、`inventory:manage`（仅总经理，停售）；预订退款另需 `billing:refund`（仅总经理）。入住区间一律 `[arrivalDate, departureDate)` 半开、至少一晚、最长 365 晚；状态机 `HELD → CONFIRMED → CHECKED_IN`，旁路 `CANCELLED`、`NO_SHOW`；联系电话可选、AES-GCM 加密保存、接口只出掩码；全部写操作带 UUID `Idempotency-Key`（同键同内容回放，不同内容 `IDEMPOTENCY_MISMATCH`）；修改/取消等带版本乐观锁的地方见各条。

| 方法与路径 | 规则 |
| --- | --- |
| `GET /api/reservations?status=&arrivalFrom=&arrivalTo=&query=&page=&pageSize=` | 预订分页；query 按外部单号/联系人模糊 |
| `POST /api/reservations` | `{arrivalDate,departureDate,roomTypeId,source,externalRef?,contactName,contactPhone?,heldUntil?,notes?}`；默认 HELD、到期 now+24h；默认禁止超卖（`ROOM_TYPE_SOLD_OUT` 报首个满夜）；外部单号去重 `EXTERNAL_REF_EXISTS` |
| `GET /api/reservations/{id}` | 详情：预订、事件轨迹、订金列表；密文绝不出接口 |
| `POST /api/reservations/{id}/modify` | `{version,arrivalDate?,departureDate?,roomTypeId?,contactName?,contactPhone?,notes?}`；同事务先锁旧+新日期（房型 id、日期升序）→ 释放旧 → 核验新 → 占用新 → 版本更新；失败整体回滚原预订原库存不变；版本不符 `STALE_VERSION`；满夜 `ROOM_TYPE_SOLD_OUT` |
| `POST /api/reservations/{id}/confirm` | HELD → CONFIRMED |
| `POST /api/reservations/{id}/cancel` | `{reason}`（1—500 字）；HELD/CONFIRMED → CANCELLED，释放 sold；罚金 0.00 元（首版未配置罚金政策，如实记录） |
| `POST /api/reservations/{id}/mark-no-show` | 仅 CONFIRMED；释放 sold |
| `POST /api/reservations/{id}/assign-room` | `{roomId}`；房型必须一致（`ROOM_TYPE_MISMATCH`）；分配是建议性，实际可住以入住校验为准 |
| `POST /api/reservations/{id}/check-in` | 复用入住入参（`{roomId,expectedCheckOutAt,guests}`）；仅 CONFIRMED；先释放 sold 再建在住段，同一夜晚不双扣；实际房间房型不一致 `ROOM_TYPE_MISMATCH`，脏房 `ROOM_NOT_AVAILABLE`；预计退房超出预订区间且未来夜晚已售 `FUTURE_NIGHT_CONFLICT`（只提示不自动赶客）；重复操作 `RESERVATION_STATE` |
| `POST /api/reservations/release-expired` | 释放到期保留单（每批最多 200，天然幂等），取消原因"保留单到期自动释放" |
| `POST /api/reservations/{id}/deposits` | `billing:receive`；`{method,amount,methodReference?,note?}`；写 `payment_records`（folio_id 空 + reservation_id，purpose=DEPOSIT）；CANCELLED/NO_SHOW 拒收，CHECKED_IN 提示转客账 |
| `POST /api/reservations/{id}/deposits/refund` | `billing:refund`；`{paymentRecordId,amount,reason}`；不超原笔未退金额与订金余额（`REFUND_EXCEEDS_LIMIT`）；已迁移客账的订金转客账退款 |
| `GET /api/reservations/receipt` | 普通收据打印视图（不标法定发票） |
| `GET /api/availability?arrivalDate=&departureDate=` | 所有启用房型逐夜 `{enabledRooms,occupied,sold,blocked,available}` 与 `bookable`（整段可订笔数 = min(available)）；最长 60 夜 |
| `GET /api/inventory/blocks?includeReleased=&page=&pageSize=` | 停售计划分页（仅 `inventory:manage`） |
| `POST /api/inventory/blocks` | `{roomTypeId,startDate,endDate,reason}`；房型级停售 `[start,end)`，区间内可售归零，不影响在住与既有事实 |
| `POST /api/inventory/blocks/{id}/release` | 解除停售，可售恢复；重复解除 `BLOCK_STATE` |

可售口径：`available = blocked>0 ? 0 : enabled_rooms − occupied − sold`；`occupied` 按营业日夜晚统计在住段（`[入住日, 实际/预计离店日)`），`sold` 只统计 HELD/CONFIRMED 预订；转入住时 sold 释放、占用转入在住段。

主要错误码：`ROOM_TYPE_SOLD_OUT`、`ROOM_TYPE_NOT_FOUND`、`ROOM_NOT_FOUND`、`ROOM_TYPE_MISMATCH`、`ROOM_NOT_AVAILABLE`、`EXTERNAL_REF_EXISTS`、`RESERVATION_NOT_FOUND`、`RESERVATION_STATE`、`STALE_VERSION`、`FUTURE_NIGHT_CONFLICT`、`PAYMENT_NOT_FOUND`、`REFUND_EXCEEDS_LIMIT`、`BLOCK_NOT_FOUND`、`BLOCK_STATE`、`IDEMPOTENCY_MISMATCH`、`VALIDATION_ERROR`。

## V4.0 渠道连接接口

按开发手册第 14 节实现供应商无关的渠道接入底座。权限 `channel:manage` 仅总经理。回调路径 `POST /api/integration/callbacks/{supplierCode}` 免登录但受 HMAC-SHA256 签名保护（`X-StayOS-Signature`，常量时间比较）；其余渠道接口全部要求登录 + `channel:manage`。首个获授权供应商条件缺失（无接口权限/正式文档/沙箱账号），按手册第 14 节回退完成供应商无关核心 + adapter 协议 + 可控模拟（SIMULATOR），真实供应商保持 `BLOCKED_EXTERNAL`，不宣称端到端集成通过。

凭据规则：连接只保存 `credentialRef` / `callbackSecretRef` 引用名，运行时按"系统属性 → 环境变量 → Spring Environment"解析；解析失败 `CREDENTIAL_UNRESOLVED`（创建时即校验）；真实密钥值不进数据库、日志、接口响应与审计文本。

| 方法与路径 | 规则 |
| --- | --- |
| `POST /api/integration/callbacks/{supplierCode}` | 供应商回调入口（免登录、CSRF 精确豁免）。签名失败 401 `CALLBACK_SIGNATURE_INVALID`（审计 `CHANNEL_CALLBACK_SIGNATURE`，不持久化任何内容）；验证通过后先持久化入站事实再异步处理；重复 `(connection, external_event_id)` 409 `DUPLICATE_EVENT`；内容不合法 400 `CALLBACK_MALFORMED`；事件类型限 `ORDER_CREATE/ORDER_MODIFY/ORDER_CANCEL/PRICE_UPDATE` |
| `GET /api/channels` | 连接列表：状态、凭据引用、最近收发/对账时间、最近错误、待处理/人工处理计数；不含密钥 |
| `POST /api/channels` | `{supplierCode,name,baseUrl?,credentialRef,callbackSecretRef,notes?}`；供应商未注册直接 400；创建时立即解析凭据引用 |
| `GET /api/channels/{id}` | 连接详情 + 最近 10 条同步尝试 |
| `PATCH /api/channels/{id}` | `{version,status?,baseUrl?,credentialRef?,callbackSecretRef?,notes?}`；乐观锁 `STALE_VERSION`；状态 `ACTIVE/PAUSED/BLOCKED_EXTERNAL` |
| `GET/POST /api/channels/{id}/mappings`、`DELETE /api/channels/mappings/{mappingId}` | 渠道编码 ↔ 本地房型/价格计划映射；`kind=ROOM_TYPE/RATE_PLAN`；每连接每类型每编码/每本地对象唯一（`MAPPING_EXISTS`） |
| `GET /api/channels/{id}/inbound?status=&page=&pageSize=` | 入站事件分页（`PENDING/PROCESSED/OBSOLETE/MANUAL`） |
| `GET /api/channels/{id}/outbox?status=&page=&pageSize=` | 出站事件分页（`PENDING/SENT/MANUAL`） |
| `POST /api/channels/inbound/{eventId}/retry`、`POST /api/channels/outbox/{eventId}/retry` | 仅 `MANUAL` 事件可重新入队（否则 409 `RESERVATION_STATE`）；入队即从 0 重新计数 |
| `POST /api/channels/{id}/process-now` | `{inboundLimit?,outboxLimit?}`（≤200）；入站按"连接 → 供应商序号 → 事件 id"有序领取，序号低于已结算水位记 `OBSOLETE` 事实不应用；出站按 id 有序领取，`PAUSED/BLOCKED_EXTERNAL` 连接跳过投递 |
| `POST /api/channels/{id}/reconcile`、`GET /api/channels/{id}/reconciliations` | 拉取供应商快照与本地渠道订单按外部单号比对：`checked/missingLocal/missingRemote/mismatch` 计数与差异明细落 `reconciliation_runs` |

入站处理语义：`ORDER_CREATE` 经共享 `ReservationService` 建单（来源 OTA、默认即确认）→ 出站 `RESERVATION_CREATED` + `RESERVATION_CONFIRMED`；`ORDER_MODIFY/CANCEL` 的出站事件由预订服务钩子按入站关联定位连接，拒绝跨渠道操作（外部单号不属于该连接即拒绝）。同一事件重放/重试使用确定性 UUID 幂等键，房量不足/映射缺失走与前台完全相同的错误码。

重试与人工处理：失败退避 `attempts²×2s`（上限 600s），`MAX_ATTEMPTS=8` 后转 `MANUAL` 停止自动重试；每次尝试落 `sync_attempts`（追加只写，含耗时与简短原因；不含负载/电话等敏感值）。出站业务变更与 outbox 写入同一本地事务；外部 HTTP 推送在所有业务事务之外；后台工作器每 5s 数据库有序领取（`for update skip locked`）。

主要错误码：`CALLBACK_SIGNATURE_INVALID`、`DUPLICATE_EVENT`、`CALLBACK_MALFORMED`、`CALLBACK_EVENT_TYPE`、`CREDENTIAL_UNRESOLVED`、`MAPPING_EXISTS`、`STALE_VERSION`、`RESERVATION_STATE`、`NOT_FOUND`、`VALIDATION_ERROR`。

## V5.0 有限客历接口

按开发手册第 16 节实现"有限客历"闭环。核心原则：`customer_profiles` 是人工维护的客户主数据，与每次入住登记的即时快照（`guests`/`stay_guests`）保持分离；**姓名相同或共用手机号绝不自动认定同一人**——同名/同号只在创建时作为"待人工核对"提示返回（`similarActiveProfiles`，最多 5 条），合并与关联都由前台/总经理人工决策。档案证件/电话沿用 GuestProtection AES-GCM 加密与掩码，任何接口不返回明文；打开档案详情留访问审计（`CUSTOMER_PROFILE_VIEW`）。

权限：`customer:read`（前台、总经理）读列表与详情；`customer:manage`（前台、总经理）建档/编辑/停用恢复/关联与解除关联；`customer:merge`（仅总经理）合并。客房等其余角色 URL 级拒绝。

| 方法与路径 | 规则 |
| --- | --- |
| `GET /api/customer-profiles?query=&status=&page=&pageSize=` | 分页列表；`status` 默认 `ACTIVE`，可选 `DISABLED/MERGED/ALL`；`query` 按姓名包含匹配；只返回掩码 |
| `GET /api/customer-profiles/{id}` | 详情（含合并目标摘要 `mergedInto`、关联入住 `stays`）；每次访问审计 `CUSTOMER_PROFILE_VIEW` |
| `POST /api/customer-profiles` | `{name,documentType,documentNumber,phone?,notes?}`，必须带 `Idempotency-Key`；同键同内容重放返回原档案，同键不同内容 409 `IDEMPOTENCY_MISMATCH`；响应含同名启用档案提示 `similarActiveProfiles`（信息性提示，不做任何自动处理） |
| `PUT /api/customer-profiles/{id}` | `{name,documentType,documentNumber,phone?,notes?,version}`；乐观锁：版本不符 409 `VERSION_CONFLICT`；证件/电话重录重新加密，不回显旧值 |
| `POST /api/customer-profiles/{id}/set-status` | `{status: ACTIVE\|DISABLED}`；停用后不能再关联新入住（409 `PROFILE_DISABLED`），详情仍可读 |
| `POST /api/customer-profiles/{id}/merge` | `{targetId}`，仅总经理；目标必须 `ACTIVE`（409 `PROFILE_NOT_ACTIVE`）、不能合并到自身（400）；源档案置 `MERGED` 并把已关联入住整体移交给目标档案；合并后源档案终态，不能再编辑/变更状态/再次合并（409 `PROFILE_MERGED`） |
| `POST /api/customer-profiles/{id}/stays` | `{stayRecordId,guestId?}` 把一次入住登记挂到档案；`guestId` 缺省取该登记的主要住客；每次入住登记的每个住客快照最多属于一个档案（数据库唯一约束兜底）：同档案重复 409 `PROFILE_STAY_EXISTS`，住客已挂在其他档案 409 `GUEST_ALREADY_LINKED`；关联不改动入住记录本身 |
| `POST /api/customer-profiles/stay-links/{linkId}/detach` | `{linkId}` 解除关联；只删关联行，入住历史保持完整可查；错误匹配的修正路径 = 解除后重新关联 |

主要错误码：`PROFILE_NOT_FOUND`、`PROFILE_MERGED`、`PROFILE_NOT_ACTIVE`、`PROFILE_DISABLED`、`PROFILE_STAY_EXISTS`、`GUEST_ALREADY_LINKED`、`PROFILE_STAY_LINK_NOT_FOUND`、`STAY_NOT_FOUND`、`VERSION_CONFLICT`、`IDEMPOTENCY_KEY_REQUIRED`、`IDEMPOTENCY_MISMATCH`、`VALIDATION_ERROR`。审计动作：`CUSTOMER_PROFILE_CREATE/UPDATE/SET_STATUS/MERGE/STAY_LINK/STAY_UNLINK/VIEW`（操作记录页业务模块"客历档案"）。本版不做批量导出与营销能力（权限未定义即不存在）。
