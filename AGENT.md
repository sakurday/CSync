# CSync 项目交接说明

> 2026-10-31 后续变更：Windows UI 已使用 Wry/WebView2 + 官方 Material Web，Android 已有多主机管理；相关细节以 `docs/WINDOWS-WEBUI.md`、`docs/ANDROID-HOSTS.md` 为准。0.1.0 已新增两端设置中的关于界面、源自根 `icon_512.png` 的应用图标、Windows 文件版本资源及自动生成的开源依赖清单，详见 `docs/RELEASE-0.1.0.md`。下方早期原生设置窗口/单主机描述不再代表最新 UI。Android release 已可构建但尚未配置正式签名密钥。

更新日期：2026-10-31（Asia/Singapore）。供后续 AI 接手时先读，不必从头遍历全部代码。下文区分当前实现、历史验证和待确认事项；需要修改某个模块时再定向读取对应文件。

## 1. 用户目标与不可忽略的约束

- 项目是 Windows ↔ Android 双向纯文本剪贴板同步工具，强调小巧、低资源占用、后台无感同步。
- Android 10+ 用 Shizuku UserService 获取特权剪贴板访问；运行时有常驻系统通知。
- Windows 是服务器，支持托盘、关闭窗口后继续运行、登录后静默自启；Android 是连接客户端。
- 网络以局域网为主，也支持用户配置端口转发后通过公网 IP 连接；蓝牙 RFCOMM 只是备用通道。
- **配对只使用 IP + 端口。用户已明确要求删除扫码配对和链接配对，不要重新加回来。** 首次仍核对两端校验码并确认，之后自动重连。
- **电脑地址自动获取，只让用户配置监听端口。** 不要恢复 `advertised_host` 编辑框。手机可填写电脑地址和端口。
- Android 界面必须使用官方 Jetpack Compose Material 3 / Material 3 Expressive 组件，不能用手工绘制的普通控件模仿外观。
- Windows 默认配置已经按用户要求改成 **可执行文件旁的 `data` 子目录**；升级保留此目录。
- **不要修改根目录 `README.md`。** 用户已自行重写，末尾明确要求 `DO NOT edit README.md, i'll do it myself.`。旧会话曾编辑它；今后遵守当前用户约束。
- 未要求提交、推送、发布或上传；不要擅自做这些动作。不要覆盖用户手工改动。
- 本文件名是用户指定的 **`AGENT.md`（单数）**。新会话如未自动加载，应显式读取它；它不是工具通常自动发现的 `AGENTS.md`。

## 2. 本地环境与交付入口

- 工作目录：`C:\documents\csync`，PowerShell。当前已是 Git 仓库；本次写交接文档前 `git status --short` 为空。早期会话尚无 `.git`，不要沿用那个结论。
- 标准电脑入口：`dist/CSync.exe`。标准手机包：`dist/CSync-android-debug.apk`。
- `dist/data/` 是真实运行数据，包含本机身份和信任记录，**不是可分发的安装内容**。不要提交、打包上传或在日志中输出它的完整内容。
- 曾出现 `CSync-updated.exe`、`CSync-IP.exe`、`CSync-fix.exe`，它们造成用户误开旧版；已删除多余版本并统一默认入口。后续仍更新 `CSync.exe`，避免再留下多个不明版本。
- `.build/` 保存 SDK、Gradle 缓存、临时文件、测试服务器数据等；`target/` 是 Cargo 构建产物。`.gitignore` 已忽略这些和 `dist/`、Android build、keystore、日志等。
- 设备调试已获用户授权，可使用已连接实体 Android 手机和 ADB。历史设备：华为 OCE-AN50 / Android 12 / API 31，序列号 `RXD0221624004261`。每次先 `adb devices -l` 确认，不假设永远在线。
- 历史本机 LAN 地址是 `192.168.0.101`，另有虚拟网卡 `192.168.45.1`；它们不是固定常量。
- 手机应用 ID `io.csync.app`，测试包 `io.csync.app.test`；Shizuku 包 `moe.shizuku.privileged.api`。

## 3. 目录地图：按问题定位文件

