# 第 3 阶段入住管理

## 已确认规则（2026-10-20）

- 全部实际住客独立登记，姓名、证件类型、证件号码必填，一名主要联系人；电话、住客备注、入住备注选填。
- 证件类型 ID_CARD / PASSPORT / OTHER。证件与电话使用 AES-256-GCM 随机 nonce 加密，密钥仅来自 Git 外的环境配置；接口只返回脱敏号码，无完整号码查询接口。姓名是授权业务查询字段，不属于匿名信息。
- 开发/测试只用虚构资料；正式试用前暂停确认个人信息保留期限及身份核验要求。不宣称完成实名核验或经营合规认证。
- PostgreSQL 保存成功入住的防重标识，以操作人、CHECK_IN、UUID 请求标识为唯一组合。请求摘要为带密钥 HMAC-SHA256，不保存原请求/完整响应。
- 同标识同内容 24 小时内返回原入住 ID 对应的脱敏详情；不同内容拒绝。24 小时从首次成功事务的建立时间起算，过期旧标识拒绝；最小记录暂不自动清理。失败全事务回滚，不占用标识，允许修正后重试。
- 沿用已确定的 stay_room_segments 房间使用分段设计；本阶段只创建第一段，不实现换房/退房。当前房间从未结束段得到，不在入住主表再保存一份可独立改写的当前房间。
- 不改变开发库 80 间待打扫客房；隔离测试库构造 CLEAN 房间验证正常业务。

## 普通实现约定

- 每次登记 1—20 人是接口负载限制，与房型建议人数无关，不作为法定容量判断。前端超过建议人数显示提示但不直接禁止。
- 证件号码仅做长度与字符/基本格式检查，不检查真实性；居民身份证只检查 18 位格式，护照 3—30 位字母数字，其他证件 3—64 位字母数字、短横线、斜线或下划线。不校验居民身份证校验位，避免把格式检查表述为身份核验。
- 同一次入住不允许重复的证件类型与号码组合；本阶段不建设跨入住常客档案或按证件自动合并，guests 保存每次登记快照。
- 电话可空，最多 30 字符；备注最多 1000 字。预计退房必须带时区并晚于服务器实际入住时间，页面按 Asia/Shanghai 显示；到期不自动退房。
- 密钥固定标识 v1；须与加密数据一并安全备份。不得随意重新生成。后续轮换须专门迁移与解密重加密方案；本阶段不提供轮换或解密接口。

## 表结构

- guests：每次登记的住客快照。姓名、证件类型、加密证件/电话、脱敏号码、密钥标识 v1、备注、创建时间。查询 SQL 不读取密文字段。
- stay_records：一次入住的主记录，实际/预计/实际退房时间、IN_HOUSE/CHECKED_OUT、经办人、备注及创建/更新时间。不保存可覆盖的当前 room_id。
- stay_guests：入住和实际住客的关联，记录顺序及主要联系人；同一入住最多一个主要联系人，服务层要求恰好一个。
- stay_room_segments：房间使用段，保存房间 ID、入住 ID、进出时间、结束原因、房号与房型名称快照。部分唯一索引保证一间房/一次入住各最多一段未结束使用。
- idempotency_requests：操作人/操作/UUID、HMAC 内容摘要、成功入住 ID、建立/过期时间。失败事务不留半成品，不保存整份请求或身份证明资料。
- 所有业务关联使用外键并限制删除。CHECKED_OUT 和已结束段只为后续流程及测试夹具提供结构，不存在办理退房接口。V4 建表；V5/V6 增量补强 SQL NULL 约束，不改已执行迁移。

## 接口

所有入住接口要求 stay:manage，默认仅 FRONT_DESK、HOTEL_MANAGER，多个角色按现有机制合并；ADMIN、客房岗位不自动获得住客查询权限。Session、CSRF、错误响应沿用现有设计。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/stays | 入住，Idempotency-Key 为 UUID |
| GET | /api/stays/available-rooms | 复用房间分页/筛选，后端强制可入住 |
| GET | /api/stays | 默认当前在住，可 status=IN_HOUSE/CHECKED_OUT/ALL |
| GET | /api/stays/history | 固定查询 CHECKED_OUT |
| GET | /api/stays/{id} | 脱敏详情 |

POST 字段：roomId、expectedCheckOutAt、guests[]、notes。每位住客 name、documentType、documentNumber 必填，phone/notes 可空，primary 恰好一人为 true。实际入住时间和操作人由服务器生成，不接受客户端覆盖。

入住列表筛选：page 默认 1、pageSize 默认 20 最大 100；roomNumber/guestName 为字面包含搜索，分别最多 20/100 字；checkInFrom 包含起点、checkInTo 不含终点，须带时区。房号匹配入住曾使用的房间快照；目前只创建第一段。历史空列表是正常结果，不伪造开发库退房。

列表 data 为 items、total、page、pageSize。items 包含 id、roomId、segmentId、roomNumber、roomTypeName、status、createdBy、operatorName、primaryGuestName、guestCount、notes、checkInAt、expectedCheckOutAt、actualCheckOutAt、createdAt、updatedAt；列表 guests 为 null，不批量返回证件/电话。详情 guests 包含 id、name、documentType、脱敏 documentNumber/phone、notes、primary。前端不把这些资料写入 localStorage/sessionStorage。

主要错误：400 VALIDATION_ERROR / IDEMPOTENCY_KEY_REQUIRED；401 UNAUTHORIZED；403 FORBIDDEN / CSRF_INVALID；404 ROOM_NOT_FOUND / STAY_NOT_FOUND / NOT_FOUND；409 ROOM_NOT_AVAILABLE / IDEMPOTENCY_MISMATCH / IDEMPOTENCY_KEY_EXPIRED / REQUEST_IN_PROGRESS。错误和审计不包含提交的完整证件与电话。

## 事务和锁

先竞争同一操作人的请求标识；重复并发请求等待原事务结果。新请求锁定 rooms 行，再查询 room_status 的统一规则；住客、入住、使用段、防重结果和成功审计在同一事务提交。失败全回滚。入住不改清洁状态。不同标识也不能突破同房唯一占用索引。

已有入住/换房/退房时统一遵守先锁入住、再按 ID 锁房间、再关联记录的顺序；首次入住不存在旧入住行，因此先锁请求标识再锁房间。未来任务/异常仍必须锁同一房间，并用新迁移替换事实视图中的未实现部分。

## 页面与运行

- /stays/new：办理入住。可入住房间为空时明确提示；不能绕过清洁条件。
- /stays：当前入住，按房号、任一登记住客姓名、实际入住时间查询。
- /stays/history：已结束记录；/stays/{id}：详情；成功后可跳转房态图重新查询占用和统计。
- 预计退房按北京时间输入；实际入住最终以服务器为准。
- 网络结果不确定时冻结原表单，重试沿用原标识和原内容；浏览器只在 sessionStorage 保存与当前用户绑定的随机请求标识。刷新后不恢复住客草稿，先提示核对记录再登记。
- 新环境先运行 scripts/init-local.ps1，再运行 scripts/init-guest-key.ps1，最后加载 scripts/env.ps1。已有环境只补缺少的住客加密密钥。密钥缺失或格式错误时后端拒绝启动，不使用通用默认密钥。

## 当前开发状态

第 3 阶段已完成。真实测试结果、文件清单、限制及后续建议见 [PHASE3-DELIVERY.md](PHASE3-DELIVERY.md)。
