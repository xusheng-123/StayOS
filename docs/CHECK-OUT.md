# 第 4 阶段：正式退房与自动清洁任务

本文件描述实际实现。收费、换房及清洁任务处理不在本阶段；已有认证、住客加密和入住流程继续使用。验收结果见 [PHASE4-DELIVERY.md](PHASE4-DELIVERY.md)。

## 正式退房接口

`POST /api/stays/{stayId}/check-out`

- 登录 Session、CSRF、`stay:manage` 权限必需，默认仅 FRONT_DESK / HOTEL_MANAGER。
- 请求头 `Idempotency-Key`：标准 UUID，新操作生成一次，重试复用。
- 请求体：`{"segmentId": 123}`。ID 必须为正整数。与第三阶段详情的 `segmentId` 对齐，不使用早期草案的 `fromSegmentId`。
- 不接受业务方指定实际时间或经办人；服务器从时钟及登录身份取得。不实现额外退房备注，不读取收费/账单状态。

成功返回原有 `ApiResponse` 外壳，data 字段为：

| 字段 | 含义 |
|---|---|
| stayId / segmentId / roomId | 入住、实际结束的使用段、稳定房间内部 ID |
| roomNumber / roomTypeName | 退房使用段保留的房号及房型快照 |
| actualCheckOutAt / checkedOutBy | 服务器实际退房时间、登录经办人 ID |
| taskId | 本次自动清洁任务 ID |
| cleaningStatusAtCheckout | 固定 DIRTY，表示退房当时的业务结果 |
| taskStatusAtCreation | 固定 PENDING，表示任务创建时的状态 |

结果中的两个状态描述历史时点；实时房态和实时任务状态分别查原房态接口及任务接口。此设计使网络重试返回稳定结果，避免未来任务完成后把历史退房结果误解为实时房态。

## 同一事务内的流程

1. 标准化业务内容（入住 ID、当前段 ID），复用共享 `IdempotencyService` 预留请求。只存带密钥的摘要，不存请求正文。
2. 先锁入住行；必须存在且为 IN_HOUSE。
3. 查当前段，锁对应房间，再锁并重读当前段，核对客户端 ID。未来多房间操作按房间 ID 升序锁定。
4. 生成一份服务器时间，将入住改为 CHECKED_OUT，写实际时间和经办人。
5. 同一时间关闭当前使用段，`end_reason=CHECK_OUT`，保留全部段和住客历史。
6. 仅将房间清洁状态改为 DIRTY，递增房间版本，不改变启用/异常事实。
7. 调用客房模块 `TaskCreationService.fromCheckout` 创建任务。它要求已经存在事务，不能独立提交。
8. 写 CHECK_OUT 成功审计、幂等成功入住 ID，读取对应退房结果，一起提交。

任何关键写入失败，全事务回滚，包括请求预留、房间版本/清洁状态、入住/段结束、任务和成功审计。接口失败日志可在回滚后独立保存，不是成功业务记录。

幂等预留在业务行锁之前。两个不同标识竞争同一入住时，后到者等待入住锁，再发现已退房并拒绝。它自己的请求预留也回滚。旧页面即使换一个标识，也不能跳过当前段检查。未来换房必须沿用“入住 → 房间升序 → 段/关联记录”的顺序，与退房串行校验。

## 防重复规则

与第三阶段同表、同主键、同 24 小时规则：操作人 + 操作类型 + UUID。

- 操作类型为 CHECK_OUT；入住仍为 CHECK_IN，两者互不覆盖。
- 24 小时内，同内容返回原结果，不同内容返回冲突；路径里的入住 ID 也是摘要的一部分。
- 超过 24 小时拒绝，不自动清理或复用旧标识。
- 业务失败时全部回滚，修复问题后可沿用原标识重试。
- 结果仍用原 `result_stay_id`，结合稳定的退房字段、来源唯一的任务和对应段重建，不保存完整响应或敏感资料。
- HMAC 和住客保护仍使用第三阶段现有密钥及摘要算法，未改变历史 CHECK_IN 摘要的计算方式。

## 数据库结构与房态

V7 新建 `housekeeping_tasks` 并扩展幂等操作枚举、房态事实视图；V8 加强房间级未完成任务唯一性。V1—V6 未修改。V7 已在测试库执行后才增加 V8，没有修改已执行文件。

任务列：id、room_id、task_type、source、source_id、stay_record_id、room_segment_id、status、created_by、created_at、notes。