```text
Cargo.toml / Cargo.lock          Rust workspace，版本 0.1.0，edition 2024
.cargo/config.toml              Windows MSVC 静态 CRT
crates/csync-core/src/
  protocol.rs                   消息类型、长度前缀 JSON、Clip 校验、协议常量
  security.rs                   证书指纹、Trust、TLS 客户端证书验证、配对窗口
  sync.rs                       有界事件去重与提交协调
crates/csync-windows/src/
  main.rs                       GUI 程序入口、启动失败提示
  ui.rs                         原生 Win32 设置窗口、托盘、配对确认、剪贴板消息线程
  server.rs                     TCP/TLS、配对、会话、广播、心跳、限流与设备撤销
  storage.rs                    配置、DPAPI 身份、便携目录迁移、原子保存、自启注册
  clipboard.rs                  Win32 文本剪贴板事件读写与回环抑制
  bluetooth.rs                  Windows RFCOMM / SDP 服务
  tests.rs                      TLS 集成测试、供 Android 使用的隔离测试服务器
  clipboard_tests.rs            独立窗口站内的真实 Windows 剪贴板测试
crates/csync-windows/
  app.manifest / build.rs        Common Controls v6、asInvoker、嵌入 manifest
android/app/src/main/java/io/csync/app/
  MainActivity.kt               Compose UI、两页导航、IP 配对对话框、校验码确认、授权入口
  SyncService.kt                前台服务、Shizuku 绑定、TCP/蓝牙重连、会话与通知
  ClipboardUserService.kt       Shizuku 特权进程内的剪贴板隐藏接口调用
  DeviceIdentity.kt             Android Keystore 身份、TLS KeyManager / TrustManager
  TlsStreams.kt                 TCP 与 RFCOMM 共用的 TLS 1.3 SSLEngine 流适配
  Transport.kt                  网络/蓝牙连接、IP 配对握手与错误反馈
  Protocol.kt                   JSON framing、hash 与 Clip 验证
  Settings.kt                   SharedPreferences、端点/状态/配对信息
  BootReceiver.kt               开机恢复入口
android/app/src/main/aidl/       自有桥接 AIDL 与剪贴板监听接口
android/app/src/main/res/        官方组件使用的矢量图标、主题、禁止备份规则
android/app/src/test/            ProtocolTest JVM 测试
android/app/src/androidTest/     后台同步测试、实际电脑配对回归测试、独立复制辅助 Activity
scripts/                        构建、SDK 初始化、防火墙、实机测试脚本
docs/PROTOCOL.md                 当前 IP 配对与会话协议说明
docs/SECURITY.md                 安全边界说明
docs/VALIDATION.md               旧验证记录；部分内容已过时，见下文
```

## 4. 当前连接与配对流程

默认 TCP 端口 **45876**。Windows 监听所有 IPv4 接口，并尝试独立 IPv6 listener；无需用户填写监听 IP。UI 通过 Windows `GetAdaptersAddresses` 枚举可用 IPv4 地址，默认路由地址优先，过滤回环等地址，最多保留 8 个；可能包含虚拟网卡。

首次配对：

1. 电脑点击“开始配对”，开启 120 秒窗口。手机输入电脑 IP 和端口。
2. 手机通过 TLS 1.3 建立连接，发送 `pair`（`version:1`、空 `token`、设备名）。未知客户端证书只在本地配对窗口内允许完成 TLS，仍校验私钥签名。
3. 双方分别计算 `SHA256(serverFingerprint + ":" + clientFingerprint)` 的前 12 个十六进制字符作为校验码。用户需核对一致。手机仅在首次配对连接中暂时接受未知服务器证书，不能把这种信任方式用于普通同步连接。
4. 电脑窗口确认后发 `pair_challenge`；手机确认后发 `pair_confirm`。双方确认顺序可以不同，数据由同一 TLS 连接交换。
5. 电脑成功持久化手机指纹后才回 `paired`。手机核对返回身份与 TLS 服务器证书一致，保存端点与服务器指纹，再启动同步服务。
6. 新同步连接使用精确证书钉扎和双向 TLS，发送 `hello` / 接收 `ready`；每次重连都认证。配对用的连接关闭本身是正常的，但配对没完成就 EOF 是错误。

配对请求会消费窗口；失败后通常需要电脑重新开启。电脑确认及手机最终确认各有约 60 秒等待限制；手机 UI 核对约 55 秒超时。蓝牙仅供已信任设备使用，不负责首次登记。

