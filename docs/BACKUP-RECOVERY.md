# StayOS 数据库备份与隔离恢复

最新 V1.2 最终本地恢复演练比较 23 张业务表指纹一致，包含实际更正事实和完整加密前后快照；重启后原回执查询/重放通过。详见 [V1.2 交付记录](releases/V1.2-DELIVERY.md)。旧阶段时间与表数量仅为历史记录。

运营加固补充（待本次收口验证）：定时执行包装、最近成功/失败状态、密钥SHA256指纹校验、任务预览及健康检查见 [OPERATIONS-HARDENING.md](OPERATIONS-HARDENING.md)。这些脚本已提供；当前没有指定离机目标，也没有安装实际定时任务。旧归档不含指纹时，不能在严格指纹校验下冒充已确认的密钥身份。

本方案适用于当前 Windows、PowerShell 7、Docker Compose 和 PostgreSQL 17 开发环境。备份可读取开发库；恢复脚本只允许向测试容器中的全新隔离库恢复，不支持生产覆盖恢复。它不会覆盖开发库，也不会自动删除旧备份或旧数据库。第八阶段历史隔离实测记录为备份1.444秒、恢复1.002秒，21张表内容一致；完整条件与限制见根目录 `PHASE8-DELIVERY.md`，不代表本次验证结果。以下命令仅为操作示例，本次文档修正不执行。

## 1. 备份

先确认 Docker 正常运行。在项目根目录的 PowerShell 7 中使用现有备份任务包装脚本：

```powershell
$savedBackup = & .\scripts\run-backup-job.ps1 `
  -Container stayos-postgres-1 `
  -Database stayos `
  -OutputDirectory .\backups\archives `
  -StatusDirectory .\backups\status `
  -KeyEnvPath .\.env
$savedBackup | Format-List
```

必须明确指定容器与数据库。`StatusDirectory` 保存 `last-attempt.json`（最近结果）与 `last-success.json`（最后成功），失败不覆盖最后成功记录；`KeyEnvPath` 必须指向所选数据库原住客加密密钥的私有配置。包装脚本记录原密钥的SHA256指纹，但不备份密钥，也不自动证明该密钥可解密库内数据；包装脚本不等于已经部署的定时任务。

底层 `backup-database.ps1` 使用容器中的 `pg_dump -Fc --no-owner --no-privileges`，保存结构、数据、外键、索引、序列及 Flyway 记录；它不备份 PostgreSQL 集群全局角色、服务器配置、应用文件和加密密钥。

每次创建独立的“数据库名—UTC时间—随机标识”目录，里面是 `.dump` 和 `.dump.json` 清单。不会改写同名历史备份，也不会修改所选父目录的访问权限。新目录和文件的 Windows ACL 仅授予当前用户与 SYSTEM；容器临时归档写入前设置为 600，结束后清除临时副本。

脚本通过 `pg_restore --list` 检查归档，再校验文件长度并计算 SHA256，只有全部成功才返回 `Status=SUCCESS`。凭据不写入脚本、清单或输出。容器操作失败会终止并报告错误，不输出原始连接信息。

`backups/`、`.local/`、`.dump` 等已在 Git 忽略范围内。不要把归档改名为源码或移到公开目录；复制到其他介质后仍需重新检查访问权限。当前归档没有额外的归档加密，请使用受控、加密的存储介质，并另存一份离机副本。仅保留在同一电脑不能防止整机损坏。

## 2. 核对备份

```powershell
$backupFile = $savedBackup.BackupPath
$backupManifest = Get-Content -LiteralPath "$backupFile.json" -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash -ne $backupManifest.sha256) {
  throw '备份校验失败'
}
```

清单与归档应一起保留。SHA256 可以发现文件被意外改变，但不代表备份来自可信人员；仅恢复来源可信的备份。恢复脚本会再次核对格式、长度与摘要。

## 3. 在全新隔离数据库恢复

确保已有隔离测试容器 `stayos-postgres-test-1` 运行。它的数据库文件使用 tmpfs，容器/引擎停止后可能丢失；它适合演练，不能保存唯一备份。

```powershell
$restoreTarget = 'stayos_restore_drill_' + [guid]::NewGuid().ToString('N')
$expectedFingerprint = Read-Host '输入从独立可信记录核对的原密钥SHA256指纹（64位十六进制）'
& .\scripts\restore-database.ps1 `
  -Container stayos-postgres-test-1 `
  -Database $restoreTarget `
  -ConfirmTarget $restoreTarget `
  -BackupPath $backupFile `
  -ExpectedGuestKeyFingerprint $expectedFingerprint
```

主流程显式传入 `ExpectedGuestKeyFingerprint`，清单缺少指纹或与独立可信记录不匹配时拒绝恢复。此比对只核对清单中的指纹记录，不代替恢复后用原密钥验证数据，也不是防篡改签名。不要仅从同一份待验证清单读取期望指纹来冒充独立核对。

兼容旧归档的调用可以不传该参数，但仅检查归档格式、长度和SHA256，**未做密钥身份的严格指纹校验**；只能在另行授权的隔离演练中受控验证，不能补造旧清单指纹或声称已严格验证。

