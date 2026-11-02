# Android 主机列表

从底部导航进入独立的“主机”页，通过“添加主机”浮动按钮配对，点击条目编辑地址，拖动右侧手柄排序，删除前需要确认。顺序自动保存，启用同步时修改列表会立即重连。删除最后一项会停止同步。手柄提供无障碍“上移 / 下移”操作。

每项保存网络地址、端口、电脑名称和配对证书指纹。添加主机仍通过 IP + 端口、双端核对校验码完成。相同地址和端口再次配对会更新原条目，其他新条目追加到列表底部。

启动或重连时按顺序逐一尝试 TCP，TLS 身份验证与 hello/ready 握手全部成功后才进入同步；失败继续下一项。TCP 连接阶段最多约 12 秒，应用握手最多 8 秒（系统 DNS 解析另受系统限制）。全部失败后沿用退避重试和网络变化唤醒。健康连接持续使用，下一次重连重新从顶部开始。

SharedPreferences 新键 `hostProfiles` 保存有序 JSON。首次读取时将旧 `hosts / port / pin / serverName` 按旧地址顺序迁移，保留原证书身份；已有新列表时不重复迁移，包括主动删除后的空列表。旧字段保留供诊断，但生产连接只读取新列表。升级时清理旧蓝牙开关、地址与主机内的废弃字段；网络端点、顺序与 Android Keystore 身份不变。

断线后的重试间隔为 2、5、15、30、60、120、300 秒，每轮增加 0–10% 随机抖动；每轮仍从第一台主机开始。持续失败时约 5–5.5 分钟一轮。成功会话持续至少 60 秒才重置退避，避免电脑反复接受后断开造成密集重连。用户主动重连会重新开始。

默认网络切换、丢失，以及接口地址或路由改变时，通过系统 `NetworkCallback` 通知触发；合并 750 毫秒内的连续通知，关闭旧 TCP 会话和未完成的连接/握手，跳过剩余退避立即开始新一轮。无默认网络时，只等待网络事件，不周期尝试连接；不要求网络具备互联网验证能力，仍支持局域网。使用普通协程等待，没有新增唤醒锁、精确闹钟或网络状态轮询；Doze 下允许系统推迟执行，不承诺严格的墙钟重连时间。DNS 解析仍受系统限制。

`ReconnectBackoffTest` 覆盖持续失败上限、短连接抖动、稳定会话恢复、随机抖动边界、网络事件提前结束退避、离线等待、通知合并和停止取消。此类测试与构建不能替代实际 Wi-Fi/移动网络切换及长时间耗电测量。

2026-10-31：14 项 JVM 测试、assembleDebug、lintDebug 通过；更新唯一交付包 `dist/CSync-android-debug.apk` 和 SHA256 清单，覆盖安装到授权实体手机后，保留配对并恢复同步。读取实际运行状态确认网络连接成功、剪贴板监听就绪。本轮未切换手机 Wi-Fi/移动数据，未做长期耗电测试。

验证：JVM 测试覆盖序列化、独立身份、顺序失败切换、全部失败、取消中止；`HostSettingsTest` 使用独立临时 SharedPreferences 验证旧记录迁移、追加、排序、删除和重新加载；`HostListGestureTest` 使用临时 Compose 列表验证真实拖动和按钮回调。测试不改真实配对。多台真实电脑之间的完整故障切换仍需对应硬件场景验证。

## Home connection state (2026-11-02)

`SyncRuntime.connected` is transient Compose snapshot state, independent of the
persisted status message. A successful reconnect to the same host can leave all
preferences unchanged, so a volatile Boolean plus preference callbacks is not
sufficient to refresh the home screen. Observe the runtime flag directly; never
infer transport state by parsing translated status text. Both connection and
disconnection must invalidate composition even without a preference change.

`SyncRuntimeTest` checks worker-thread invalidation in both directions.
`ConnectionStateUiTest` checks the real home screen with only the runtime flag
changed and restored, under the existing enabled/unpaused configuration. This
UI test simulates state publication; it does not claim to exercise TLS or clipboard
sync. After this test, a separate real service reconnect and connected home title
were verified on the existing paired phone. No clipboard content was written.

## QR enrollment (2026-11-02)

The Hosts destination has Scan to add above Enter manually. The non-exported ScannerActivity requests camera permission only when opened, uses lifecycle-bound CameraX preview and ZXing QR decoding, and releases the camera when closed. Frames stay in memory. Denied permission or unavailable camera leaves manual entry available. PairingQr validates the version, bounded private IPv4 list, port, fingerprint and token before connecting. Scan pairing keeps the existing two-device verification and saves only the connected endpoint and certificate identity after successful enrollment.