- task_type=CHECKOUT_CLEANING，source=CHECKOUT，status=PENDING，notes 默认空，不复制住客或入住备注。
- source_id 指本次退房所属入住 ID，room_segment_id 指这次离开的段。
- `(source,source_id)` 唯一，同一次退房只能一条任务；段 ID 唯一，同一离房段不能重复建任务。
- PENDING 任务按 room_id 部分唯一，同一房间最多一条未完成任务。
- 复合外键校验段、入住和房间必须相互匹配；外键限制删除历史。
- 不新增退房表、收费表、任务分配表或任务日志表。

`room_operational_facts` 现在从实际任务推导 `has_open_cleaning_task`。占用继续从未结束段推导，清洁继续只存在 `rooms.cleaning_status`。因此退房后 VACANT + DIRTY + 有待处理任务，`canCheckIn=false`。即使单独把清洁误改成 CLEAN，有未完成任务仍不能入住。

维修状态目前仍来自原异常事实接口，尚无完整异常/维修模块；退房不清除阻止异常，也不操作启用开关。隔离测试通过阻止事实验证其保留行为，没有创建维修业务接口。

第 5 阶段增加任务状态时，必须同步增量调整状态约束、未完成唯一索引、房态视图，不能让进行中的任务漏出入住阻止判断。

## 客房任务查询

`GET /api/housekeeping-tasks`、`GET /api/housekeeping-tasks/{taskId}`，要求 `housekeeping:read`。

默认授予 HOTEL_MANAGER / HOUSEKEEPING_MANAGER / HOUSEKEEPING_STAFF。ADMIN 和 FRONT_DESK 不自动拥有该权限。多角色继续按权限并集合并。

本阶段所有任务均未分配，服务员查看的是未分配任务池。未来分配功能落地时，再把服务员范围收紧为未分配及本人任务；当前没有“可读取他人已分配任务”的接口，因为没有已分配任务。

列表参数：page（默认 1，最大 100000）、pageSize（默认 20，最大 100）、roomNumber（最长 20，忽略前后空格并转大写，字面包含）、floor（-99—199，不含 0）、status（仅 PENDING）。列表和计数在同一只读快照查询。

返回字段仅包括：id、roomId、roomNumber、floor、roomTypeName、taskType、source、status、createdAt、notes。没有住客姓名、证件、电话、密文、入住备注或住客详情入口。

## 页面与安全重试

- `/stays`：当前入住列表，点击“办理退房”，确认房号/房型/联系人/实际及预计时间。
- 成功显示房号、实际时间、待打扫和清洁任务已创建；可跳转 `/stays/history`、`/rooms`。
- `/housekeeping/tasks`：客房功能 → 清洁任务，房号/楼层筛选、刷新、分页、详情，仅查询。
- 不显示接单、开始、完成、分配或验收按钮。

提交期间禁用重复点击。网络中断、响应不完整、登录中断或 500 等结果不确定时，保留原 ID 和 UUID，重试原请求。`sessionStorage` 只存入住 ID、段 ID、UUID，并按登录用户区分，不存姓名、证件或电话。刷新后可恢复原请求。

如果重试过期或无法继续，可暂时关闭窗口，先查历史；“核对后结束重试”需要明确核对确认，只清本机标识，不能撤销退房或重新执行退房。切勿仅因客户端没收到成功，就生成新标识重放操作。

## 常见错误

| HTTP / code | 处理 |
|---|---|
| 400 VALIDATION_ERROR / IDEMPOTENCY_KEY_REQUIRED | 检查参数和请求标识 |
| 401 UNAUTHORIZED | 重新登录；结果不确定时沿用原标识 |
| 403 FORBIDDEN / CSRF_INVALID | 无权限或安全校验失败，不越权重试 |
| 404 STAY_NOT_FOUND / TASK_NOT_FOUND | 记录不存在 |
| 409 STAY_ALREADY_CHECKED_OUT | 新操作不能再次退房，查询历史 |
| 409 STALE_ROOM_SEGMENT | 页面过期，重新读取并确认当前房间 |
| 409 IDEMPOTENCY_MISMATCH | 同标识换内容，先核对原结果 |
| 409 IDEMPOTENCY_KEY_EXPIRED | 旧标识超过 24 小时，不自动重新执行 |
| 409 STAY_SEGMENT_INCONSISTENT / SERVER_TIME_INVALID | 数据关系或服务器时间异常，需管理员核查 |
| 500 INTERNAL_ERROR | 不暴露数据库堆栈；原请求安全重试或先核对结果 |
