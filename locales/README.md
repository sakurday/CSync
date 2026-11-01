# CSync 翻译说明

翻译只需编辑本目录，不需要阅读 Rust、Kotlin、HTML 或 JavaScript 源码。正式编译后，语言资源嵌入 EXE 和 APK，程序运行时不读取外部语言文件。

## 对照实际界面

在 Windows 或 Android 的“设置 → 语言”中选择“键名（测试）”。按钮、提示、通知和托盘菜单会显示 `nav.settings` 等键名，在语言文件的 `messages` 中搜索该键即可定位。选择“简体中文”可以随时恢复。

产品名称 CSync、设备名称、地址、校验码、版本号及第三方库名称属于数据，保持原样；显示键时，某条带参数的文案只显示键本身，不展开参数。

两端的语言选择独立保存，立即生效。切换语言不会重连、停止同步或改变配对信息。“跟随系统”优先匹配系统语言及其文字体系；没有对应语言时选择简体中文。选定某种语言后，缺失或空的译文直接显示键，不回退到中文。

## 文件用途

- `zh-Hans.json`：当前简体中文翻译。
- `en-US.json`：English (US)，完整美式英语翻译；英文系统使用此语言。
- `qps-keys.json`：保留的测试语言，`messages` 必须保持空对象。
- `messages.json`：全部稳定键名、使用位置和参数列表，供翻译者查阅。新增功能时由开发者维护。

语言文件格式：

```json
{
  "_meta": {
    "locale": "zh-Hans",
    "nativeName": "简体中文",
    "direction": "ltr"
  },
  "messages": {
    "nav.sync": "同步",
    "sync.connected": "已连接 · {transport}"
  }
}
```

新增语言时新建 `<语言标签>.json`，修改 `_meta.locale` 和 `_meta.nativeName`，只填写已翻译的条目。文件名必须与 locale 一致，例如 `zh-Hant.json`。建议使用标准 BCP 47 标签；`direction` 为 `ltr` 或 `rtl`。构建会自动发现新文件并加入语言菜单，不需要修改语言列表源码。

`nativeName` 是菜单里始终可辨认的语言自称，不随当前界面语言变化。`system` 和 `qps-keys` 是保留标识，不能用于其他语言。

## 翻译规则

1. 只修改键对应的值，不修改键。调整中文措辞不会改变其他语言的键。
2. 保留具名参数，允许调整其位置。例如 `{device}` 表示设备名，`{host}` 表示地址，`{port}` 表示端口，`{count}` 表示数量，`{priority}` 表示优先级，`{seconds}` 表示秒数，`{version}` 表示版本，`{code}` 表示校验码，`{transport}` / `{status}` 表示按当前语言显示的连接方式或状态，`{error}` / `{path}` 为诊断详情和文件路径。
3. 翻译完整句子，保留语气和事实。关于页介绍仅翻译“在手机和电脑间同步剪贴板”，不要增加营销描述。
4. 使用 UTF-8。换行写成 JSON 的 `\n`，引号写成 `\"`。语言值只支持纯文本，不能写 HTML、脚本或格式表达式。花括号只用于参数。
5. 尚未翻译的键可以省略，也可以填写空字符串，两种方式都会直接显示键名。不要把未翻译的中文留作占位内容。

带 `count` 参数的数量文案，在 Windows 页面和 Android 中还支持复数对象：`zero`、`one`、`two`、`few`、`many`、`other`。运行时按所选语言规则选择，缺少特定类别时使用同一条目的 `other`，`other` 必须存在。每个非空形式都须保留该条目的全部参数。当前中文使用普通字符串即可。

## 检查与预览

在项目根目录运行：

```powershell
python scripts/check-locales.py
```

检查重复 JSON 键、文件名、元数据、未知键、参数及源码引用，输出各语言完成数量。部分翻译是合法的。构建过程也会验证语言结构、已登记键和参数，并重新嵌入所有语言资源。

编辑翻译后需要重新编译，修改源码目录中的 JSON 不会影响已经生成的程序：

```powershell
cargo build --release --locked
.\scripts\gradle.ps1 :app:assembleDebug
```

仅编辑翻译不需要重新构建 Windows JavaScript bundle。构建产物为 `target/release/CSync.exe` 和 `android/app/build/outputs/apk/debug/app-debug.apk`，按项目交付流程更新 `dist/`，保留已有 `data/`。

语言资源没有在线下载、外部覆盖或导入功能。
