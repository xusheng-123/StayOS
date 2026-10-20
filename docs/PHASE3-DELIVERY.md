# StayOS 第 3 阶段交付：入住管理

验收日期：2026-10-20。本记录以实际运行结果为准，适用于当前本地单体开发环境。

## 实际完成

- 可入住房间分页、房号/楼层/房型筛选、空结果提示；提交时仍由后端重新验证。
- 登记全部实际住客，指定一名主要联系人；每位姓名、证件类型、证件号必填，电话/备注选填。人数来自登记名单，不从客户端另一个数字字段读取，也不将房型建议人数作为强制容量限制。
- 正式办理入住，服务器生成实际入住时间和经办人，校验带时区的预计退房时间。
- PostgreSQL 事务、房间行锁、同房/同入住当前段部分唯一索引；处理不同请求争抢房间及同一请求并发重试。
- 24 小时成功请求防重、内容不一致拒绝、过期旧标识拒绝、失败回滚后可重试。
- 当前入住分页、授权住客姓名/房号/时间查询、历史入住查询、脱敏详情。
- 接入真实房间使用记录计算占用及使用历史。入住后房态和统计重新查询即反映占用；清洁状态保持原值。
- 已入住或有历史房间的普通改号限制由后端真实生效；有占用时不能停用。
- 五角色权限、后端拒绝越权、前端导航和路由控制；总经理默认没有管理员权限，管理员默认不办理入住。
- 证件/电话加密、仅脱敏查询、成功审计与入住同事务、错误不回显完整敏感资料。
- 新增入住登记、当前/历史列表、详情页面，沿用原有风格，桌面/手机基本布局可用。

没有开发退房、换房、清洁执行/验收、维修工单、收费、预订或外部身份核验业务。

## 主要文件

| 文件/目录 | 用途 |
| --- | --- |
| backend/src/main/resources/db/migration/V4__check_in.sql | 五张新表、当前占用与历史事实视图 |
| backend/src/main/resources/db/migration/V5__stay_closed_state_constraints.sql、V6__segment_end_pair.sql | 已执行 V4 后通过增量迁移补强空值与结束状态约束 |
| backend/src/main/java/com/stayos/stay/dto/ | 登记、筛选、脱敏返回数据 |
| backend/src/main/java/com/stayos/stay/mapper/StayMapper.java | 参数绑定 SQL、防重记录及业务关联 |
| backend/src/main/java/com/stayos/stay/service/CheckInService.java | 入住事务、并发与防重 |
| backend/src/main/java/com/stayos/stay/service/GuestProtection.java | AES-256-GCM 随机加密、脱敏、带密钥摘要 |
| backend/src/main/java/com/stayos/stay/service/StayQueryService.java | 一致快照内查询与详情 |
| backend/src/main/java/com/stayos/stay/controller/StayController.java | 五个入住接口及业务权限检查 |
| backend/src/main/java/com/stayos/auth/SecurityConfig.java | 纳入受保护的入住路径 |
| backend/src/main/java/com/stayos/common/ApiErrors.java | 数据库占用冲突转为中文业务提示 |
| backend/src/main/resources/application.yml、.env.example、scripts/init-guest-key.ps1 | 从私有环境加载或生成住客加密密钥 |
| backend/src/test/java/com/stayos/StaySchemaTest.java、StayIntegrationTest.java、StayTestFacts.java | 真实 PostgreSQL、HTTP、并发、失败回滚与浏览器测试 |
| backend/src/test/java/com/stayos/RoomIntegrationTest.java | 测试结束恢复新的真实占用视图，保留既有测试含义 |
| frontend/src/types/stay.ts、views/CheckInView.vue、StayListView.vue、StayDetailView.vue、stays.css | 入住类型、页面与响应式样式 |
| frontend/src/router/index.ts、layouts/WorkspaceLayout.vue、main.ts | 页面接入、独立入住导航与样式加载 |
| frontend/e2e/stays.spec.ts | 10 个真实浏览器场景 |
| docs/CHECK-IN.md、PRD.md、DATABASE.md、API.md、DEVELOPMENT.md、ROOMS.md、根 README.md | 同步用户确认规则、接口和运行说明 |

## 数据库

V1—V3 文件哈希保持不变，没有重建/清空数据库。V4—V6 在隔离测试库及开发库均成功执行；重复迁移测试通过。

| 新表 | 关键字段/约束 |
| --- | --- |
| guests | 姓名、证件类型、加密证件/电话、脱敏值、密钥标识、备注、创建时间；每次登记一份快照 |
| stay_records | 实际入住、预计/实际退房、状态、经办人、备注、时间戳；预计退房晚于入住；结束状态与退房字段一致 |
| stay_guests | 入住 ID、住客 ID、是否主要联系人、顺序；关联及顺序唯一，最多一个主要联系人，业务层要求恰好一个 |
| stay_room_segments | 入住/房间 ID、进出时间、结束原因、房号/房型快照；同房及同入住各最多一段未结束使用；结束时间/原因须成对 |
| idempotency_requests | 操作人、操作、UUID、HMAC 摘要、成功入住 ID、建立/过期时间；操作人+操作+标识唯一，过期不自动删除 |

