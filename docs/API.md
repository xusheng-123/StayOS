# 酒店前台与客房协同管理系统｜接口说明草案

> **这份文档是做什么的？** 它约定前端页面如何向后端请求数据，以及后端会返回什么。可以把“接口/API”理解成网页和服务器之间的办事窗口：网页提交请求，服务器验证规则、处理业务，再把结果返回。
>
> **初学者提醒：** 本文同时保留实际接口和后续设计草案。已实现部分按下方“当前实现进度”及各节标注识别；未实现的换房等示例不能当作已经运行的程序。

> **版本边界：** 第 4—10 节及第 11 节的管理/历史接口属于第一版。第 12 节为第二阶段收费参考，第一版不实现、不提供空接口或页面；退房不要求账单、支付信息或结算状态。第一版无主管验收、住中清洁、预订和续住接口。

> **当前实现进度：** 第 1 阶段完成认证及账号/角色；第 2 阶段完成房间与房态，见 ROOMS.md；第 3 阶段入住、当前与历史入住查询见 CHECK-IN.md 和第 6 节。第 4 阶段退房见 CHECK-OUT.md；第 5 阶段清洁处理及人员批量分房见 HOUSEKEEPING.md 和根目录 PHASE5-BATCH-ASSIGNMENT-DELIVERY.md（PHASE5-DELIVERY 为优化前快照）；第 6 阶段异常及维修停用已实现，见 ROOM-ISSUES.md；换房仍未开发。各阶段交付文档是当时的验收快照。

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

## 8. 换房接口

| 方法 | 路径（建议） | 用途 | 允许角色 |
|---|---|---|---|
| POST | `/api/stays/{stayId}/room-change` | 办理换房 | 前台、酒店总经理 |
| GET | `/api/stays/{stayId}/room-changes` | 查询换房历史 | 前台、酒店总经理 |

请求示例：

```json
{
  "fromSegmentId": 20001,
  "toRoomId": 10005,
  "reasonType": "BLOCKING_ISSUE",
  "reason": "空调故障",
  "blockingIssueId": 30001,
  "notes": "演示数据"
}
```

后端核对 `fromSegmentId` 仍是当前段、目标房不是原房且满足统一可入住条件。同一事务关闭原段、建立新段、写换房历史、原房标记 `DIRTY`、创建关联原段的 `ROOM_CHANGE` 清洁任务和成功审计；原房故障阻止不解除。任一步失败全部回滚，本次入住保持 `IN_HOUSE`。

`reasonType` 必填且只能为 `NORMAL` 或 `BLOCKING_ISSUE`。普通换房不关联异常，`blockingIssueId` 为空；影响入住的故障换房必须选择 `BLOCKING_ISSUE`，先通过异常上报接口创建记录或选择已有异常，再传其 ID。后端在换房事务中核对异常属于原房、状态为 `OPEN/IN_PROGRESS` 且严重程度为 `CRITICAL`（换房在第 7 阶段实现时核对；不再使用旧 blocksCheckIn 字段）；缺失、他房、已解决或不阻止入住的异常均拒绝。不能用 `reason` 自由文字代替异常登记，也不从文字自动推断类别。

异常上报与换房是先后两次请求，上报失败不得继续；换房失败保留之前已上报异常，不执行删除补偿。异常若在提交前被解决，故障换房应返回冲突，不能用过期异常继续办理。

成功返回换房记录 ID、新旧房间使用记录 ID、房间摘要、服务器生成的换房时间和原房清洁任务 ID。换房历史返回每次原房、新房、原因、操作人和时间，房间使用历史返回每段进入/离开时间，不覆盖原房号。重复同一请求返回既有结果；换用新请求标识重放旧原段时也必须拒绝。

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
| `BLOCKING_ISSUE_REQUIRED` | 故障换房未关联阻止入住的异常 | 请先上报或选择原房未解决的阻止异常 |
| `BLOCKING_ISSUE_INVALID` | 关联异常属于他房、已解决或不阻止入住 | 异常状态已变化，请重新核对 |
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
