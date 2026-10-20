# 第 1 阶段交付记录

验证日期：2026-10-19。本阶段在原有五份设计文档基础上创建工程，未更换既定技术路线，未开发入住、退房、换房、房间、清洁、收费或财务业务。

## 完成范围

- 1.1：Flyway 建立四张认证基础表，初始化五个固定角色；真实 PostgreSQL 约束验证通过后再进入 1.2。
- 1.2：登录、退出、当前用户、登录状态、Session/Cookie、CSRF、账号临时锁定、来源 IP 限流、接口权限检查、账号禁用及密码/角色变化撤销旧登录。提供管理员专属的账号基础管理接口。
- 1.3：Vue 登录页面、五类工作区、权限导航、路由守卫、登录失效处理和基础响应式布局；TypeScript 与生产构建通过后再进入 1.4。
- 1.4：真实 PostgreSQL、真实 HTTP 服务、真实 Microsoft Edge 浏览器联调；修正快速切换工作区时标签动画重复和手机标志遮挡问题，最终复验通过。

没有固定默认管理员，也没有自动插入开发账号。首个管理员采用用户确认的一次性终端初始化方式；已有管理员（包括禁用管理员）后禁止重复初始化。

## 主要文件

| 文件或目录 | 作用 |
|---|---|
| `backend/pom.xml` | Spring Boot 3.5.16、Java 21、MyBatis-Plus 3.5.17、Security、Flyway、PostgreSQL 驱动与测试依赖 |
| `backend/src/main/resources/db/migration/V1__authentication.sql` | 用户、角色、关联、操作记录及约束 |
| `backend/src/main/resources/db/migration/V2__session_revocation.sql` | 状态、密码与角色变化时更新安全版本，撤销旧会话 |
| `backend/src/main/java/com/stayos/auth/` | 登录、权限映射、会话校验、限流、工作区接口、管理员初始化 |
| `backend/src/main/java/com/stayos/staff/` | MyBatis-Plus 用户模型、Mapper 和管理员账号接口 |
| `backend/src/main/java/com/stayos/audit/` | 成功、失败及拒绝操作记录，不保存凭据 |
| `backend/src/main/java/com/stayos/common/` | 统一响应与错误处理 |
| `backend/src/main/resources/application*.yml` | 本地配置、Cookie、30 分钟空闲过期，以及 prod 的 Secure Cookie |
| `frontend/src/api/client.ts` | 同站点请求、CSRF、错误与 401 通知 |
| `frontend/src/stores/auth.ts` | 内存登录状态，凭据不保存到 localStorage |
| `frontend/src/router/` | 权限导航、工作区定义与路由守卫 |
| `frontend/src/views/`、`layouts/` | 登录、五角色共享基础工作区、无权限与连接失败页面 |
| `deploy/compose.yaml` | 独立的 stayos 开发库与测试库 |
| `scripts/` | 配置生成、环境载入、启动、初始化及测试命令 |
| `backend/src/test/`、`frontend/e2e/auth.spec.ts` | 数据库、认证、并发初始化、限流与浏览器验收 |
| `README.md`、原有四份 docs 文档、`AUTHENTICATION.md` | 同步实际实现、角色编码与已确认决定 |
| `.gitignore`、`.gitattributes` | 排除机密及构建产物，统一文本换行 |

实际环境：PostgreSQL 17.11（`postgres:17-alpine` 镜像）、Node.js 24.19.0、Maven 3.9.16、JDK 21.0.12.1。前端依赖版本以 package-lock.json 为准。

## 数据表

| 表 | 用途 |
|---|---|
| `users` | 用户名唯一、BCrypt 密码哈希、启用状态、安全版本、失败次数及临时锁定时间 |
| `roles` | 五个固定系统岗位 |
| `user_roles` | 一人多角色，组合主键和外键防止重复或无效关联 |
| `audit_logs` | 登录、退出、拒绝访问和账号维护的动作摘要 |

另外 `flyway_schema_history` 由 Flyway 自动管理迁移版本，不是业务表。权限映射集中在后端 `RolePermissions`，无需额外动态权限表。后续增加业务权限时在统一映射及接口上扩展，并补权限测试。

## 五角色实测结果

以下为每个角色对五个工作区接口的真实 HTTP 访问结果。允许为 200，拒绝为 403；未登录访问均为 401。

| 单独持有的角色 | 管理员 | 总经理 | 前台 | 客房主管 | 客房服务员 |
|---|---|---|---|---|---|
| ADMIN | 200 | 403 | 403 | 403 | 403 |
| HOTEL_MANAGER | 403 | 200 | 200 | 200 | 200 |
| FRONT_DESK | 403 | 403 | 200 | 403 | 403 |
| HOUSEKEEPING_MANAGER | 403 | 403 | 403 | 200 | 200 |
| HOUSEKEEPING_STAFF | 403 | 403 | 403 | 403 | 200 |