旧二维码/URI 已从生产 UI 和协议数据结构删除，ZXing 和 qrcode 依赖已删除，摄像头权限已删除。`security.rs` 中仍有旧令牌方法和对应单元测试；不要把它们误当成当前生产配对方式。剪贴板仍跳过 `csync://pair?` 前缀，是防止转发旧配对材料的遗留保护，不代表仍支持链接配对。

## 5. 同步实现要点与安全边界

- Rustls/ring 服务端；Android 自有 `SSLEngine` 流封装；TCP 与蓝牙都经过 TLS 1.3。不要改成裸 TCP 或普通同步时 accept-all 证书。
- 身份是完整 DER 证书的 SHA-256；Windows 自签 EC 证书私钥由当前 Windows 用户的 DPAPI 保护；Android P-256 私钥在 Android Keystore 中。
- 帧为 4 字节大端长度 + UTF-8 JSON，正文最大 256 KiB，帧最大 `256*1024*6+8192`；接收前先检查长度。仅纯文本，不含图片、文件、富文本或历史记录。
- 每次复制事件有 UUID、来源证书指纹、单调 sequence、内容 hash；服务器检查来源、顺序、大小和 hash。Coordinator 用有界 UUID 集合去重，重复复制相同文本但新事件仍是新复制。
- Windows 剪贴板在 UI 消息线程上应用，成功后提交并广播递增 revision；原发送端忽略自己的 commit。Android 保留有界待提交事件，防止旧远端消息覆盖更新的本地复制。
- 不保存正文历史、不在重连后自动覆盖当前剪贴板、不持久化离线正文队列。不要为调试打印真实剪贴板、证书私钥或全部配对配置。
- Shizuku UserService 使用单独 worker，调用隐藏 `IClipboard`，身份为 shell；监听事件而非轮询。不回退到悬浮窗抢焦点、无障碍抓取或日志抓取。
- Android 设置监听基线以避免刚注册就上传旧内容；用事件标记/hash/系统 timestamp 抑制回环及 ROM 重复通知。敏感标记文本会跳过。
- Android 心跳/退避重连、网络变动触发重试；蓝牙只有 TCP 都失败后且用户启用时尝试。前台通知只是 Android 后台运行条件之一，不能保证所有 ROM 永不杀进程。
- 电脑公网来源默认禁止，开启后仍需用户自己配置路由器端口转发与防火墙。没有云中继、UPnP 或自动打洞。不要默认把公网 IP 自动发现视为已实现。
- `scripts/configure-firewall.ps1` 用于显式配置入站规则；不要未经授权自动改防火墙。
- Windows 修改端口或蓝牙开关需完全退出后重启；公网开关立即影响新连接。UI 检查配置端口与运行端口，未重启时阻止生成错误配对请求。
- 自启写当前用户 Run 注册表并加 `--background`；默认不主动注册。普通关闭仅隐藏到托盘；托盘“退出”才结束进程。存在会话内单实例 mutex。

## 6. 最近故障、真正原因与最新修复（接手优先读）

用户连续反馈“两端核对校验码并确认后，手机立刻显示连接关闭”。最初 Windows 吞掉了连接错误，只看到手机 EOF；后来补充服务端错误状态，定位为：

```text
保存配对设备失败:
无法创建配置临时文件 C:\Users\sakurday\AppData\Local\CSync\settings.new:
拒绝访问。 (os error 5)
```

检查发现当前运行程序是 **低完整性级别**：读取进程 token 得到 `Integrity=S-1-16-4096`，而原 AppData 配置目录不允许它写入。不是 IP 输错、TLS 校验码不一致或已证实的网络故障。普通 DACL 中用户 FullControl 不等于低完整性进程也能写；单纯恢复 DACL 继承未解决问题。

曾申请 `icacls ...\CSync /inheritance:e`，自动审批先拒绝；用户后来明确批准仅恢复该目录继承，命令成功执行，但用户再次确认仍报错。没有修改父 AppData 目录、没有加 Everyone 写权限、没有再去修改完整性级别。**不要重复这个无效修复，也不要擅自扩大权限或要求管理员运行。**

用户随后提出把配置放在程序同目录，现已实现：

