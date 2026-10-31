# CSync：给接手 AI 的项目说明

更新时间：2026-10-31，Asia/Singapore。本文件按当前代码重写，优先描述现状，而不是历史对话。先读“快速接手”和“用户约束”，之后按任务定位模块；无需重新通读整个项目。

## 1. 快速接手：一分钟了解现状

- **产品**：Windows 与 Android 双向同步纯文本剪贴板，核心是低开销、后台无感同步。Windows 充当服务器，Android 充当客户端，默认 TCP 端口 **45876**。
- **当前版本**：两端 **0.1.0**；Android `versionCode=1`；协议版本独立为 `1`。
- **Windows**：Rust/Win32 负责后台、剪贴板、托盘和服务器；设置界面采用 **Wry + Edge WebView2 + 官方 Material Web**，已不是早期纯 Win32 控件界面。
- **Android**：Kotlin + 官方 **Jetpack Compose Material 3 Expressive**；Shizuku UserService 监听和读写后台剪贴板，前台服务显示常驻通知。
- **配对**：只用 IP + 端口，双方核对校验码；**不支持也不要恢复二维码、扫码或链接配对**。
- **多主机**：Android 设置页可添加、编辑、删除、拖动排序；按优先级连接，TCP 全部失败后才尝试用户配置的蓝牙备用连接。
- **数据位置**：Windows 默认使用 **`CSync.exe` 旁的 `data/`**，保留旧 AppData 身份迁移逻辑。升级绝不能删除 data。
- **交付**：`dist/CSync.exe`、`dist/CSync-android-debug.apk`、`dist/CSync-android-0.1.0-unsigned.apk`。最后一个经过 R8，但**尚未签名，不能直接安装/正式发布**。
- **关于界面**：两端设置内均有“关于 CSync”，显示构建版本、图标、开源依赖；介绍必须且仅为 **“在手机和电脑间同步剪贴板”**。
- **图标**：唯一源图为根目录 `icon_512.png`；Windows EXE/窗口/托盘及 Android 启动/关于/通知图标均由它派生。
- **验证边界**：LAN 配对和 Android 后台同步有实机成功记录；公网、蓝牙硬件、长期功耗仍未充分验证。0.1.0 最新改动通过构建/测试及关于页检查，不等于重新覆盖全部端到端场景。
- **当前后续重点**：若要正式发布 Android，需配置开发者长期发布签名密钥。不要用 debug 密钥冒充正式签名，不要为了换签名卸载并清空用户数据。

## 2. 用户约束与协作原则

1. **不要编辑根 `README.md`**：用户自行维护，文件末尾有明确要求。`README/` 下的翻译也不是自动同步的目标，除非用户要求。
2. 电脑端地址自动获取，只让用户配置监听端口；不要恢复手填“对外地址”字段。手机端输入目标 IP/域名和端口。
3. Android 必须用官方 Compose Material 组件，不得用自制普通控件仿外观；Windows 保持现有官方 Material Web 组件与 Rust 后台分工。
4. 产品范围是文本剪贴板同步。不要擅自增加历史记录、云中继、图片/文件同步、遥测或剪贴板日志。
5. 不输出真实剪贴板、私钥、完整用户配对配置。不要把运行中的 `dist/data/` 或 WebView profile 放进发布包。
6. 修改前看 `git status`，保护用户及其他会话的改动；不要擅自提交、推送、发布或替换 README。
7. 只维护一个默认电脑交付入口 `dist/CSync.exe`。曾因 `CSync-IP.exe`、`CSync-updated.exe` 等重复版本误开旧 UI，这些文件已清理，不要重演。
8. 用户已授权使用连接的 Android 实机和 ADB 调试。测试仍应说明副作用并保留配对数据；Windows UI 自动化需使用当时可用的 computer-use 技能，用户停止控制后停止当次 UI 输入。
9. 本文件是用户指定的 **AGENT.md（单数）**，不是通常由工具自动加载的 AGENTS.md；新会话若未自动读取，应显式打开本文件。

## 3. 环境和工具入口

