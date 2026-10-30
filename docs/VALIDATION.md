# 验证记录

验证日期：2026-10-30，Asia/Singapore。此文件区分自动验证与仍需真实环境覆盖的场景。

## 已验证

- Rust 协议与令牌测试：6 项通过。
- Windows TLS 集成测试：未知设备拒绝、错误令牌拒绝、有效令牌配对、持久化信任、Unicode 文本提交、伪造来源拒绝、实时撤销后断连及重连拒绝通过。
- Windows 原生程序已构建并启动，托盘 / 设置窗口已进行界面检查。
- 已连接测试设备：华为 OCE-AN50，Android 12 / API 31，Shizuku shell 服务运行。
- Android debug APK 和 instrumentation APK 构建通过，4 项 JVM 单元测试通过，lint 无错误。仍有隐藏 API、自定义证书钉扎及开发依赖更新等警告，均与当前实现选择对应。
- 实机端到端测试通过：证书钉扎拒绝错误身份、Keystore TLS 签名、配对、真实局域网传输、中文 / 表情、Shizuku 后台读取和写入、无回环。实际使用 192.168.0.101 的局域网连接，未走 USB reverse 备用。
- 独立复制辅助 APK 使用另一进程 / UID，在 CSync 位于后台时复制每轮唯一的合成文本。服务端严格确认仅收到一次该事件；电脑再发回另一条唯一文本，手机返回前台验证结果。
- Windows release 使用静态 C 运行库；PE 依赖检查仅包含 Windows 系统 DLL，无需额外的 VCRUNTIME140.dll。
- 交付文件：`dist/CSync.exe` 约 1.44 MiB；`dist/CSync-android-debug.apk` 约 4.47 MiB。

## 验证限制

- 实机端到端测试覆盖 Android 真实剪贴板及 Windows 服务端协议；测试服务端使用模拟桌面写入回调。另有真实 Win32 剪贴板测试，要求创建独立的不可见窗口站；当前环境返回 Access denied，因此默认标记为需单独运行，未触碰用户交互剪贴板。
- 最终 Windows 界面自动操作被用户按 Esc 停止，未继续窗口输入。此前调试构建的设置窗口和托盘启动已检查。
- 未进行长时间资源与电量测量；文件体积不能代表实际运行占用。

## 需要额外环境覆盖

- Android 10、11、13 及更高版本、不同厂商 ROM 的剪贴板隐藏接口和后台策略。
- 长时间 Doze / 锁屏、手机重启后的 Shizuku 恢复、操作系统强制停止。
- 真正公网路由器端口转发、动态 DNS、CGNAT、外部 IPv6。
- 不同 Windows 蓝牙适配器 / 驱动的 RFCOMM 与 SDP 兼容性。
- Windows 真正注销 / 登录自启及 Explorer 重启后的托盘恢复。
- 24 小时空闲 / 压力运行的内存、CPU 与手机电量对照测量。
