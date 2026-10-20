# 第 2 阶段：房间资料与房态

## 2026-10-20 已确认的规则

1. 第一版单酒店、不设楼栋，房号全酒店唯一。楼层是非零整数，地下层使用负数。
2. 无使用历史可改房号；有使用历史禁止直接改号。内部 ID 始终不变。
3. 支持 INSPECTION 的存储、查询、展示、统计，但不实现检查或主管验收流程，也不自动进入该状态。
4. 占用以有效房间使用记录为准；维修停用以未解决的阻止异常为准，不在房间表保存第二份可独立编辑的事实。
5. 新房默认启用、待打扫。无任意改房态接口，无入住、清洁任务、维修工单及收费业务。

## 数据结构和扩展边界

V3 是增量迁移，不修改已执行的 V1/V2，不清空数据库。新增 room_types 与 rooms；既有 users、roles、user_roles、audit_logs 保留。

rooms 只保存基础资料、清洁状态及版本号。room_operational_facts 是统一的事实查询视图；第 2 阶段最初尚无入住、异常、任务记录，所以明确返回空事实。**第 3 阶段通过 V4 新迁移接入真实 stay_room_segments 的占用与历史**，原 V3 不改写。异常和任务仍待各自阶段接入，不能继续沿用空事实。实际入住规则见 CHECK-IN.md。

room_status 视图是本阶段唯一的可入住及综合房态计算规则，列表、详情、筛选、统计都查询它。未来入住/换房应先锁定 rooms 行，再重新查询该规则，不以界面颜色判断。

可入住 = 已启用 AND 无占用 AND CLEAN AND 无阻止异常 AND 无未完成清洁任务。

综合展示优先级：DISABLED → OCCUPIED → OUT_OF_SERVICE → CLEANING → INSPECTION → DIRTY → UNAVAILABLE → VACANT_CLEAN。UNAVAILABLE 用于清洁合格但仍有未完成任务等不可分配情形。每条数据同时保留各维度，综合标签不掩盖其他事实。统计按独立维度计算，所以各状态数量可能重叠，不应相加当作总数。

真实 PostgreSQL 测试会在独立 stayos_test 库中构造隔离的业务事实夹具，验证占用、异常、任务及历史判断；不在开发库或生产库插入假入住记录，不向业务用户开放改状态接口。

## 普通实现约定

- 房号去除首尾空格并转大写，1—20 位，允许英文字母、数字、下划线和短横线；0801 与 801 不同，a801 与 A801 相同。
- 房型编码同样转大写，1—50 位；名称 1—100 字，描述/备注最多 2000 字，床位说明最多 200 字，建议人数 1—20。
- 楼层范围 -99 至 199，排除 0；楼层清单从现有房间汇总，不建多余的楼层配置表。新增房间时可输入新楼层。
- 房型停用禁止新增/改绑到该房型，现有房间继续显示且不自动停用。不提供硬删除接口。
- 管理员维护资料，五角色均可查询；房型/房间配置权限不授予总经理。
- 资料修改使用版本号，拒绝旧页面覆盖别人的新修改；新增防重依靠数据库唯一约束。本阶段不提前决定入住等关键流程的 Idempotency-Key 保存策略。
- 可选 Excel 导入不在本阶段实现。

第 2 阶段已完成，实施与真实测试结果见 [PHASE2-DELIVERY.md](PHASE2-DELIVERY.md)。


## 已实现接口说明

所有接口沿用 Session、CSRF、统一响应和中文错误；资料维护需 room:manage（默认仅 ADMIN），查询需 room:read（五角色）。所有修改请求操作人取自当前会话。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | /api/room-types | 房型分页，可筛选 enabled、search |
| POST | /api/room-types | 新建房型 |
| PUT | /api/room-types/{id} | 编辑房型，必须带 version |
| POST | /api/room-types/{id}/set-enabled | 房型启用/停用，必须带 enabled、version |
| GET | /api/rooms | 房间分页、组合筛选 |
| GET | /api/rooms/{id} | 完整基础资料及房态维度，无住客个人信息 |
| POST | /api/rooms | 新建房间，默认启用且 DIRTY |
| PUT | /api/rooms/{id} | 编辑基础资料，必须带 version |
| POST | /api/rooms/{id}/set-enabled | 受控启用/停用，必须带 enabled、version |
| GET | /api/room-status | 房态图数据，含同一数据库快照内的列表和统计 |
| GET | /api/room-status/statistics | 同筛选条件下的独立统计 |
| GET | /api/floors | 已有房间的楼层及每层数量 |