- `storage.rs::Store::open()` 默认 `current_exe().parent()/data`，不依赖工作目录；`--data-dir` 仍可覆盖，显式指定目录不自动迁移。
- 若默认 `data` 尚不存在且旧 AppData 下有 `identity.dpapi`，通过同级唯一 staging 目录整体复制身份和可选配置，再 rename 发布，避免混用身份。旧文件保留。
- 若 `data` 已存在，直接使用，不覆盖也不重新迁移；即使现有目录为空也不会迁移。迁移失败清理本次 staging 并报错，不能静默换身份。
- `atomic_write` 仍是 `.new` 临时文件、write、sync、关闭句柄、`MoveFileExW` replace；新增分阶段错误上下文。
- `server.rs` 保存失败现在发 `pairing_storage_failed`，再报告具体错误；Android `Transport.kt` 显示“电脑无法保存配对记录…”而非只有“连接关闭”。完整错误可在电脑状态栏查看。
- 已把新版放到唯一 `dist/CSync.exe`，已启动；实际出现 `dist/data/identity.dpapi` 与 `settings.json`，迁移后身份文件 hash 与旧文件一致，说明真实启动流程已成功在新目录创建/原子写入文件。
- 已更新并安装 Android debug APK（含上述错误提示），未清空用户应用数据。
- **最新会话结束时，用户尚未确认改成便携目录后的完整配对成功。** 下一次若继续排查，应先核对当前运行路径、`dist/data` 和实际错误，不要把“迁移/单元测试通过”说成“最终 GUI 配对已成功”。

`dist` 当前父目录确有 Low mandatory label，可供当前低完整性进程写入。这是本机环境事实，不能推断在任意目录（如 Program Files）也可写；便携程序应置于可写位置。

## 7. Android UI 与构建版本

- 官方 Compose `MaterialExpressiveTheme`，动态深/浅配色（Android 12+）、系统深浅主题、`Scaffold`、`LargeTopAppBar`、`NavigationBar`、Card/Button/Switch/TextField/AlertDialog。
- 两页“同步 / 设置”。保留开始/停止/暂停、IP 配对、端点保存重连、Shizuku 授权、系统通知/电池设置、备用蓝牙选择、解除配对。
- SharedPreferences 监听驱动 Compose revision 更新，服务状态反映到 UI；不是后台线程直接操作界面。
- Kotlin/Compose compiler 2.1.20；Compose BOM 2025.05.01；Material3 1.4.0-alpha18（Expressive 为实验性 API）；Activity Compose 1.10.1；Shizuku API/provider 13.1.5；coroutines 1.10.2。
- minSdk 29，compile/targetSdk 35，Java/Kotlin target 17。构建运行用 JDK 21。
- 当前 APK 是 **debug 包，约 58 MiB**。不要引用早期 4.47 MiB 数据，也不要把体积当作实际内存/耗电证据。release 配置有 minify，但未交付正式签名 release 包。

## 8. 构建、验证与交付命令

在项目根目录执行，使用已有脚本/本地依赖，避免无故重新下载 SDK。

```powershell
cargo fmt --all
cargo test --workspace --locked
cargo build --release --locked

.\scripts\gradle.ps1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 需要实机测试 APK 时另加：
.\scripts\gradle.ps1 :app:assembleDebugAndroidTest
```

- 本机 JDK 候选 `C:\Program Files\Android\openjdk\jdk-21.0.8`；SDK `.build/android-sdk`；Gradle 8.11.1 位于 `.build/gradle-8.11.1`；缓存 `.build/gradle-home`。`scripts/gradle.ps1` 会设置这些并使用代理环境配置。
- `scripts/build.ps1` 合并 Rust 检查、release 构建、Android 构建与复制；`-SkipAndroid` 可跳过手机。其脚本仍值得在修改前定向检查，不要假设它会生成/更新所有发布元数据。
- 沙箱下网络测试可能报 socket `os error 10013`，Gradle 可能无法连接本地 daemon。这是执行环境问题，必要时用合规的工具提权运行这些具体命令，不要立即修改网络业务代码。
- Windows 产物 `target/release/CSync.exe` → `dist/CSync.exe`；Android `android/app/build/outputs/apk/debug/app-debug.apk` → `dist/CSync-android-debug.apk`。
- 更新发布文件后更新 `dist/SHA256.json`（File、Bytes、SHA256）；**不包含 `dist/data`**。
- 正在运行的 exe 不能直接覆盖。先核对进程完整路径，仅退出本项目的实例，等待退出后复制；不要创建新名字的临时交付版让用户误开旧程序。
- 保留 runtime `data`，不因更新删除身份/配对。移动程序也应连同 data 移动；DPAPI 私钥仍绑定原 Windows 用户，不能直接跨用户/电脑复用。

