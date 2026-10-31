# 0.1.0 版本信息与关于界面

两端版本均为 `0.1.0`。Android `versionCode=1`、`versionName=0.1.0`；Windows 版本来自 Cargo workspace，同时嵌入 EXE 的 FileVersion / ProductVersion 资源。协议版本仍为 1，与应用版本号独立。

## 关于入口

- Android：设置 → 关于 CSync，官方 Compose Material 3 对话框，可滚动查看开源库。
- Windows：设置 → 关于 CSync，WebView2 内的独立页面，可返回设置。
- 介绍文字仅为：`在手机和电脑间同步剪贴板`。
- 两端显示本端实际构建版本、应用图标、依赖库名/版本/许可证。Windows 清单包含 Cargo 和 Material Web/Lit 的依赖；Android 按 debug/release 分别生成，debug 增加开发工具依赖。

## 图标与版本资源

唯一源图是根目录 `icon_512.png`。运行 `python scripts/generate-app-icons.py`（开发环境需要 Pillow）重新生成：

- Windows `crates/csync-windows/assets/app.ico`，多尺寸 ICO；`build.rs` 使用 Windows SDK `rc.exe` 编译图标和版本资源。可用 `RC` 环境变量指定编译器。窗口与托盘从 EXE 加载资源 101。
- Android 各密度 launcher PNG、adaptive icon 的原图与前景、通知使用的透明单色掩模。通知掩模符合系统小图标的透明度要求，形状来自同一源图。

源图变更后请重新生成并提交派生图标。Android 构建和普通 Cargo 构建无需 Pillow。

## 更新开源库清单

在根目录执行：

```powershell
cargo metadata --locked --filter-platform x86_64-pc-windows-msvc --format-version 1 > .build/cargo-metadata-about.json
.\scripts\gradle.ps1 :app:exportOpenSourceDependencies
python scripts/generate-open-source-inventory.py
```

脚本读取 Cargo 解析图、Web package-lock 和 Gradle 本地 POM（包含父 POM 的许可证元数据），不依赖外部 CDN。生成文件已作为源码资源保存：

- `crates/csync-windows/web/open-source.html`
- `android/app/src/main/assets/open_source_libraries.txt`（release）
- `android/app/src/debug/assets/open_source_libraries_debug.txt`（仅 debug）

升级依赖后重新导出并生成清单；缺少许可证信息时脚本会报错，需要查明实际来源，不能猜测。清单是依赖署名信息，不代表完整的第三方许可证合规审计。

## 构建与交付

```powershell
npm.cmd --prefix crates/csync-windows/web run build
cargo fmt --all
cargo test --workspace --locked
cargo build --release --locked
.\scripts\gradle.ps1 :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Windows release 程序来自 `target/release/CSync.exe`。Android 可安装调试包来自 `android/app/build/outputs/apk/debug/app-debug.apk`；经过 R8 的正式构建来自 `android/app/build/outputs/apk/release/app-release-unsigned.apk`。

**项目尚未配置正式 Android 签名密钥。** `release-unsigned.apk` 必须用开发者自己的长期发布密钥签名后才能安装/发布。不要把 debug 签名当作正式发布签名，也不要把签名私钥或密码提交到仓库。正式密钥与现有测试机的 debug 签名不同，不能直接覆盖安装；不要为更换签名擅自卸载并清空用户数据。

更新 `dist/CSync.exe` 时保留现有 `data` 和文件安全标签，不扩大目录权限。不要将真实 `data`/WebView profile 放进发布压缩包。根 README 由用户维护，不要自动覆盖。

## 本次验证

- Rust 8 项自动测试通过，2 项环境相关用例仍 ignored。
- Android debug/release 构建、JVM 单元测试、lint 通过。
- 实机检查了设置入口、关于页的原文介绍、0.1.0、图标、开源库显示；未更改配对配置，未改写剪贴板。
- Windows 离线浏览器验证关于页布局，读取 EXE 版本资源确认 FileVersion / ProductVersion 为 0.1.0，并提取了嵌入图标核验资源存在；不将浏览器预览等同于完整 Windows 原生托盘交互测试。