| 项目 | 当前信息 |
|---|---|
| 工作目录 | `C:\documents\csync`，PowerShell，Git 仓库 |
| Rust | workspace edition 2024；MSVC；`.cargo/config.toml` 启用静态 CRT |
| Android SDK | `.build/android-sdk`，minSdk 29，compile/targetSdk 35 |
| JDK | 脚本优先 `C:\Program Files\Android\openjdk\jdk-21.0.8`，字节码目标 Java/Kotlin 17 |
| Gradle | `.build/gradle-8.11.1`，缓存 `.build/gradle-home` |
| Kotlin / Compose compiler | 2.1.20 |
| Android UI | Compose BOM 2025.05.01；Material3 1.4.0-alpha18（Expressive 实验性 API）；Activity Compose 1.10.1 |
| Shizuku / coroutines | API 与 provider 13.1.5；coroutines 1.10.2 |
| Windows UI | Wry 0.57.1；系统 WebView2 Evergreen Runtime；`@material/web` 2.5.0，esbuild 0.28.2 |
| 应用包名 | `io.csync.app`；测试包 `io.csync.app.test` |
| 历史调试设备 | 华为 OCE-AN50，Android 12 / API 31，ADB 序列号 `RXD0221624004261` |
| 历史 LAN 地址 | `192.168.0.101`，另有虚拟网卡；每次重新确认，不要硬编码 |

SDK、工具缓存和构建产物均已在本地，不要无故重新下载。先 `adb devices -l` 确认设备是否仍在线。版本或库升级前，以实际构建配置与锁文件为准。

## 4. 代码地图：按任务定向读取

| 路径 | 职责 / 何时阅读 |
|---|---|
| `Cargo.toml`、`Cargo.lock` | Rust 版本、依赖、release 优化配置 |
| `crates/csync-core/src/protocol.rs` | 协议枚举、长度前缀 JSON、Clip 与字段验证、大小限制 |
| `crates/csync-core/src/security.rs` | 证书指纹、信任集合、配对窗口与 rustls 客户端证书验证 |
| `crates/csync-core/src/sync.rs` | 事件去重和服务器提交协调 |
| `crates/csync-windows/src/ui.rs` | Win32 主循环、WebView 生命周期/IPC、托盘、窗口、配对确认、剪贴板线程回调 |
| `crates/csync-windows/src/server.rs` | TCP/TLS、登记/会话、限流、广播、心跳、撤销设备、连接错误反馈 |
| `crates/csync-windows/src/storage.rs` | portable data、旧配置迁移、DPAPI 身份、原子保存、登录自启注册 |
| `crates/csync-windows/src/clipboard.rs` | Windows 文本剪贴板监听/读写、回环抑制 |
| `crates/csync-windows/src/bluetooth.rs` | Windows RFCOMM / SDP 服务 |
| `crates/csync-windows/src/tests.rs` | TLS 集成测试、供 Android 使用的隔离测试服务器 |
| `crates/csync-windows/src/clipboard_tests.rs` | 独立窗口站里的 Win32 剪贴板测试，当前默认 ignored |
| `crates/csync-windows/build.rs` | 嵌入 manifest、ICO 和 Windows FileVersion / ProductVersion；需要 SDK `rc.exe` |
| `crates/csync-windows/web/index.html` | Windows 布局、CSS、关于页及资源注入占位符 |
| `crates/csync-windows/web/app.js` | 官方 Material Web imports、页面导航、状态渲染、IPC 操作 |
| `crates/csync-windows/web/bundle.js` | 已生成、需随源码维护的前端 bundle；不是手工修改目标 |
| `android/.../MainActivity.kt` | Compose 同步/设置页、配对/主机编辑、关于对话框、系统授权入口 |
| `android/.../HostList.kt` | 主机列表 UI、拖动手柄排序、无障碍移动操作 |
| `android/.../HostProfile.kt` | 每台主机的独立端点、证书身份、名称、蓝牙地址及 JSON 编解码 |
| `android/.../Settings.kt` | SharedPreferences、新旧主机数据迁移、状态与用户开关 |
| `android/.../SyncService.kt` | 前台服务、通知、Shizuku 绑定、主机选择、连接/握手取消、网络事件、同步会话 |
| `android/.../ReconnectBackoff.kt` | 分级退避、稳定会话重置、事件打断等待 |
| `android/.../ClipboardUserService.kt` | Shizuku 特权进程调用 Android 隐藏剪贴板接口，事件读写与去重 |
| `android/.../DeviceIdentity.kt` | Android Keystore 身份与 TLS KeyManager / TrustManager |
| `android/.../Transport.kt`、`TlsStreams.kt` | TCP/RFCOMM、IP 配对、TLS 1.3 SSLEngine 流适配与超时 |
| `android/.../Protocol.kt`、`BootReceiver.kt` | Kotlin 协议实现、开机恢复入口 |
| `android/app/src/main/aidl/` | 自有 Shizuku 桥接与剪贴板监听 AIDL |
| `android/app/src/test/` | 协议、主机配置、退避等 JVM 单元测试 |
| `android/app/src/androidTest/` | 后台同步、主机设置/拖动、实际电脑配对回归与独立复制辅助 Activity |