多角色实测：FRONT_DESK + HOUSEKEEPING_STAFF 可进入前台与服务员区域，不能进入管理员或主管区域。总经理导航可连续访问前台、客房主管和服务员区域，无须重新登录，Session ID 保持不变。

## 已执行测试

最终命令（PowerShell 中带点的 -D 参数需整体加引号）：

```powershell
. .\scripts\env.ps1
mvn -B -f backend/pom.xml '-Dstayos.e2e=true' package
npm --prefix frontend run build
```

平时可直接运行 `scripts/test.ps1 -Browser`，脚本会按正确方式传递参数并启动测试数据库。

最终 JUnit 结果：**29 项，失败 0、错误 0、跳过 0**。其中包含一个启动浏览器测试的入口，不应将它与浏览器场景重复统计为独立业务场景。

- AuthIntegrationTest：22 项（含五角色参数化测试及浏览器入口）。
- BootstrapTest：3 项，首次初始化、已有管理员保护、并发只成功一次、无效密码不留半成品。
- IpRateLimitTest：1 项，来源 IP 超限返回 429，伪造 X-Forwarded-For 无法绕过。
- SchemaTest：3 项，迁移重复执行、五角色、用户名/关联约束以及数据库拒绝明文密码格式。

Playwright：**13 个场景通过，失败 0、跳过 0、重试 0**。覆盖五角色导航、总经理连续访问、多角色、错误提示、刷新/退出、服务员越权、另一窗口撤销后的重新登录提示、禁用后拒绝以及手机布局。

用户要求的 12 项验收均已实际覆盖：正确密码、错误密码、不存在账号、禁用账号、未登录、前台访问管理员、服务员访问前台、总经理访问前台/客房、总经理访问管理员被拒、退出后旧 Cookie 无效、过期处理、密码只保存哈希。过期测试使用真实 Servlet Session，将该测试会话空闲时限缩短到 1 秒，等待后验证 401；没有实际等待 30 分钟。

其他结果：前端 TypeScript + Vite 生产构建通过；后端可执行 JAR 打包通过；npm 安装时依赖审计报告 0 漏洞（此结论只对应本次执行时间）。已查看桌面登录、手机登录和总经理导航截图。非交互式运行打包后的管理员初始化程序被正确拒绝，开发库仍为 0 个用户。

测试过程曾出现两项失败，均已修正：PowerShell 拆分未加引号的点号参数，未启动测试；工作区状态标签动画导致浏览器定位到两个相同标签。最终结果是修正后的重新执行结果。

原始证据：`backend/target/surefire-reports`、`.local/playwright-results.json`、`.local/browser-run.log`；这些运行产物不提交 Git。

## 登录操作与当前边界

启动及首次管理员创建命令见根 README。打开 http://127.0.0.1:5173，输入你在终端初始化的账号和密码。登录后按角色进入工作区，右上角“退出登录”。账号管理接口已完成；账号管理表单尚未提供，本阶段前端按任务要求只提供登录与基础工作区。

Session 是服务器保存的登录登记，浏览器只持有随机 Cookie；密码经过 BCrypt 比较后不再保留到会话。空闲 30 分钟、退出或服务重启后失效；禁用、重置密码和角色变化使旧会话在下一次请求失效。登录、退出和其他写请求需要 CSRF 校验。

**尚未执行或保留事项：**

- 未替用户创建真实开发管理员；真实终端的隐藏输入由用户亲自执行，内部初始化逻辑、并发保护及非交互拒绝已验证。
- 未进行真实生产部署、HTTPS 代理联调、多个后端实例共享 Session、生产备份恢复或压力测试。本阶段是本地单体开发环境；部署时需受限数据库账号和可信入口代理限流。
- Git 仓库已初始化，文件可查看差异；尚未创建 Git 提交。
- 账号管理表单、历史查询界面及酒店业务均未提前实现。没有发现阻塞本阶段认证核心功能的未解决故障。
- 单酒店/楼栋、住客范围、历史房号修改、业务幂等保留策略等原未决事项仍保留，后续触及时按 PRD 第 9 节暂停等待用户反馈。

## 下一步建议与概念解释

下一阶段先实现房型、房间基础资料及房态查询，复用当前登录和接口权限。建房间表之前先确认单酒店、楼栋和楼层范围；尚未确认的事项不能默认为通过。也可先补管理员的账号管理表单，让后续角色测试更方便。

- **身份认证**：确认“你是谁”，例如核对账号密码并建立登录登记。
- **角色**：岗位标签，例如前台、客房主管；同一个人可以兼任多个岗位。
- **权限**：允许做的具体事情，例如进入前台区域或管理员工账号；多个角色的权限合并。
- **Spring Security**：后端的门卫，负责恢复登录身份、CSRF 校验、退出及执行访问规则；密码与锁定策略由项目服务配合实现。
- **为什么前端不能代替后端**：用户可以绕过菜单，直接构造接口请求。后端必须自己检查身份和权限，才能真正保护数据库与业务操作。