脚本同时要求：容器是 StayOS Compose 的 `postgres-test` 服务；库名以 `stayos_restore_` 开头；`ConfirmTarget` 完全匹配；目标数据库从未存在。`stayos`、`stayos_test`、开发容器和已有恢复库均不能作为覆盖目标；当前工具不支持生产覆盖恢复。

脚本先检查归档，再用 `createdb` 新建数据库，之后用 `pg_restore --single-transaction --exit-on-error` 恢复。不会执行 `--clean`、`DROP DATABASE` 或删除既有数据。恢复失败会明确报错；新建的空库可能仍然存在，保留用于排查，换新库名重试。不要为了让脚本继续而手动清空开发库。

归档中的表角色仍以业务表形式恢复；数据库对象所有者使用当前目标 PostgreSQL 用户。当前脚本仅适用于 Windows 上的 PowerShell 7 和现有 StayOS Compose 布局，不是通用生产灾备平台。

## 4. 加密密钥必须单独保管

住客证件和电话使用 AES-256-GCM，密文格式及 `guests.encryption_key_id` 当前标记为 `v1`，这不是唯一密钥身份。同一私有密钥还用于生成幂等请求的 HMAC 摘要。恢复时必须使用生成这些数据时的**原密钥**，不能重新生成一把代替。

- 数据库归档不包含 `STAYOS_GUEST_ENCRYPTION_KEY`；清单记录 `guestKeyId=v1`、`keyIncluded=false`，主流程生成的新清单还记录 `guestKeyFingerprint`（原密钥字节的SHA256指纹）。它不同于归档文件的 `sha256`；`format=StayOS/pg_dump/custom/v1` 则只是归档格式标识，都不是原密钥。
- 密钥应另存于受控密码管理或密钥保管介质，与数据库备份分开授权，记录用途、对应环境和备份批次；不要放在 Git、交付报告或普通日志里。
- `v1` 是当前密文格式标识，不是密钥本身的唯一指纹。当前没有多密钥轮换或自动判断“任意32字节密钥是否与旧数据匹配”的生产方案。
- 密钥为空或不是32字节 Base64 时，应用启动明确失败。格式正确但选错密钥，也必须在恢复验证阶段发现，不能直接恢复写入。
- 隔离演练用虚构证件验证正确测试密钥能够解密，错误测试密钥触发认证失败；解密只在测试代码中进行，没有新增完整号码查看接口。
- 页面原来保存的脱敏字段仍可能正常显示，这**不能证明**密文密钥匹配。不要仅看页面成功就宣布恢复完成。

开发库密钥仍由 Git 外的本地配置提供；上线前需落实独立密钥备份、保管人员、访问记录和轮换计划。备份文件完整但密钥丢失时，重新启动或重置密码无法恢复加密资料。

## 5. 恢复后的验收顺序

1. 比较关键业务表的数量、内容校验值、关联关系，以及序列后续分配是否正常。
2. 核对 Flyway 成功版本、外键、唯一约束、检查约束，确保没有关闭业务约束。
3. 使用对应环境密钥在隔离环境验证虚构敏感字段。开发或真实数据恢复必须有专门授权与受控验证流程。
4. 在独立端口和明确的恢复库连接上启动应用，先进行登录及只读查询，不替换正在使用的开发连接配置。
5. 查看入住、房间使用段、换房、清洁、异常、停用、审计和幂等关联。
6. 在隔离演练中通过业务接口验证后续操作，记录备份时间、恢复时间、核对结果和环境。

第八阶段自动测试会在 `postgres-test` 测试容器中创建 `stayos_restore_随机标识` 库，启动独立 Java 进程，验证重启、幂等、旧 Session 失效、测试容器暂停、查询超时、提交故障回滚及恢复后重试。故障用例会暂停整个测试容器，同容器内其他隔离库也会暂时不可用；因此测试须独占该容器，不与其他测试或恢复演练并行。整个过程不关闭开发数据库。

## 6. 常见失败与限制

| 现象 | 检查和处理 |
|---|---|
| Docker/容器不可用 | 检查 Docker Desktop 和明确指定的 StayOS 服务；不要重建或删除开发卷 |
| 备份工具失败 | 检查库名、用户和容器；确认数据库仍在线，不把连接凭据复制进聊天或报告 |
| ACL 设置失败 | 使用当前账号可管理的新备份目录；脚本明确失败，不能跳过文件保护后声称备份安全 |
| SHA256/长度错误 | 停止恢复，重新核对可信原始备份，不能修改清单让损坏文件强行通过 |
| 目标库已存在 | 选用新的隔离库名，不使用清库/覆盖作为默认修复方法 |
| Flyway 校验失败 | 核对备份与应用版本，不修改已执行迁移的内容来绕过校验 |
| 密钥错误/丢失 | 从独立保管位置取得原密钥，先验证匹配；不要运行生成新密钥脚本替代 |
| 重启后要求登录 | 当前 Session 在内存中；重新登录后查询原业务结果，不重复执行业务 |

尚未实现生产定时任务、离机传输、备份轮换删除、持续归档/WAL时间点恢复和生产RPO/RTO承诺。未来定时执行时可复用脚本，但须另外完成告警、访问控制、异地保管和定期恢复演练。当前脚本不会自动删除任何历史备份。