分页默认 page=1、pageSize=20，每页最多 100。房态图每页 24，统计始终覆盖全部筛选结果，不仅当前页。房型接口 search 同时匹配名称和编码。

房间/房态查询共用以下参数（均可选）：roomNumber（不区分字母大小写的包含搜索，不把百分号当通配符）、floor、roomTypeId、availability、cleaningStatus、occupancyStatus、maintenanceStatus、enabled、canCheckIn，以及 page/pageSize。各筛选条件取交集。统计同样遵守这些条件；独立维度可能重叠，例如已入住且有故障会分别计入 occupied 和 outOfService。

新增房型请求示例：

```json
{
  "typeCode": "DELUXE_KING",
  "typeName": "豪华大床房",
  "description": "虚构房型说明",
  "bedDescription": "1 张 1.8 米大床",
  "suggestedOccupancy": 2
}
```

新增房间请求示例（roomTypeId 使用实际返回的房型内部 ID）：

```json
{
  "roomNumber": "0801",
  "floor": 8,
  "roomTypeId": 1,
  "notes": "虚构房间备注"
}
```

编辑资料提交同样的业务字段，另外带上读取时返回的 version。启用/停用请求为 {"enabled":false,"version":0}。成功后版本递增，旧版本返回 409 STALE_VERSION；前端提示刷新，不自动覆盖他人修改。新增/编辑接口不接收可生效的占用、清洁或维修状态字段。

列表 data：items、total、page、pageSize。房态图另外返回 statistics。每条房间数据包含基础字段、roomTypeName、roomTypeCode、roomTypeEnabled、occupied、occupancyStatus、cleaningStatus、maintenanceStatus、enabledStatus、hasBlockingIssue、hasOpenCleaningTask、hasUsageHistory、canCheckIn、availability。hasUsageHistory 仅用于是否可改号的业务提示，不提供住客历史资料。

statistics 的字段为 total、available、occupied、dirty、cleaning、inspection、outOfService、disabled。空结果均为 0。

主要错误：

- 400 VALIDATION_ERROR：格式、楼层、人数或查询条件不正确。
- 400 VERSION_REQUIRED：编辑缺少版本。
- 401 UNAUTHORIZED：未登录或登录失效。
- 403 FORBIDDEN：无维护权限。
- 404 ROOM_NOT_FOUND / ROOM_TYPE_NOT_FOUND：房间/房型不存在。
- 409 ROOM_NUMBER_EXISTS / ROOM_TYPE_CODE_EXISTS：房号/房型编码重复。
- 409 STALE_VERSION：资料已被别人修改。
- 409 ROOM_TYPE_DISABLED：不能新增或改绑到停用房型。
- 409 ROOM_NUMBER_HAS_HISTORY：已有历史不能直接改房号。
- 409 ROOM_IN_USE：有占用或未完成清洁任务，不能停用。
- 404 NOT_FOUND：房间路径下不存在的接口，例如任意改房态接口。

成功资料维护和审计同一事务提交。数据库唯一约束是最后防线，并发重复新增也不能产生同号房间。停用房型与新建/改绑房间锁同一房型记录，防止校验通过后房型恰好被停用。修改和停用房间锁 rooms 行；未来创建入住、任务、异常等业务也必须锁同一行，然后重新判断。

房态查询使用只读、可重复读事务，使同次返回的列表、总数和统计一致。不承诺其他员工随后操作后页面仍实时有效；手动刷新和重新进入页面会查询最新数据，未来办理入住时还须在提交事务内重新核对。
