# 第 6 阶段：房间异常与维修停用（当前实现）

本规范以第六阶段指令为准，替代早期 EQUIPMENT_FAILURE / HYGIENE_ISSUE / LOST_PROPERTY 和 blocksCheckIn 的草案。本阶段不实现换房、完整维修工单、收费或主管清洁验收。

## 业务与状态

- 类型：FACILITY 房间设施、ELECTRICAL 电器、PLUMBING 供水管道、SANITATION 卫生环境、SAFETY 安全、OTHER 其他；类型与严重程度相互独立。
- 严重程度：LOW 一般、MEDIUM 需要处理、CRITICAL 严重；只有尚未解决的 CRITICAL 自动阻止新入住。系统不从描述文字猜测严重程度。
- 异常：OPEN → IN_PROGRESS → RESOLVED；主管可填写结论从 OPEN 直接解决。RESOLVED 不重开、不重复处理，新的故障另建异常。
- 主管可调整未解决异常的严重程度，必须提交当前 version 和非空原因，保存前后程度及操作人；员工不能降级以移除阻止。
- 维修状态统一派生：存在未解决 CRITICAL 或有效人工停用 → OUT_OF_SERVICE，否则 NORMAL。rooms 不增加独立维修状态字段。
- 人工停用独立于异常，最多一条有效记录；解除须指定当前 stopId、填写原因，并先解决所有严重异常。解决异常不自动解除人工停用。
- 异常和停用可发生于已入住房间，不结束入住、不删除使用段、不自动换房，也不修改清洁、启用状态。
- 合法清洁仍能执行并完成，但不会移除维修阻止；解除维修也不会把 DIRTY 改为 CLEAN。

## 权限与数据范围

| 角色 | 上报 | 异常详情与历史 | 开始/解决/调级 | 停用/恢复 |
|---|---|---|---|---|
| ADMIN | 否 | 否 | 否 | 否 |
| FRONT_DESK | 是 | 全部异常 | 否 | 否 |
| HOUSEKEEPING_STAFF | 是 | 仅自己提交 | 否 | 否 |
| HOUSEKEEPING_MANAGER | 是 | 全部 | 是 | 是 |
| HOTEL_MANAGER | 是 | 全部 | 是 | 是 |

所有角色原有房态查询仍返回工作所需的异常/严重异常/人工停用标识；ADMIN 不由此获得异常业务权限。服务员的列表、统计、详情和历史均在后端按上报人裁剪，不能用 roomId 或 URL 绕过。多角色账号仍使用已有权限合并规则。仅主管和总经理保留清洁分房权限，所有角色自主领取继续禁止。

## 已实现 API

统一 Session、CSRF、ApiResponse；写请求必须带 UUID 格式 Idempotency-Key。时间和操作人均来自服务器。

| 方法 | 路径 | 参数或说明 |
|---|---|---|
| GET | /api/room-issues | page/pageSize（上限100）、roomNumber、roomId、floor、issueType、severity、status |
| GET | /api/room-issues/statistics | 当前可见范围的待处理、处理中、严重数量；全店维修停用房间数量 |
| GET | /api/room-issues/{id} | 授权范围内详情 |
| GET | /api/room-issues/{id}/history | 按操作顺序的历史 |
| POST | /api/room-issues | roomId、issueType、severity、description（1–2000字）、notes（选填，上限1000） |
| POST | /api/room-issues/{id}/start | version、reason（选填，上限1000） |
| POST | /api/room-issues/{id}/resolve | version、reason（必填解决说明） |
| POST | /api/room-issues/{id}/severity | version、severity、reason（必填） |
| POST | /api/rooms/{id}/maintenance-stop | reason（必填）、issueId（选填，必须属于同房间） |
| POST | /api/rooms/{id}/maintenance-resume | stopId、reason（必填） |
| GET | /api/rooms/{id}/maintenance-history | 该房间真实停用及恢复历史；异常业务角色可查询，无住客资料 |
| GET | /api/room-operations/{operation}/{key} | 仅查询本人请求的不可变成功回执；未查到返回404，不自动重新执行 |

操作编码 ISSUE_REPORT / ISSUE_START / ISSUE_RESOLVE / ISSUE_SEVERITY / MAINTENANCE_STOP / MAINTENANCE_RESUME。
新增业务冲突统一 ISSUE_CONFLICT（409），非法输入400、未登录401、越权403、不可见记录404；数据库约束冲突409，不展示SQL或堆栈。无删除异常/历史接口，无任意设置维修或清洁状态接口。

## 事务、并发和请求重试

复用第三阶段的 PostgreSQL 幂等表及摘要计算：操作人＋操作＋UUID，内容一致24小时内重放原成功回执，不同内容409；超过24小时旧标识409，历史暂不清理。失败事务连同请求预留一起回滚，修复后可用原请求重试。只关联历史回执ID，不存完整请求正文。

统一锁顺序：请求预留 → 当前员工共享锁 → 房间行锁 → 异常/停用行锁 → 历史、审计、幂等结果。异常流程不反向锁入住；沿用入住的房间锁及退房的入住→房间顺序。取得锁后重新读取真实运营事实。先提交的严重异常会使等待中的入住失败；入住先成功则保留入住，异常照常登记。

每个操作的业务、追加历史、成功审计和幂等结果同事务。room_operational_facts / room_status 统一派生维修阻止，查询、统计和入住共用，未创建另一套状态副本。

## 前端使用

访问 /room-issues；前台、客房服务员可上报，主管和总经理可处理。服务员页面明确只列自己的异常。

“上报异常”选择房间和分类、程度，填写说明。“查看异常”展示历史并提供主管处理按钮。人工停用可关联异常，也可独立创建；独立停用房间从“维修记录与恢复”查询和恢复，不依赖虚构异常。

房态卡片保留占用、清洁、维修和严重异常文字；复用原房间详情展示阻止原因及授权范围内的异常/维修记录。数据通过刷新重新获取，没有实时推送。

网络不确定时冻结原内容并使用“重试原请求”；浏览器仅保存操作类型和请求标识，不保存说明正文。刷新后可查询原结果；未查到不代表原操作必然失败，须核对业务记录后显式结束恢复。

## 数据库

新增 V11：room_issues、room_issue_logs、room_maintenance_stops、room_maintenance_logs。后两张分别保留停用周期及STOP/RESUME不可变操作回执。真实房间/用户/异常外键、同房异常关联、状态时间配对、异常版本日志唯一、有效停用部分唯一索引共同保护一致性。异常更新触发器限制合法转换及保留原始上报事实。

共用 idempotency_requests 新增 result_issue_log_id / result_maintenance_log_id，按操作类型约束结果种类。所有 V1–V10 保持原样。无历史删除API；数据库运维账号仍应受控，本阶段未建设不可篡改审计存储。