上表的 `android/.../` 均指 `android/app/src/main/java/io/csync/app/`。

## 5. 连接、配对和同步协议

### IP 配对

1. Windows 默认监听所有 IPv4 接口并尝试单独 IPv6 listener；本地通过 `GetAdaptersAddresses` 自动列出可用 IPv4 地址，可能包含虚拟网卡。
2. 用户在电脑开启 **120 秒配对窗口**，手机填写 IP + 端口。首次登记仅支持 TCP，不支持蓝牙登记。
3. TLS 1.3 交换证书并验证私钥签名。配对连接中手机暂时接受未知服务器证书；服务器只在配对窗口内允许未知客户端身份。普通同步绝不能使用这种临时信任。
4. 手机发送 `pair`（协议 `version:1`、`token:""`、设备名）；请求消费配对窗口。双方独立计算 `SHA256(serverFingerprint + ":" + clientFingerprint)` 前 **12 位十六进制**校验码。
5. Windows Web UI 显示确认对话框，手机也显示核对框。电脑批准后发送 `pair_challenge`；手机确认后发送 `pair_confirm`。双方可以先后点击，但都必须确认一致。
6. 电脑**持久化成功后**才返回 `paired`，手机核对服务器身份与当前 TLS 证书一致后保存主机并启动同步。保存失败发送 `pairing_storage_failed`，手机显示明确错误，电脑显示具体保存阶段和路径。
7. 新同步连接必须精确钉扎已配对证书并使用双向 TLS；`hello` / `ready` 完成后才进入同步。配对连接完成后关闭属于正常设计；未登记成功就 EOF 则需要检查具体错误。

电脑确认和手机最终确认有约 60 秒等待限制，UI 核对超时约 55 秒。失败后通常需重新开启电脑配对窗口。设备名只是标签，指纹才是身份。

`security.rs` 仍保留部分旧 token 方法和测试；不是当前配对流程。生产 UI、二维码/URI 类型、ZXing/qrcode 依赖和摄像头权限已删除。跳过 `csync://pair?` 前缀的剪贴板保护仍有保留，是防止泄露旧配对材料，不代表支持链接配对。

### 文本同步与安全边界

- TCP 和 RFCOMM 都承载 **TLS 1.3 + 4 字节大端长度 + UTF-8 JSON**。正文最大 256 KiB，帧最大 `256*1024*6+8192`；读正文前必须验证帧长度。
- 身份是完整 DER 证书的 SHA-256。Windows 自签 EC 私钥由当前用户 DPAPI 保护；Android P-256 私钥保存在 Android Keystore。
- Clip 包含 UUID、来源指纹、sequence、文本、SHA-256 hash；服务器验证大小、hash、来源与会话内顺序。有界 UUID 去重，不把后续主动重复复制同一文本当成旧事件。
- Windows UI 消息线程真正应用剪贴板成功后才提交；服务器递增 revision 并向会话广播 commit，来源端跳过自己的事件。Android 防止较旧远端提交覆盖尚待提交的新本地复制。
- 重连不回放旧剪贴板、不保存正文历史、不维护持久化离线正文队列。空内容/自动清空不转发，Android 标记敏感的内容跳过。
- Shizuku UserService 在自己的 worker 中反射调用隐藏 `IClipboard`，使用 shell 身份，不继承调用者 Binder 身份。先设置监听基线，再监听新复制；用事件标记、hash、平台 timestamp 防回环和 OEM 重复通知。
- 不回退到悬浮窗抢焦点、无障碍抓取、日志抓取或剪贴板轮询。不同 Android/ROM 隐藏接口仍需实际测试。
- Windows 默认拒绝公网来源。启用公网后仍需用户配置路由器端口转发、防火墙及可达地址；没有自动公网发现、UPnP、打洞或云中继。
- 蓝牙是用户启用后的备用通道，需要系统层先配对和应用层已信任身份；硬件互通未充分验证。

## 6. Android 多主机与后台行为