最新已通过：Rust core 6 项 + Windows TLS 1 项 + 新增存储迁移 1 项，另 2 项 ignored；Android JVM 4 项、assembleDebug、lintDebug 通过。新增存储测试验证身份指纹/信任/端口迁移、旧文件保留、已有 data 不被覆盖及迁移后可保存。

## 9. 实机测试与验证边界

```powershell
adb devices -l
.\scripts\test-device.ps1 -LanHost 127.0.0.1
# 真 LAN 请使用当时实际可达的电脑地址；不要硬编码历史 IP
```

- 该脚本安装主/测试 APK，启动隔离的 Rust `android_device_server`（端口 45877），设置 USB reverse 备用并运行指定 `DeviceIntegrationTest`，以 `.build/device-server/device-result.json` 判断成败。
- 模拟服务端只在测试中自动批准配对；生产程序必须经真实 Windows 确认框。用例验证证书钉扎、Keystore TLS、中文/表情、后台剪贴板双向读写和无回环。
- **测试有副作用**：会修改手机 SharedPreferences、停止/启动服务并改写剪贴板。需要时先备份应用配置，测试后 force-stop 再恢复，清理临时备份；不要泄露其中指纹或设备数据。不可用生产配对配置冒充隔离测试配置。
- `PairingRegressionTest.actualDesktopConfirmation` 指定 `-e host <IP>`，连接真实电脑 45876，手机一侧测试自动确认，电脑仍需批准；成功会保存真实配对，再验证 hello/ready，不改剪贴板。此前未完成这条用例的成功验证，不能当成已有通过记录。
- 历史 2026-10-30 的旧界面/旧配对版本通过过真实 LAN（192.168.0.101）Android 后台端到端测试；2026-10-31 新 IP 协议通过过 **ADB reverse / 127.0.0.1 + 测试自动批准** 的实机后台同步。
- 最终真实 Windows GUI 确认 + 新便携存储方案的完整成功，仍待用户确认。独立 Win32 剪贴板测试需创建窗口站，之前 Access denied，默认 ignored；不要与“Android + 模拟桌面回调”的测试混为一谈。
- 尚未充分验证：公网路由/CGNAT/IPv6、蓝牙硬件互通、24h 资源/电量、长时间锁屏/Doze、各种 Android/ROM、真正注销登录自启、Explorer 重启恢复。
- `docs/VALIDATION.md` 仍是旧记录：旧 token 配对和旧 APK 体积描述已过时；当前交接事实以上文为准。`dist/README.md` 可能是此前技术文档副本，与用户维护的根 README 内容不同，不要为了同步而覆盖用户 README。

## 10. 后续协作注意

1. 先读本文件，针对任务查模块；确认 `git status`，不要把用户新改动覆盖掉。
2. 若用户说配对仍断开，先读取完整阶段错误、运行 exe 路径和默认数据目录；不要再只归咎地址、防火墙或重复让用户试同一修复。
3. 需要 Windows UI 控制时读当时可用的 computer-use 技能；之前旧 helper 出现过 `coordinate input geometry is unavailable`、焦点/截图不匹配，不能假装点击成功。用户曾按 Esc 停止控制，应遵守当次停止，不继续发送 UI 输入。
4. 权限/防火墙/安全设置修改不应凭猜测执行；已有用户批准范围只有上述 CSync 单目录恢复继承，不是修改任意目录/完整性/系统安全策略的授权。
5. 不要为修复错误清空 data、重生成证书或静默绕过证书钉扎。若确需重置身份，应明确解释需要重新配对。
6. 所有验证报告写明“普通单测 / 模拟服务端 / USB reverse / 真 LAN / 实际 GUI”的覆盖层级，不能拿旧结果声称当前所有路径通过。