关联通过外键限制删除。没有增加收费表。room_operational_facts 的占用/历史改为查询真实使用段；异常和清洁任务尚未实现，对应事实仍按阶段边界为空，后续需增量接入。

## 一次入住如何完成

1. 浏览器查询后端，只列符合统一条件的房间，登记全部住客。
2. 提交房间内部 ID、住客名单、预计退房时间，附 UUID 请求标识和现有 CSRF 校验。
3. 后端检查当前员工的 stay:manage 权限及输入；经办人取登录身份。
4. 同一事务内竞争请求标识，重复请求等待首次操作结果；新请求锁定房间行。
5. 重新读取 room_status：启用、无人占用、CLEAN、无阻止异常、无未完成任务必须同时满足。
6. 服务器记录时间，保存入住、加密住客、住客关联、第一段房间使用、成功审计和防重结果。
7. 一起提交；任何一步失败则一起回滚。唯一索引作为最后防线，不能绕过锁和业务校验。
8. 页面展示脱敏详情，房态图重新查询后显示已入住；不额外手改占用，不把清洁状态改为 DIRTY。

网络结果不确定时前端冻结原表单，保留原请求在内存中重试。浏览器持久保存的只有随机请求标识，没有住客草稿；刷新后要求先查询记录核对结果。

## 接口和页面

后端均为 /api 前缀、统一 JSON、Session 和 CSRF：

| 方法和路径 | 作用 |
| --- | --- |
| GET /api/stays/available-rooms | 可入住房间分页及条件筛选 |
| POST /api/stays | 正式办理入住，必须带 Idempotency-Key |
| GET /api/stays | 默认当前入住，允许明确状态筛选 |
| GET /api/stays/history | 已结束入住分页 |
| GET /api/stays/{id} | 脱敏详情 |

完整参数、返回字段、错误码和时间规则见 CHECK-IN.md。没有为了历史展示提供虚假退房接口。

打开 http://127.0.0.1:5173，用现有前台或总经理账号登录，通过“入住功能”导航访问：

- /stays/new：办理入住。
- /stays：当前入住。
- /stays/history：历史入住。
- /stays/{id}：详情。

现有 80 间房仍待打扫，所以开发页面显示“暂无可入住房间”是正确结果。正常入住测试在 stayos_test 库创建少量清洁合格房间，并在结束后清理，不更改开发房间。

## 权限实测

| 单独角色 | 房态查询 | 入住/可入住房间/住客详情 | 管理员接口 |
| --- | --- | --- | --- |
| ADMIN | 200 | 403 | 200（原有回归） |
| HOTEL_MANAGER | 200 | 200 | 403 |
| FRONT_DESK | 200 | 200 | 403 |
| HOUSEKEEPING_MANAGER | 200 | 403 | 403（原有回归） |
| HOUSEKEEPING_STAFF | 200 | 403 | 403（原有回归） |

未登录访问入住接口 401；越权在后端拒绝。前端导航/路由同时验证。原有多角色权限合并测试通过。开发库五个原测试账号另外真实登录、查询房态/入住权限、退出均通过；未办理开发库入住。

## 实际测试结果

最终执行：

```powershell
. .\scripts\env.ps1
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' package
npm --prefix frontend run build
```

**JUnit：75 项，失败 0、错误 0、跳过 0。** 其中 3 项是启动浏览器套件的入口，不与浏览器场景重复累计为独立业务场景。

| 测试类 | 通过数量 |
| --- | --- |
| AuthIntegrationTest | 22 |
| BootstrapTest | 3 |
| IpRateLimitTest | 1 |
| RoomIntegrationTest | 16 |
| RoomSchemaTest | 8 |
| SchemaTest | 3 |
| StayIntegrationTest | 14 |
| StaySchemaTest | 8 |

**Playwright / Edge：34 个场景通过，失败 0、跳过 0、重试 0。** 认证 13、房间 11、入住 10。前端 TypeScript/Vite 构建、后端可执行 JAR 打包通过。

关键验证结果：