- 主机列表保存地址、端口、名称、独立 pin、可选蓝牙地址。列表顺序就是连接优先级；健康连接不会为抢占高优先级主机而定期切换。
- 同端点再次配对更新原项，否则追加；地址编辑保留配对身份。删除最后一项停止同步。启用同步时排序/增删改会触发重连。
- 主存储键是 SharedPreferences `hostProfiles`（有序 JSON）。首次读取才从旧 `hosts/port/pin/serverName` 迁移；旧蓝牙地址归第一项。新键存在即不重迁移，**空列表也表示用户主动清空**，不得恢复旧数据。
- 每轮按顺序尝试所有 TCP，TLS 和 hello/ready 都成功才算连接成功；失败继续下一台。所有 TCP 失败后再按列表尝试蓝牙。TCP 连接阶段约 12 秒上限，应用握手约 8 秒；系统 DNS 解析另受系统限制。
- 退避为 **2、5、15、30、60、120、300 秒 + 0–10% 抖动**。会话稳定至少 60 秒才重置，防止短连抖动。主动重连会重置。
- 默认网络切换/丢失/地址或路由变化由 `NetworkCallback` 驱动，合并 750 ms 内连续通知；关闭旧 TCP 和未完成连接，立即重试。健康蓝牙继续使用。
- 无默认网络且未启用蓝牙时只等网络事件，不周期建连。不要求互联网 validated，局域网仍可用；没有新增 WakeLock、精确闹钟或网络轮询。
- Doze 可能推迟协程执行，不能承诺严格墙钟重连时间。手机重启后 Shizuku 通常仍需用户重新启动；常驻通知不等于所有 ROM 都不会杀进程。
- 详细设计和测试范围：`docs/ANDROID-HOSTS.md`。

## 7. Windows UI、托盘和存储

### Web UI 生命周期

- Wry 只在窗口打开时创建 WebView2；关闭窗口释放 WebView 和待批准配对，后台 Rust 服务器与剪贴板监听继续运行。重新打开重建页面。
- UI 全部本地嵌入：HTML/CSS、bundle、开源清单、图标。无 CDN、远程字体或 HTTP 管理端口。CSP 禁止网络请求，导航/新窗口受限，DevTools 关闭；IPC 仅暴露有限设置/操作和状态，不暴露剪贴板正文或私钥。
- 页面为“同步 / 设置 / 关于”，通过 `window.updateState` 更新；Rust 比较序列化状态，仅变更时推送。IPC 消息长度有限制。
- 依赖系统 **Microsoft Edge WebView2 Evergreen Runtime**，不自动安装。`bundle.js` 已纳入源码，普通 Cargo 构建不需要 Node；改 app.js 后必须重新打 bundle。
- 普通关闭隐藏到托盘；若托盘注册失败，关闭会退出，防止留下无法打开的后台进程。`--background` 遇托盘失败会打开窗口。托盘失败每 5 秒重试，成功后每 30 秒刷新；支持 Explorer 重启恢复。
- 检查托盘应看应用的 `trayAvailable` / Shell_NotifyIcon 结果；低完整性外部诊断工具查询位置失败，不等于托盘不存在。
- 修改端口或蓝牙开关需完整退出重启；公网来源开关对新连接生效。自启仅在用户开启并保存后写当前用户 Run 注册表，参数 `--background`。会话内单实例。

### 数据目录与身份迁移

- 默认目录根据 **可执行文件路径**定位为 `data/`，与工作目录无关；`--data-dir <path>` 可覆盖，显式目录不自动迁移。
- `settings.json` 存配置和信任列表；`identity.dpapi` 存加密身份；`webview/` 是 WebView2 profile。
- 仅在默认 data 不存在且旧 `%LOCALAPPDATA%\CSync` 中有身份时迁移：先复制到唯一 staging，再 rename 整体发布，避免混用证书与信任记录；旧文件保留。
- data 已存在就直接使用，包括空目录；不重新迁移、不覆盖。迁移失败清理本次 staging 并报错，不静默换身份。
- 保存使用 `.new` 临时文件 → 写入/sync → 关闭句柄 → `MoveFileExW` 原子替换。错误保留操作阶段和路径。
- 升级/移动程序应保留 data。DPAPI 身份绑定原 Windows 用户，不能直接跨电脑/用户复制后指望复用。不要为了修故障删除身份或放宽证书钉扎。

### 已定位的本机权限问题（不要重复误诊）

本机项目目录有可继承 **Low mandatory label**。新构建/复制的 EXE 可能继承 Low，使程序即使从普通用户会话启动也成为低完整性进程，导致原 AppData 的 `settings.new` 无法创建，配对确认后断开；也会干扰托盘/WebView 诊断。这和 DACL 用户 FullControl、manifest 的 asInvoker 是不同层次。

此前只恢复旧 CSync AppData 目录的权限继承未解决；随后按用户要求改为便携 data。本机后续另有用户授权记录：**仅将 `dist/CSync.exe` 调整为 Medium 完整性标签**，已验证从 Explorer 正常启动时程序/Explorer 为 Medium、托盘注册成功、手机重连。最新替换 EXE 后已核对仍保留 Medium 标签。

后续维护：先看运行路径、实际目录、完整错误和进程/文件完整性；保留现有 EXE 标签，不扩大目录权限，不自动改父目录/data/系统安全设置，也不要默认要求管理员运行。低完整性构建进程可能无权覆盖 Medium EXE，应使用合规的具体命令提权完成文件更新，而非放宽目录权限。历史授权记录不是任意安全设置修改的通行证。

## 8. 版本、关于、图标与开源清单

- Windows 版本来自根 Cargo workspace 的 `version=0.1.0`；关于页从 Rust 状态里的 `CARGO_PKG_VERSION` 读取。`build.rs` 使用 Windows SDK `rc.exe`（可用 `RC` 指定）生成 EXE 版本资源和图标资源 **101**。manifest 版本为四段 `0.1.0.0`。
- Android 版本来自 `android/app/build.gradle.kts`，关于页读 `BuildConfig.VERSION_NAME`。版本号与协议常量分开维护，不能因应用升级盲目更改协议。
- 图标源 `icon_512.png`；`python scripts/generate-app-icons.py`（需要 Pillow）生成 Windows 多尺寸 ICO、Android 多密度/adaptive icon、通知的透明单色掩模。普通构建不依赖 Pillow。
- Android 关于用官方 AlertDialog；Windows 关于是独立页面。介绍文字只用用户原句；库名/版本/许可证单独列出，不加营销介绍。
- 开源清单由解析后的元数据生成，不凭印象填许可证：Windows Cargo + npm 共 104 项；Android release 61 项、debug 64 项（当前锁文件）。数量会随依赖变化。
- Windows 资源是 `web/open-source.html`；Android release 为 `src/main/assets/open_source_libraries.txt`，debug 另用 `src/debug/assets/open_source_libraries_debug.txt`。About 根据 BuildConfig.DEBUG 选择，避免把开发工具列入正式包。
- 更换依赖/图标后需要重新生成并保留对应源码资源。署名清单不是完整的法律合规审计。

## 9. 构建和发布操作手册

在根目录运行：

```powershell
# 只在改 Windows app.js / 前端依赖时需要 Node
npm.cmd --prefix crates/csync-windows/web ci
npm.cmd --prefix crates/csync-windows/web run build

cargo fmt --all
cargo test --workspace --locked
cargo build --release --locked

.\scripts\gradle.ps1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
# 需要 instrumentation APK 时另运行
.\scripts\gradle.ps1 :app:assembleDebugAndroidTest
```

`gradle.ps1` 自动配置本地 JDK、SDK、Gradle 缓存、TMP 和代理参数。`scripts/bootstrap-android.ps1` 用于首次准备环境。沙箱中测试可能报 socket `10013` 或 Gradle 无法连接本地 daemon，必要时通过工具对具体构建/测试命令提权，不要据此改业务网络代码。

图标和依赖清单更新：

```powershell
python scripts/generate-app-icons.py
cargo metadata --locked --filter-platform x86_64-pc-windows-msvc --format-version 1 > .build/cargo-metadata-about.json
.\scripts\gradle.ps1 :app:exportOpenSourceDependencies
python scripts/generate-open-source-inventory.py
```

库存脚本读 Cargo 解析图、npm package-lock、Gradle 本地 POM（含父 POM），缺少许可证信息报错；更新依赖后再生成。Windows 导出 metadata 加平台过滤，避免无谓解析/下载 Linux GTK 依赖。新增 Android source-set 资源后若增量资源合并异常，可 `:app:clean` 后重建，不删除用户 data。

| 构建产物 | 唯一交付位置 |
|---|---|
| `target/release/CSync.exe` | `dist/CSync.exe` |
| `android/app/build/outputs/apk/debug/app-debug.apk` | `dist/CSync-android-debug.apk` |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | `dist/CSync-android-0.1.0-unsigned.apk` |