- 正常入住及多人关联真实写入 PostgreSQL，主要联系人恰好一人。
- 不存在、待打扫、清洁中、待检查、档案停用、阻止异常、未完成任务及已占用房间拒绝入住。
- 预计退房非法、缺时区、重复住客、无主要联系人、无请求标识等请求被拒绝。
- 两个事务争同一房间只成功一次；两个真实登录 HTTP 客户端同时提交得到 200、409。
- 同一标识并发提交返回同一入住 ID；内容不同/已过期拒绝；失败标识随事务回滚。
- 测试库注入审计写入故障，住客、关联、入住、使用段、防重全部回滚；删除故障夹具后原标识可重试成功。
- 浏览器模拟后端成功但响应被截断，使用相同请求标识重试，只生成一条入住。
- 浏览器多人提交后，详情脱敏、当前列表按同行姓名找到记录、房态变已入住、清洁仍 CLEAN、统计同步变化；随后直接 JDBC 核对数据库。
- 测试库构造已结束记录验证历史查询，不在开发库伪造退房。
- 实际入住产生后普通改房号/停用被拒绝，其他备注可正常维护。
- AES 密文随机化、使用正确测试密钥可恢复原值、脱敏及密钥缺失拒绝均通过；普通房态查询和审计不包含测试完整证件/电话。
- 桌面登记、脱敏详情和手机截图已查看，手机无整页横向溢出。

早期失败已修复并重新运行：测试房型编码误用小写、页面漏导入组件、多个证件下拉框的测试定位不够明确。上述通过数字来自最后完整重跑，不将初次失败算为通过。

证据（均为本地运行产物，不提交 Git）：

- .local-phase3-final.log、backend/target/surefire-reports。
- .local/playwright-results.json、playwright-rooms-results.json、playwright-stays-results.json。
- .local/stay-browser-run.log、stay-check-in.png、stay-detail.png、stay-mobile.png。
- .local/phase3-before.json、phase3-after.json、phase3-historical-migrations.json。
- 开发库变更前备份 .local/stayos-before-phase3.sql；备份包含受保护账号数据，不应公开。

## 既有功能与剩余边界

更新后的后端已在本地 8080 启动，前端 5173 可访问。Flyway V1—V6 全部成功。迁移前后逐行比较房间/房型资料及账号指纹一致，五角色和角色关联数量不变；V1—V3 文件哈希不变。开发库仍为 6 种房型、80 间房、全部待打扫、0 条入住、0 条住客。测试夹具已清理，测试事实视图已恢复真实占用来源。

原登录、退出、权限、账号、房型、房间、房态功能回归通过。后端重启会结束内存 Session，需重新登录，这是原有认证方式的行为。未创建 Git 提交，也未更换技术栈或依赖。

没有发现阻塞本阶段功能的未解决故障。以下未执行或留待后续：

- 未做生产 HTTPS 部署、压力测试、密钥轮换或备份恢复演练；备份已生成但未验证恢复。
- 24 小时过期用测试库调整时间验证，没有真实等待 24 小时。
- 没有处理真实个人信息。真实试用前须暂停确认个人信息保留/清理期限、身份核验与部署要求。
- 全部房间仍待清洁。清洁任务尚未开发，不开放临时改房态接口来规避条件。
- 备注由用户输入，明确提示不要写完整证件/电话；系统不承诺能自动识别并隐藏备注里的所有个人信息。

## 第 4 阶段建议

实现退房时复用当前入住和房间使用段，不删除历史；请求带当前段 ID 防止旧页面操作。先锁入住、再锁房间，原子结束入住/当前段、标记 DIRTY、自动生成唯一来源清洁任务、写防重及审计。退房与换房互斥，重复请求不能重复建任务。先核对退房与自动清洁任务的阶段边界和请求防重规则，再开发；不加入任何结算、押金或收款条件。

## 初学者概念说明

- **为什么入住记录独立于房间？** 房间像一间固定教室，入住像某次使用登记。教室只有一间，使用记录会有很多次；分开保存才能保留历史，也为以后换房留出位置。
- **为什么同一房间不能有两条有效入住？** 否则系统会把同一间房同时交给两组住客。数据库只限制未结束的使用段，允许保存许多已结束记录。
- **事务是什么？** 把几步数据库修改当成一个整体：入住、住客、占用和成功日志一起成功；中途失败，已做的修改也撤回，避免留下半份登记。
- **并发冲突是什么？** 两个人几乎同时办理同一间房。行锁让冲突操作排队，后来的操作重新检查，发现房间已占用便拒绝；唯一索引再防止重复占用漏网。
- **前端查过为何后端还查？** 从看页面到点击提交之间，别人可能已经入住；请求也可能绕过网页直接发送。最终写数据库前必须再检查。
- **为何不能改房态代替入住？** 把标签改成“已入住”没有登记客人、时间、经办人及历史，退房和换房也找不到对应记录。这里的房态是从真实业务记录计算出来的结果。
- **外键是什么？** 一种关联检查，例如入住使用段的房间 ID 必须指向真实房间，防止引用不存在的记录。
- **唯一约束是什么？** 数据库保证某种组合不能重复，例如同一房间只能有一段未结束使用。它在多人同时操作时也生效，比前端查重更可靠。