- 运行中的 exe 不能覆盖。核对完整路径，仅退出本项目实例，等退出后更新默认文件；不要留下另一个临时版本作为用户入口。保留 data 和文件安全标签。
- 复制后更新 `dist/SHA256.json`（File / Bytes / SHA256），不包含 runtime data。不要自动覆盖用户根 README。
- `scripts/build.ps1` 是已有便捷脚本，主要产出 Windows release 与 Android debug；它不等于完整的正式签名发布流水线。
- Android release 启用 R8/minify，当前**没有正式签名配置**。需开发者长期密钥签名，私钥和密码不能提交。debug 与正式签名不同，不能直接覆盖安装；不得擅自卸载用户应用。
- 当前产物约：Windows 1.83 MiB、Android debug 27.2 MiB、unsigned release 2.44 MiB。旧文档中的 4.47 MiB/58 MiB 都不是当前体积；体积不代表 RAM/耗电。
- `.build/`、`target/`、`dist/`、Android build、node_modules、Gradle/Kotlin/Python 缓存、签名文件已忽略；不要把它们加入版本控制。

## 10. 验证记录、实机调试与副作用

### 当前可确认的结果

- Rust **8 项通过、2 项 ignored**：core 6 项、TLS 集成 1 项、身份/信任迁移与保存 1 项。ignored 是实机服务器和独立窗口站剪贴板测试。
- 最新 Android JVM 报告为 **14 项通过，0 failure/error**；debug/release 构建、lint 均通过。
- 多主机序列化/身份隔离/失败切换/取消、退避上下限/稳定会话/网络事件等待等有 JVM 覆盖；HostSettingsTest 与 HostListGestureTest 使用隔离配置/临时列表，覆盖迁移、排序、删除与实际拖动。
- LAN 配对、Android 前后台文本读写、Shizuku、Unicode、无回环有历史实机成功记录。IP 新协议还通过过 `127.0.0.1` + USB reverse + 测试服务端自动批准的实机用例。
- 0.1.0 关于功能：实体手机验证了设置入口、原文介绍、0.1.0、图标和开源库；Windows 用离线浏览器看过布局、读取 EXE 版本并提取图标。APK 内的 debug/release 库清单与元数据版本也做过检查。
- 不把旧版实机结果、模拟写入回调、无头浏览器或单元测试，当作当前完整 Windows GUI/托盘/剪贴板/公网链路测试。

### 实机脚本

```powershell
adb devices -l
.\scripts\test-device.ps1 -LanHost 127.0.0.1
# 真 LAN 时传当时实际可达的电脑地址
```

脚本启动隔离 Rust `android_device_server`（45877）、安装主/测试 APK、建立 USB reverse 备用，运行指定 DeviceIntegrationTest，以 `.build/device-server/device-result.json` 判断结果。服务端仅测试环境自动批准；生产程序仍要求真实确认。

**该端到端脚本会修改手机配对配置、启停同步服务并写入测试剪贴板。** 需要时先备份配置、结束后 force-stop 再恢复并清理临时备份，不输出其中的完整身份/用户信息。不要把生产信任当成隔离测试配置。

`PairingRegressionTest` 指定 `-e host <IP>`，连接实际电脑 45876，手机测试一侧自动确认，电脑仍需批准；成功会保存真实配对并检验 hello/ready，不改剪贴板。它不是“已经全部通过的常规测试”。

### 仍需硬件或长期验证

公网转发/CGNAT/外部 IPv6，蓝牙适配器和 SDP/RFCOMM，多台真实电脑故障切换，Android 各版本/ROM 隐藏接口，Wi-Fi/移动数据切换，长时间锁屏/Doze/耗电/CPU/RAM，真正注销登录自启及 Explorer 重启恢复。不能把 README 的用户描述变成未测量的性能保证。

## 11. 专题文档与下一步选择

- UI/托盘/完整性问题：`docs/WINDOWS-WEBUI.md`
- Android 主机与重连：`docs/ANDROID-HOSTS.md`
- 协议字段和时序：`docs/PROTOCOL.md`
- 身份与安全边界：`docs/SECURITY.md`
- 关于/图标/库存生成/发布签名：`docs/RELEASE-0.1.0.md`
- `docs/VALIDATION.md` 是早期历史记录，含旧 token、旧 APK 体积和旧 UI 等过时内容，不能覆盖本文件与当前代码事实。

接到新任务后，先 `git status`，按上表读取受影响文件并完成任务；不要为了解项目而先全量扫源码或重跑所有实机测试。若是发布任务，先处理正式签名和干净发布包；若是故障，先拿到当前具体错误、运行路径、数据目录和实际状态，不重复让用户尝试已经证伪的修复。
