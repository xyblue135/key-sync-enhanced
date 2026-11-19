
## 1. 项目是什么

- 路径：`D:\2document\00_github\key-sync-enhanced`
- 安卓端「键盘鼠标 → 触屏」映射工具：把物理键鼠输入翻译成游戏内的触摸事件并注入。
- 注入依赖 **Shizuku**（免 root 的 ADB 级权限），所以设备上必须先激活 Shizuku 并授权，另需「悬浮窗」权限。
- 这是 **KeySync 的增强分支**（enhanced fork），在原始运行时之上加了一层「预设层」，并持续做了可用性改造。

构建配置（`app/build.gradle.kts`）：

| 项 | 值 |
|---|---|
| namespace / applicationId | `com.devoid.keysync` |
| compileSdk / targetSdk | 36 |
| minSdk | 29 |
| versionCode / versionName | 55 / `1.85-android16` |
| debug 变体 | applicationId 后缀 `.fpsdebug`，versionName 后缀 `-debug` |
| Kotlin / Java | 17 |

依赖要点：Jetpack Compose（含 `constraintlayout-compose`）、Hilt 2.55（KSP）、kotlinx-serialization、DataStore Preferences、Navigation Compose、Shizuku API。

### 1.1 设置页结构

设置页按用途与作用域分为 8 个分区（除「当前预设」外均可折叠），顺序固定：

| 分组 | 内容 |
|---|---|
| 当前预设 | 预设列表（切换 / 重命名 / 复制 / 导出 / 删除）、新建、导入 / 导出全部。始终置顶 |
| 显示（全局） | 悬浮窗透明度、顶部状态提示条 |
| 按钮与操作（预设级） | 按钮默认大小、指针灵敏度 |
| 按键辅助（预设级） | 跟随当前预设：Tab 防连按（间隔可调）、R ↔ 右键换位、2-3-4 连点、Shift 取消蹲趴、连按落点、点击宏 |
| 外观与触控（预设级） | 投屏键鼠兼容、主题、背包/退出键与普通按钮的点击方式 |
| 鼠标与兼容（全局） | 鼠标断点阈值 / 输入模式锁存 / 屏幕尺寸缓存、M 键开关地图并切光标、Alt+D 拖拽丢弃开关与速度 |
| 按键拟人化（全局） | WASD / 其他按键开关、偏移强度、WASD 晃动、换向过渡（时长可调）、随机偏移间隔 |
| 维护 | 删除键位数据、关闭悬浮窗、恢复默认设置、关于；低频与危险操作集中在这里 |
| 轨迹录制 | 通知栏开始/停止，记录 WASD 向量与鼠标位移，生成 720p 轨迹动画 MP4 |

除「当前预设」外，每个分区可点标题行折叠/展开，标题旁标有「预设级 / 全局」；预设级分区统一提示“改动需点右上角保存”，全局分区为“立即生效并保存”，折叠状态会记住。

「按键辅助」和滑杆、主题等业务设置一样先暂存，点右上角「保存」才写回当前预设；返回或切换预设时选「放弃修改」不会留下半套设置。切换预设、新建预设时若有未保存修改，会先询问「保存并继续 / 放弃修改 / 取消」。

「鼠标与兼容」里的鼠标相关项属于设备级全局设置（不同鼠标各自调一次即可），改动立即生效并保存，不随预设切换。

### 1.2 与上游 KeySync 的主要区别

上游 KeySync 解决的是「键鼠事件 → 触屏注入」的运行时；本仓库是它的增强分支，重点补预设层、辅助功能和输入兼容性修复。注入方式与 Shizuku 依赖相同。

| 能力 | 上游 KeySync | 本仓库 Enhanced |
|---|---|---|
| 多预设 | 单一布局 | 多套布局 / 绑定 / 宏，可切换、重命名、复制、删除 |
| 预设导入导出 | 无 | 单预设 / 全部导出；导入生成独立副本，重复 id 自动重映射 |
| 内置布局 | 无 | 22 个 JSON 预设（通用 / FPS / MOBA / MMORPG / 动作 / 竞速），加载前校验并诊断键位冲突 |
| 按键辅助 | 无 | Tab 防连按、R ↔ 右键、2-3-4 连点、Shift 取消蹲/趴、连按落点 |
| 点击宏 | 无 | 顺序点位点击，每宏独立开关，标准间隔 / 自定义间隔 |
| 拟人化 | 无 | WASD / 其他按键抖动、强度、随机间隔 |
| 设置页 | 平铺开关 | 8 个可折叠分区 + 作用域标签；业务设置全量暂存，保存时一次写回；切换预设前确认未保存修改 |
| 编辑交互 | 双击按钮打开设置框 | 双击按钮打开设置，单击调整大小，删除收进设置框；同一按键不可重复绑定到两个按钮 |
| 鼠标稳定性 | 相对位移 + 灵敏度 | 绝对坐标回退、单帧断点过滤（默认 240px，可调）、输入模式锁存、屏幕尺寸缓存 |
| 应用内更新 | 无 | GitHub Release 检查 → 下载 → 系统安装器 |
| 输入兼容性 | 原始实现 | 多指断指、触点几何补全、注入顺序、键码越界、WASD 掩码冲突等修复 |

---

## 2. 构建与验证

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain -q
.\gradlew.bat :app:assembleDebug --console=plain -q
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 31 MB）

环境依赖：`local.properties` 中 `sdk.dir=C\:\\Users\\xyblu\\AppData\\Local\\Android\\Sdk`

关于验证的现实情况：

- `app/src/test` 有 27 个 JVM 测试类（146 个用例），覆盖 `domain/` 下的纯状态机与 `model/` 的序列化；`app/src/androidTest` 有普通按钮与宏编号点的拖动回归测试（另保留模板测试）。
- 跑测试：`.\gradlew.bat :app:testDebugUnitTest --console=plain`。
- **`compileDebugKotlin` 不编译测试源集。** 只跑编译的话，测试代码引用了已删除的 API 也发现不了——本项目就发生过一次：两个测试文件引用了 `60d95b6` 删掉的 `ProfileSwitchHotkey` / `activationKeyCode` / `profileActivationRoutes`，导致整个测试源集编译失败，54 个用例**全部静默不执行**，而 `assembleDebug` 依旧通过。改完代码请跑 `testDebugUnitTest`，不要只跑 `compileDebugKotlin`。
- `service`、`domain/EventHandler`、`domain/EventManagerImpl` 与整条注入链路仍需真机验证；纯宏执行器、鼠标去重与滚轮归一化由 JVM 测试覆盖，拖动由 androidTest 覆盖。
- 注意 **Kotlin 未使用的 import 只是 warning，不会报错**，所以删函数后残留 import 不会被编译器抓出来。

---

## 3. 运行时架构

分三层：

1. **UI 层（Compose）** —— `MainScreen`（游戏列表 / 启动入口）、`SettingsScreen`（设置）、`AboutScreen`；悬浮层在 `ui/overlay/*`。
2. **状态层** —— `FloatingWindowStateManager`（Hilt 单例）持有全部 StateFlow：Profile、按键项、编辑态、按键显隐、拟人化、透明度等，并负责持久化与**旧数据迁移**。
3. **注入层** —— `EventHandler`（核心状态机）→ `EventManagerImpl` / `EventInjectorImpl` → 经 Shizuku 注入触摸事件。

关键文件职责：

| 文件 | 职责 |
|---|---|
| `service/FloatingBubbleService.kt` | 前台服务 + 悬浮窗（WindowManager overlay）+ **通知栏控制面** |
| `service/FloatingWindowStateManager.kt` | 全局状态中心（946 行）：Profile / 按键 / 编辑态 / 拟人化 / 迁移 / 持久化 |
| `domain/EventHandler.kt` | 键鼠事件 → 触摸事件的核心状态机（856 行）：WASD 摇杆、开火、倍镜、滚轮、拟人化 |
| `domain/MouseButtonTracker.kt` | 鼠标按钮位去重：把 `MotionEvent` 按钮与鼠标来源的 Back/Forward 键盘事件归一成一次边沿 |
| `domain/MouseInputs.kt` | 侧键 / 滚轮四向的私有键码、鼠标键码映射、滚轮小数累计 |
| `domain/EventInjectorImpl.kt` | 注入串行队列 + 高频 MOVE 合并（鼠标 125–1000Hz） |
| `domain/PendingMoves.kt` | 单触点位移累加批次，合并时保留总位移 |
| `domain/JoystickMotion.kt` | WASD 虚拟摇杆的「重锚点」逻辑（见 6.1，改动风险最高的小文件） |
| `domain/MultiModeTouchHandlerImpl.kt`、`TouchToMouseTranslator.kt` | 点击 / 按住 / 滑动等手势模式与鼠标翻译 |
| `data/mapping/MappingPresetRepository.kt` | 读取 `data/mapping/*.json` 预设 |
| `data/local/DataStoreManager.kt` | DataStore 读写（单一配置仓） |
| `ui/overlay/FloatingWindow.kt` | `ItemsContainer`（按键渲染）、`EditToolbar`（编辑态操作条）、`MenuItems`（加号菜单） |
| `ui/overlay/ContainerItems.kt` | 单个可拖拽按键的渲染与手势 |

### 3.1 鼠标输入

鼠标链路与键盘分开，入口是 `FloatingWindowStateManager.onMouseEvent()`：

1. `FloatingBubbleService` 对 ComposeView 请求 **pointer capture**，用 `setOnCapturedPointerListener` + `setOnGenericMotionListener` 收事件。
2. **相对鼠标**（`SOURCE_MOUSE_RELATIVE`）直接把 `event.x / event.y` 当作增量；非相对源先取 `AXIS_RELATIVE_X/Y`，再回退到相邻 `rawX/rawY` 的差值。绝对坐标只用来算差值，不能直接当位移。
3. `EventHandler.handlePointerMove()` 分流：轮盘激活时转轮盘；射击模式乘 `pointerSensitivity` 转视角；光标模式 1:1 移动光标，**不拖动任何触点、也不注入点击**。
4. 注入走 `EventInjectorImpl` → `ShizukuSystemServerAPi`（见第 1 节）。

支持范围：

| 输入 | 私有键码 | 说明 |
|---|---|---|
| 左 / 右 / 中键 | LMB / RMB / MMB | `BUTTON_PRIMARY / SECONDARY / TERTIARY` |
| 侧键 1 / 侧键 2 | `-10006` / `-10007` | 后退 / 前进，可绑点击、按住和宏 |
| 滚轮上 / 下 / 左 / 右 | `-10008` ~ `-10011` | 上下取 `AXIS_VSCROLL`，左右取 `AXIS_HSCROLL` |

- 鼠标私有键码从 `-10002` 起，全部是负值，不与 Android `KeyEvent` 键码重叠；`mouseInputKeyCodes` 是唯一清单。
- **侧键去重**：同一颗侧键可能同时以 `MotionEvent.BUTTON_BACK/FORWARD` 和 `KeyEvent.KEYCODE_BACK/FORWARD` 到达。`mouseKeyAlias()` 只在 `source` 是鼠标时做别名，`MouseButtonTracker` 把两条路径归一成一次边沿，避免触发两次或误触发系统返回。
- **滚轮**：`ScrollTracker` 累计小数增量（高分辨率滚轮 / 触控板），方向反转时先归零，单次累计限制 ±16；只有满一格才产生一次点击。轮盘打开时滚轮优先控制轮盘，不再触发普通绑定。
- **1000Hz 高回报率**：注入用 `INJECT_INPUT_EVENT_MODE_ASYNC`，并用 `PendingMoves` 把同一触点两次 flush 之间的位移累加成一条 MOVE。**总位移不丢、中间采样点丢弃**，所以注入吞吐不足时是降采样而不是积压；`EventInjectorImpl` 的单线程队列保证 DOWN / MOVE / UP 不乱序。
- 宏的 45–95ms 点击与拟人化抖动循环（默认 1000ms，可调）不在这条路径上，和高回报率互不影响。
- **光标模式的按键语义**：光标模式（非射击模式）下左键/右键在**光标所在位置**注入一次原生点击（≥60ms），点击会落到该坐标上实际存在的东西——包括映射按钮。
- **按住拖动可用**：左键/右键保持按下时，触点会跟随光标一起移动，因此按住上下滑动、拖拽列表/滚动条都能正常工作；松开时才抬起触点。
- **已知且有意保留的行为**：如果光标恰好停在开火/右键按钮的屏幕位置上，切出鼠标后点击会**真实触发开火/开镜**。注入只能携带坐标与按压，游戏无法区分「UI 点击」和「开火」，因此不做区域屏蔽；想避免误触就把光标移开再点。中键/侧键不受影响，仍按各自绑定处理。
- **光标模式的可视化**：屏幕上显示的是一个 26dp 的半透明触摸指示圈（描边 + 中心点），不再是箭头图标，视觉上更接近「手指触点」。光标本身只做显示，点击坐标仍由 `pointerOffset` 计算。

### 3.2 鼠标按键数量的硬限制（G502 等）

Android 的 `MotionEvent` 只定义 5 个按钮位（PRIMARY / SECONDARY / TERTIARY / BACK / FORWARD），`MouseButtonTracker` 也只认这 5 位。**第 6 个及以后的鼠标键不可能通过 `buttonState` 进入本应用。**

| 按键 | 状态 |
|---|---|
| 左 / 右 / 中键、两个拇指侧键 | 原生支持 |
| 滚轮上下、左右倾斜 | 支持；横向需要硬件与 ROM 都实际上报 `AXIS_HSCROLL` |
| G502 的 DPI 加/减、狙击键、配置文件键、G Shift | **不支持**。多为固件级功能（狙击键只改传感器 DPI）或厂商私有 HID usage，Android `InputReader` 不产生事件，任何应用都拦不到 |
| 被板载配置映射成键盘键的额外键 | **可支持**。以普通 `KeyEvent` 到达，按一下即可绑定 |

**推荐做法（G502 等）**：在 PC 上用 Logitech G HUB 把额外键写入鼠标**板载内存**，映射成 `F13–F24` 这类手机上基本不用的键盘键；再把鼠标接到手机，在绑定窗口按一下就能当普通按键用，也能当宏触发键。G HUB 的映射不会在手机上生效，必须先写入板载内存。蓝牙连接的上报能力通常弱于 USB 接收器，测试时优先用接收器。

**排查方法**：

```powershell
adb shell getevent -l
```

逐个按鼠标键，观察落到哪个 `/dev/input/eventX`：

```
BTN_LEFT / BTN_RIGHT / BTN_MIDDLE  → 左 / 右 / 中键
BTN_SIDE / BTN_EXTRA               → 侧键 1 / 侧键 2
REL_WHEEL / REL_HWHEEL             → 滚轮上/下、左/右
KEY_F13 ... KEY_F24                → 额外键被当成键盘键（可绑定）
完全没有输出                        → 固件级功能，无解
```

真机验收流程见 `docs/MOUSE_AND_MACROS.md`。

### 3.3 应用内更新（自动检查 + 一键安装）

更新源就是本仓库：`github.com/xyblue135/key-sync-enhanced`。

- **启动时自动检查一次**：`MainActivity` 的 `LaunchedEffect(Unit)` 调用 `viewModel.checkForUpdates()`；只有发现新版本才弹窗，已是最新或网络失败都静默，不打扰。
- **弹窗内容**：新版本号 + Release 说明（截断 700 字），按钮「下载并安装」/「稍后」。「稍后」只隐藏本次弹窗，不会自动再弹。
- **手动检查**：关于页「检查更新」按钮，状态机为 `Idle → Checking → Available / UpToDate / Failed`，下载中为 `Downloading(progress)`。
- `data/external/GitHubReleaseClient.kt` 读取 `releases/latest`，解析 `tag_name`、`html_url`、`body` 与第一个 `.apk` 资源的 `browser_download_url`；只有标签没有 Release 时回退到 `tags` 第一项（此时没有 APK 直链，按钮退回「前往下载」）。
- `util/VersionComparator.kt` 只比较版本号核心（开头的点分数字），`v1.70-android16` 与 `1.70` 视为同一版本。**版本号必须保持数值递增**：`1.7` 会被判定为低于 `1.63`，所以「1.7」要写成 `1.70`。
- **下载与安装**：`data/remote/ApkInstaller.kt` 用系统 `DownloadManager` 下载（自动跟随后 302 跳转，通知栏有进度），保存到 `externalFilesDir/Download/updates/`；完成后通过 `FileProvider`（authority `${applicationId}.updates`，路径见 `res/xml/file_paths.xml`）拉起系统安装器。
- **安装权限**：需要 `REQUEST_INSTALL_PACKAGES`；未授权时跳转本应用的「安装未知来源应用」页面，并提示用户允许后重试。
- 需要 `INTERNET` 权限（查询与下载）；除启动检查与手动点击外，没有后台轮询。

### 3.4 预设导入导出

预设（键鼠布局 + 键位绑定 + 宏）支持两种方式带走：

| 方式 | 入口 | 说明 |
|---|---|---|
| 文件 | 预设卡片「导出到文件」/ 单预设导出对话框「保存为文件」/ 「从文件导入」/ 对话框「从文件选择」 | 走系统文件选择器（SAF），无需存储权限；跨设备拷贝 `.json` 即可 |
| 剪贴板 | 「复制全部预设」/ 导出对话框「复制」/ 导入对话框「粘贴」 | 适合快速分享单套预设 |

- 文件格式与剪贴板完全一致：单预设为 `Profile` JSON，全部预设为 `ProfileBundle { version: 1, profiles: [...] }`。
- 两种入口共用同一套校验：版本号、宏点位、重复触发键、预设 ID 去重、同名自动加「副本 N」。
- 导入一律作为**独立副本**加入，不覆盖现有预设；快捷键冲突时保留切入键但停用。
- 按键位置按「悬浮窗可用区域」的比例保存，不是按手机分辨率；换设备或可用区域比例不同时可能错位。导入前会弹出提示，导入后请进编辑态核对位置。
- 入口：设置页「预设」分组里的「导入 / 从文件导入 / 复制全部预设 / 导出到文件」。

### 3.5 拟人化（模拟人手）

目标是让注入触点在线迹、时序、触点参数上不再呈现机械规律，**默认保持零延迟**：只改位置与触点数值，不增加等待。唯一例外是 WASD 换向过渡（见下表），默认 60ms、可关闭，松手仍然立即 UP。

| 维度 | 行为 |
|---|---|
| 落点分布（普通按键） | 圆内均匀采样（`EventHandler.jitterOffset()`），半径上限为 `强度% × 8px`；强度 1 时几乎为零 |
| 落点分布（WASD） | 每次换向采样一次偏置：角度 ≤ ±1.2°、半径 ≥ 99.2%（`biasedDirection()`）；按住期间按「WASD 方向晃动」参数做 ≤ ±1.5° 的缓慢摆头（`jitterTarget()`）。**满值也不会压到慢走/死区** |
| 疾跑切换 | 默认不 release：按下 / 松开 Shift 时，从当前落点沿同一方向在 60ms 内拉到（或拉回）疾跑半径，和换向过渡共用开关与时长。关闭过渡后恢复「release + 在新落点重新 DOWN」的瞬移方式（个别浮动摇杆游戏只能这样触发疾跑） |
| 推力范围 | 每次换向或重新按下重采样一次：普通移动在基准的 1.00–1.08 倍之间（幅度随「WASD 晃动」缩放，只向上浮动，不会掉进慢走/死区）；疾跑在 `sprintScale`（默认 1.5）之上再上浮 0–12%，即 1.50–1.68 倍。同一段按住期间倍率不变 |
| 斜跑疾跑 | 斜跑不再叠加前向+横向距离（否则 W+D 会到 2.1 倍、超出摇杆边缘），而是和正方向落在同一圈上；斜跑的前向分量约为半径的 0.707 倍 |
| WASD 换向过渡 | 小角度换向（W→WD→D）沿等半径弧线走；接近 180° 的 A↔D、W↔S 则把半径在中间压到 18%（`TRANSITION_MIN_RADIUS_FACTOR`），触点几乎横穿摇杆中心，轨迹是一条左↔右/上↔下的线，同时保留最低推动量避免完全停住。默认 60ms（可调 40–200ms），补 3–8 个中间点。开关与时长在设置页「按键拟人化」里；关闭后换向回到 8 向瞬移。疾跑位变化、触点或中心变化时仍优先 release + DOWN |
| WASD 持键 | 按「随机偏移间隔」重掷（默认 1000ms，可调 200–3000ms，实际间隔 ±20% 随机），只发 MOVE（`JoystickMotion.jitter()`），**绝不 reanchor**（见 6.1） |
| 普通按键持键 | HOLD / MIXED 同样按该间隔微抖，只对单点落位（`position == end`）生效；滑动类按键跳过，避免把触点拽回锚点 |
| TAP 点击 | 按住时长采样：多数 50–85ms，约 15% 为 85–125ms（`sampleTapHoldDuration()`）；中点补一帧 0.3–1.2px 位移 |
| 宏 / 2-3-4 连点 | 每次点击的按住时长与间隔都采样；2-3-4 的间隔由预设的「连点间隔」决定（20–300ms，默认 60ms），±10% 抖动 |
| 触点参数 | 压力 `0.82–1.00`、`size` `0.85–1.00`、接触面积 ±15%，每个触点在按下时独立取样（`EventManagerImpl`） |
| 关闭行为 | 两路拟人化都关闭时恢复固定压力/面积；强度最低 1（几乎零偏移），不启动位置循环 |

拟人化是全局设置，对所有预设统一生效。它只能改变轨迹与触点参数，无法改变事件的设备身份（`deviceId`）。

### 3.6 快捷换位（1–4 / X）与横竖屏投影

**快捷换位**是预设级开关（`Profile.quickSwapEnabled`，默认开启），键位固定，不需要任何手动配置：

| 按键 | 行为 |
|---|---|
| `1` / `2` / `3` / `4` | 任意一个都让「绑定 R 的按钮」与「绑定鼠标右键（`KEYCODE_RMC`）的按钮」互换屏幕位置 |
| `X` | 两者回到各自的 `anchorPosition` |

- 换位**带锁存**：未换位时按 1–4 任意一个换一次；已换位时再按 1–4 都**不再换位**（只当普通按键透传），避免来回跳。
- `X` 只在已换位时生效；还原后再按 1–4 才会再次交换。
- 锁存状态存在 `Profile.quickSwapLatched` 并持久化：重启后位置仍是换位状态，不会被误判成未换位而再跳一次。
- A / B 每次按键时按**当前绑定**实时解析，所以重绑 R 或右键后依然正确。
- 只改位置、不动绑定；刷新坐标走 `EventHandler.repositionItems()`，不会释放正在按的触点。
- 开关位置：设置页 →「按键辅助」→「R ↔ 右键（1/2/3/4 换位 / X 还原）」。
- 换位键本身仍会透传给游戏。
- 换位对象写死为 R 与鼠标右键，不再提供“双键交换对”自定义入口；需要换成别的按钮请自行改代码。

**M 键开关地图并切光标**（全局开关 `map_mouse_toggle_enabled`，默认开启）：

- 第一次按 `M`：照常触发 M 键映射（打开地图），同时退出射击模式，把鼠标光标切出来。
- 再按一次 `M`：不再触发 M，改为点击 `ESC` 按钮的映射位置（关图），随后进入射击模式、收回鼠标。
- 如此交替；连按 `M` 相当于「开图 → 关图 → 开图」，中途都可以直接用鼠标点地图上的标记/道具。
- 开关位置：设置页 →「鼠标与兼容」→「M 键开关地图并切光标」。关闭后 `M` 恢复成普通映射键。
- 需要预设里有一个 `ESC` 按钮才会执行关图点击；没有时只切换鼠标模式。

**F 键光标双击**：

- 只在**光标模式**（非射击模式）且本次运行中已出现过鼠标输入时生效，射击模式下 F 保持原有映射。
- 按 `F` 在光标当前位置快速点两下：间隔约 90ms，每段按住时长沿用点击采样（50–125ms）。
- 触发后吞掉 F 本身的映射，不会再去点 F 按钮；适合开背包/地图后直接双击拾取或打开物品。

**Alt+D 拖拽丢弃**（`Profile.discardX/discardY` + 全局开关 `discard_drag_enabled`）：

- 编辑态「＋」→「工具」→「丢弃点」放置一个竖条标记（宽:高约 1:5），拖动即定位；非编辑态不渲染。
- 高级功能打开「Alt+D 拖拽丢弃」后生效。必须在**光标模式**（非射击模式）、且本次运行中出现过鼠标输入时，**按住 Alt 再按 D** 才触发；单按 D 不受影响。
- 动作序列：在光标当前位置按下左键 → 12 步拖到丢弃点 → 停 70ms → 松开 → 70ms 后光标回到起点。
- 拖拽速度可调（全局设置 `discard_drag_speed`，×1–×10，默认 ×5，即原来的 5 倍速）；只缩放移动阶段（12 步的时间），按下与停顿时长不变，保证游戏有足够时间识别“放下”。
- 协程取消或异常时也会强制松开触点，避免鼠标卡在按下状态；开关默认关闭。

**2-3-4 连点**（`Profile.tapSequence234Enabled`，默认开启）：按一次 `3` 依次点击 `2 → 3 → 4`，用于一键切换武器组。

- 连点间隔按预设可调（`tapSequence234GapMs`，20–300ms，默认 60ms），就在开关同一菜单里用滑杆调；按住时长自动取间隔的一半，整体节奏随间隔缩放。
- 三个按钮缺一就不接管，`3` 保持原行为。

- 三个触点是采样时长（按下 38–78ms，间隔 90–150ms，12% 长尾 150–220ms），串行执行；重复按 3 不会叠加成多条序列。
- 三个按钮缺任何一个就不接管，`3` 回落到原本的单键映射，避免"按了没反应"。
- 开关位置同快捷换位：设置页 →「按键辅助」→「按 3 依次点 2/3/4」。

**Tab 防连按**（`Profile.tabDebounceEnabled`，默认开启）：

- 与上一次有效 `Tab` 间隔不足设定值时，重复的 Tab 整段吞掉（DOWN/UP 一起），避免手速过快时多余的 Tab 落到游戏里。
- 间隔（`tabDebounceMs`，默认 1000ms，可调 200–3000）在设置页用滑杆调整；只拦截 Tab，其他按键不受影响。
- 开关位置：设置页 →「按键辅助」→「Tab 防连按」。

**连按落点**（`Profile.extraTapPoints`，每个按键最多 3 个）：

- 同一按键在 250ms 内连续按下时，第 2/3/4 次按下会让本体照常触发，**同时额外点击一个落点**；单按、慢按完全不变。
- 入口在按钮的双击设置框：「连按落点 n/3」→「添加连按落点」/「清空落点」。
- 添加后进入编辑布局，落点显示为带「键名·序号」的小圆圈，直接拖动定位；松手写回预设。
- 触点使用独立 pointer id，和宏、摇杆、普通按键互不干扰；点击按住时长沿用现有拟人化采样。

**按键唯一性**：同一按键不能再绑定到两个按钮（W/A/S/D 由摇杆组占用，同样算重复）。绑定框里命中已占用按键会显示红色提示并禁止确认；旧预设/导入数据里的重复键不阻断加载，保持“后绑定者生效”。

**Shift 取消蹲/趴**（`Profile.stanceAssistEnabled`，默认开启）：

- 与静步同理，只取消「我们自己点开的」姿态：按 `C`（趴）/ `Z`（蹲）/ `Ctrl`（蹲）时记录最后一次姿态键，同一个键再按一次视为关闭。
- 按 `Shift` 时若记录了姿态，补点一次同一个姿态键把它取消，然后正常开始跑；不会出现「一按 Shift 还在蹲/趴着」。
- 补点的按下时长 35–65ms，在 Shift 事件之前发出，姿态取消与起跑几乎同时。
- 姿态键或 Shift 没有绑定按钮时不介入，按键照常透传。
- 开关位置：设置页 →「按键辅助」→「Shift 取消蹲/趴（C/Z/Ctrl）」。

**点击宏**（`Profile.macros`，每个宏独立开关）：设置页 →「按键辅助」→「点击宏」里新增与编辑，进入「编辑布局」后拖动编号点定位。按一次触发键，依次点击各个位置。

- 间隔有两种模式，类似罗技宏：**标准间隔**（所有位置统一等待，默认 100ms，0–5000ms）与**自定义间隔**（每个位置单独设置到下一步的等待，0–5000ms）。
- 旧版本保存的宏没有间隔模式字段，升级后默认按原来的「自定义间隔」执行，节奏不变。
- 每次点击的按住时长采样 45–95ms，间隔从松开后开始计算；最后一步之后不再等待。

**编辑条默认收起为中间的圆球**：进入编辑态只显示一个居中的圆形齿轮球，不再占用右上角，放在右上角的自定义按键可以正常拖动、双击设置。点圆球展开操作条（`⠿ / ＋ / 显隐 / 提示 / 完成 / 收起`），展开后的长条同样**居中**显示，不会挤占右上角；按住 `⠿` 可移动；球本身也能拖动，点一下展开。手柄使用独立拖动手势，不会和按钮点击冲突；配置对话框打开期间底层按钮禁止拖动。

**触摸模式与配色**：单选列表和设置页下拉都用颜色区分，方便快速确认每个按钮是哪种模式。

| 模式 | 颜色 | 行为 |
|---|---|---|
| 点击 TAP | 绿 | 按下注入一次点击（按住时长 50–125ms），松开不会延长，适合开火一次/跳跃/切枪 |
| 按住 HOLD | 蓝 | 按下保持到松开，适合开镜、持续奔跑、按住屏息 |
| 混合 MIXED | 橙 | 按下立即生效，最少保持 200ms 才抬起；单击也会被拉长，**有明显延迟，不推荐使用**，列表里已标注 |
| 轮盘 WHEEL | 紫 | 按住打开轮盘，移动鼠标选择，松开确认 |
| 跟随全局 | 灰 | 使用设置页「普通按钮 / 可取消按钮」的全局触摸模式 |

**按钮齿轮**：编辑态下每个可配置按钮右上角显示一个小齿轮，点一下直接打开配置框（等价于双击按钮），非编辑态不渲染。

**双击编辑 / 删除**：编辑态下按钮周围不再显示齿轮和删除图标，避免小按钮旁边挂着大图标、也避免误触删掉映射。**双击**按钮才打开对应的设置框（绑定按键 / 点击模式 / 尺寸 / 滚轮半径 / 删除）；删除只在设置框底部提供。WASD 摇杆双击打开疾跑距离设置，删除同样在框内。

**单按钮尺寸**：每个按钮（通用按钮 / 固定键 / 可取消键）新增 `customSizeDp`，在双击打开的设置框里用滑块单独调节（28–110 dp），与全局 `buttonScale` 叠乘。位置坐标不受影响，触点中心由测量结果自动跟随，改大改小都不会让点击跑偏。

**横竖屏**：`projectLayout()` 检测基准与当前视口朝向是否一致。交叉时整体旋转 90°（横→竖顺时针、竖→横逆时针，两者互为逆运算），而不是把 X/Y 各自拉伸——后者会把横屏布局直接"搬"到竖屏，左右两侧按钮全部错位。同朝向的不同分辨率仍按轴独立缩放。

**分辨率数字差异**：预设记录的 `layoutScreenWidth/Height` 是**悬浮层可用区域**（扣掉状态栏/导航栏，例如 1200×2466），设备信息显示的是整块屏幕（1200×2608）。两者相差的就是系统栏高度，不是 bug。

**通知栏控制面**：屏幕上不再有常驻工具条，所有入口都在前台服务通知里。通知只保留 4 个 action：

| 位置 | Action | 行为 |
|---|---|---|
| 1 | 关闭悬浮窗 | 停止前台服务并移除悬浮窗，等价于设置页「恢复默认」上方的同名按钮；下次从应用重新启动 |
| 2 | 隐藏 / 显示 | 只控制悬浮窗按键的渲染 |
| 3 | 录制 / 停止 | 录制 WASD 向量与鼠标位移，停止后生成 MP4 |
| 4 | 编辑 / 退出 | 进入 / 退出编辑态；添加按键与提示条显隐都在编辑工具条里 |

**暂停映射已下线**：暂停后没有可靠的恢复入口（实践里经常忘了恢复，表现为“按键失灵”），因此第一个 action 改为直接「关闭悬浮窗」。之前 5 个 action 在 HyperOS 上会把按钮压到只剩一个字（「暂…」）甚至空白，现在总数保持 4 个。

---

### 3.7 轨迹录制与导出

通知栏第三个按钮就是「录制」。点一下开始，再点「停止」，后台渲染 720p / 30fps 的 MP4；完成后会另发一条通知，点开即可播放。

- **记录内容**：WASD 虚拟摇杆向量、射击模式下实际注入的鼠标增量。不记录其他按键、游戏画面、声音和屏幕内容。
- **记录的是真实注入点**：换向时的一次性方向偏置、按住期间的微抖、弧线过渡的每个中间点，都按实际注入的坐标记录，不是理想化的 8 个方向点；疾跑以普通方向落点的 1.50–1.68 倍（`sprintScale` 默认 1.5，再叠加每段随机上浮）显示，按住 `Shift` 时能直接看到轨迹半径外扩、且每段半径并不相同。
- **数据位置**：录制数据只在内存里，停止后交给渲染器；视频通过 MediaStore 写入系统媒体库 `Movies/KeySync/trajectory-*.mp4`，相册和文件管理器可直接看到。媒体库不可用时退回 `Android/data/<包名>/files/Movies/KeySync/`。
- **画面**：左侧是 WASD 向量轨迹（单位是方向键距离，1.0 约等于满推，疾跑会超过 1），右侧是鼠标增量积分出的视角路径；底部时间轴，单次最长 60 秒。
- **断线规则**：WASD 轨迹按「一次连续按压」分段绘制。只在松手（零向量）或间隔超过 300ms 时断开；方向/半径突变不再断线，所以 A → D 会连成一条穿心的左→右轨迹。最近 2 秒的段高亮并画出采样点，更早的段淡显作背景。
- **边界**：WASD 图只能反映输入，不能反映游戏内移速、地形和碰撞；鼠标图是注入给游戏的视角位移，和实际画面转动一致，但会受游戏内灵敏度影响。
- **实现**：`TrajectoryRecorder` 记录 → `TrajectoryVideoRenderer` 用 Bitmap + MediaCodec 编码，不需要录屏权限。

## 4. 数据与持久化

DataStore 文件名为 `app_configurations`，键定义集中在 `DataStoreManager.companion`：

| 键 | 类型 | 说明 |
|---|---|---|
| `profiles` | String(JSON) | 所有 Profile —— **当前的主存储** |
| `active_profile_id` | String | 当前激活的 Profile |
| `keys_config` | String(JSON) | 全局 `AppConfig` |
| `buttons_config_<packageName>` | String(JSON) | 每个游戏一套按键布局（遗留路径，仍兼容） |
| `added_packages` | String(JSON) | 已加入的游戏包名列表 |
| `pointer_sensitivity` | Float | 鼠标灵敏度 |
| `overlay_opacity` | Float | 悬浮窗透明度 |
| `wasd_humanization` | Boolean | WASD 拟人化开关（全局） |
| `key_humanization` | Boolean | 其他按键拟人化开关（全局） |
| `humanization_strength_scale` | Int | 拟人化强度 1–100，默认 50（全局） |
| `humanization_strength` | Float | 旧版 0..1 强度，仅用于迁移到 `humanization_strength_scale` |
| `wasd_sway` | Int | WASD 方向晃动幅度 1–100，默认 50（全局，独立于整体强度） |
| `humanization_interval_ms` | Int | 随机偏移间隔 ms，默认 1000（全局） |

模型（`model/`）：

- `Profile`：`id` / `name` / `items` / `appConfig` / `quickSwapLatched` / `macros` / `layoutScreenWidth` / `layoutScreenHeight`。
  后两个字段是**布局保存时的屏幕分辨率基准**，`0` 表示未记录；横竖屏由宽高关系隐含决定。
- `DraggableItem`：拖拽按键，运行时及 Profile 保存的是**基准视口内的像素坐标**；含 `DraggableItem.FixedKey`，以及 `DraggableItemType` 的 `WASD_KEY` / `KEY` / `SHOOTING_MODE` / `WALK_TOGGLE` 等。
- `AppConfig`：`buttonScale`、`pointerSensitivity`、`deleteDataOnRemove`、`screenMirrorCompatMode`、`themePreference` 等。

预设资产：`data/` 通过 `sourceSets["main"].assets.srcDir("$rootDir/data")` 挂成 assets，`data/mapping/**` 共 24 个 JSON（含 `index.json` 索引），分类为 universal / fps / moba / mmorpg / action / racing 与 `templates/blank.json`。**新增预设不需要改 Kotlin**。

---

## 5. 硬性约束（改代码前必读）

1. **区分两种坐标格式**：内置 `MappingPreset` 资产按 `coordinateMode` 使用归一化或像素坐标；用户 `Profile` 保存基准视口内的像素坐标，并同时保存 `layoutScreenWidth/Height`。禁止混用两种 JSON 格式，禁止只改坐标单位而不做数据迁移。
2. **布局基准必须持久化**：每个 Profile 记住保存时的 `layoutScreenWidth/Height`；**加载/保存预设时不要用当前屏幕去覆盖已记录的基准**。无基准的历史数据目前按已知旧版本的 `2608×1200` 迁移；这是历史兼容假设，不适用于新数据。新布局保存时记录真实视口，后续如需支持其他旧设备，应单独设计迁移选择，不能擅自重解释已有数据。
3. 加载及旋转时统一通过 `projectLayout()` 投影。同朝向按扣除按键尺寸后的可移动范围分别换算 X/Y；**横竖屏交叉时必须整体旋转 90°**（横→竖顺时针、竖→横逆时针），禁止把两轴各自拉伸。运行期布局使用自己的 `layoutViewport`，不能把旧坐标直接标为新基准。WASD 的 center/w/a/s/d 由 Compose 重新测量，不参与投影。
4. **游戏界面优先横屏**。若确实需要竖屏使用，依赖 `projectLayout()` 的整体旋转，不要手工把横屏坐标改成竖屏坐标。
5. **快捷换位（`quickSwapEnabled`）只修改按钮的物理布局位置**，不改键位绑定与名称；A/B 按当前绑定实时解析（R 与 `KEYCODE_RMC`），位置恢复用 `anchorPosition`。换位对象与键位均写死，UI 不再提供自定义入口；旧 `swapPairs` 字段读入时直接忽略。<br>交换后刷新坐标只能调用 `EventHandler.repositionItems()`，**禁止调用 `updateKeyMapping()`**——后者会 `eventInjector.clear()` 释放全部触点，按一次换位键就会把正在按着的 W / 开火松开。
6. **拟人化必须零延迟**：只允许通过调整注入触点位置 / 轨迹 / 触点参数实现，**禁止**添加等待、渐进、缓冲逻辑。抖动循环的间隔由 `humanization_interval_ms` 决定（默认 1000ms，±20% 随机，`EventHandler.humanizeScope`），它不阻塞按键响应路径。
7. 拟人化需**独立开关 + 强度滑杆（1–100）+ 偏移间隔滑杆（默认 1000ms）**，且**全局生效**（对所有预设统一），不要做成按预设存储。WASD 另有独立的「方向晃动」滑杆（1–100）；角度/半径扰动必须随参数缩放，半径不得低于 99%。
8. 键位交换热键必须保留；「预设切换热键」已被要求删除，不要加回来。
9. **全局功能配置放 `DataStoreManager`**（全局键），不要塞进 `Profile`。
10. `projectLayout()` 的位置投影与按键尺寸是两回事：位置按每轴可用范围换算，尺寸由 Compose 实测，禁止反复缩放缓存尺寸导致按钮越变越小。以 `LayoutProjectionTest` 的往返、旋转和边界用例为准。

---

## 6. 踩过的坑（回归风险清单）

### 6.1 Shift 跑步时「向后顿一下」——改动风险最高的地方

- 现象：纯 WASD 正常，叠加 Shift 疾跑就卡顿，像向后顿一下。
- 根因：`JoystickMotion.update()` 的重锚判定写成 `(lastMask or mask) and sprintBit != 0`（**用的是「或」**），导致疾跑期间**每一次** `update()` 都被判定为疾跑切换 → `release()` + 重新 `start()`（从中性位重新起拖），游戏就把这一串读成「松手后重新推杆」。以前 `update()` 只在物理按键事件时被调用，次数少所以看不出来；拟人化每 140ms 的抖动循环把它放大了。
- 修复：新增 `JoystickMotion.jitter()` —— **只发 MOVE，绝不 reanchor**；抖动循环改调它。同时修了两个隐患：`wasdMap` 没有对应项时停掉抖动循环；`updateKeyMapping()` / `clear()` 重置映射时调用 `stopWasdJitter()`。
- **红线：`jitter()` 与 `update()` 的职责必须保持分离。任何让抖动路径触发 reanchor 的改动都会复现这个 bug。**

### 6.2 通知栏相关（最近一次改动引入，尚未真机验证）

- **Android 12+ notification trampoline 限制**：`contentIntent` 必须用 `PendingIntent.getActivity`，不能先跳 Service/Receiver 再 `startActivity`，否则点击没有反应。
- action 用 `PendingIntent.getService`（startService 语义）而非 `getForegroundService`，避免引入「5 秒内必须 `startForeground`」的 `ForegroundServiceDidNotStartInTimeException` 义务。
- **每次 `onStartCommand` 都必须无条件调用 `startForeground()`**：通知 action 会重入 `onStartCommand`，缺一次就会被判定「未进入前台」而崩溃。`ACTION_STOP` 是唯一例外，它提前 `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` 并直接 return。
- **`keysVisible == false` 时编辑态会整个空掉**，所以「显示/隐藏按键」按钮必须在**编辑态操作条**里也有，不能只放通知栏。
- **从通知栏点「编辑布局」进编辑态时，通知栏收起的过程会抢走悬浮窗焦点**，此时 View 层 `setOnKeyListener` 收不到按键（按钮一直显示 ＋）。已在 `addOnWindowFocusChangeListener` 中镜像编辑态分支（`requestFocus` + `releasePointerCapture` + `clearActivePointers`），并补了 500ms 兜底重试。
- `R.drawable.logo_raw` **不能删**，`AboutScreen` 仍在引用。
- Android 13+ 通知栏是唯一控制面，启动悬浮窗前需 `POST_NOTIFICATIONS`；`MainActivity` 里用 `rememberLauncherForActivityResult(RequestPermission())` 的 `pendingLaunch` 模式实现「先授权，再启动」。

### 6.3 状态持久化的历史缺口

- `overlayOpacity` 旧代码只改 StateFlow 不落盘，重启即丢；已补 `FloatingWindowStateManager.saveOverlayOpacity()` 写穿。
- `overlayOrigin` 是 `FloatingWindowStateManager` 的运行期私有字段（由实测位置推出，见 `updateMeasuredPosition`），**不随 Profile 存储**；布局一致性依赖 Profile 的 `layoutScreenWidth/Height` 基准 + 实测重算。改动测量/缩放逻辑时两者要一起看。

### 6.3.1 重启后右上角按钮被往下挤（1.81 修复）

- 现象：只有贴近屏幕上边缘的一部分按钮（多在右上角）每次重启后被下移一截，左侧与右下角正常。
- 根因链条：
  1. 服务启动时悬浮窗尚未布局，`overlayViewport == null`，`applyActiveProfile()` 退回 `currentScreenSize()`（`WindowMetrics.bounds`，**整屏，含系统栏**），而预设保存的是**可用区域**坐标系。
  2. `LayoutProjection.position()` 把坐标归一化后 `coerceIn(0f, 1f)`；被拖到 `y<0`（编辑态允许压进上边缘）的按钮在归一化时成为负数，被压成 0，投影结果为 `margin = size*(scale-1)/2`。
  3. 悬浮层首帧到达时 `onOverlayFrame()` 立即 `persistActiveProfile()`，把这份被压平的坐标连同整屏基准写回预设，之后重启都停在新位置。
- 修复（三处，缺一不可）：
  1. `projectLayout` 在 `from ≈ to` 且未旋转时**原样返回**，不做归一化/夹取。
  2. `onOverlayFrame` **不再自动写盘**，只更新 viewport 并重新投影；持久化仅由用户操作触发。
  3. `persistActiveProfile` 只在完成首次投影后写 `items`，并用本次 apply 的 `layoutViewport` 作为基准；未投影时保留预设原有 items/layout，避免清空。
- 回归用例：`LayoutProjectionTest.sameSizeProjectionKeepsEdgeButtonsExactlyInPlace`（同尺寸必须保留负 y）。
- **红线：不要在 `from == to` 的路径上恢复归一化夹取，也不要在帧变化回调里写盘。**

### 6.4 批量改动的注意事项

仓库里 LF 与 CRLF 两种行尾同时存在，写脚本批量替换时必须按文件探测再还原，否则整个文件都会显示成改动。

- 读取 → 探测原文件是否含 `\r\n` → 归一化成 `\n` → `.Replace(旧, 新)` → 按探测结果还原 → 以 UTF-8 **无 BOM** 写回。
- 每个锚点替换前先断言匹配次数**恰好为 1**，否则抛错。静默匹配 0 次会让补丁看起来成功、实际没改。
- **here-string 的坑**：`@'...'@` **不包含末尾换行**，拼接 `$a + $b` 很容易把两行粘成一行（本项目就因此出过 `}                Card(` 的语法错误）。拼完记得显式补 `` `n ``。
- 临时脚本用完要删掉，不要留在仓库里。

---

## 7. 与下一个 AI 的协作建议

- **先 `Read` 再改**。状态耦合很重（`FloatingWindowStateManager` 946 行、`EventHandler` 856 行），凭猜测改动的代价很高。
- 涉及坐标 / 布局基准 / 预设加载 → 回看第 5 节。
- 涉及 WASD / 开火 / 注入触点 → 回看第 6.1 节。
- 涉及通知栏 / 前台服务 / 权限 → 回看第 6.2 节。
- 用户的既有偏好（已被明确要求过，不要回退）：
  - 中文沟通；
  - 功能迭代用版本号推进；
  - 每个功能要有**独立开关**，可按需启用/禁用；
  - 极端在意**跟手性**，拒绝任何引入延迟的实现；
  - 不喜欢屏幕上多余的常驻 UI（控制入口已迁到通知栏，屏幕无悬浮气泡）；
  - 不喜欢半透明窗口；偏好侧边栏常驻展开、按钮样式统一、面板间距充裕。

---

## 8. 检测风险与使用边界（重要）

本项目是外设模拟 / 输入注入工具，**不做任何「防检测」承诺**。以下是产物最容易被识别的点，按可检测性从高到低排列。

### 8.1 注入事件不是真实触摸

- 所有触点都由 Shizuku 经系统输入派发器注入，事件带系统注入来源 / 标记，缺少真实触摸屏的设备身份与硬件时间戳特征。
- 虽然补了 `touchMajor / touchMinor / toolMajor / toolMinor`，但压力、接触面积、扫描抖动是恒定值或程序生成的；真实手指每个点都不一样。
- 拟人化只改变轨迹和间隔，**不能伪造硬件级特征**。

### 8.2 悬浮窗与授权组合

- `SYSTEM_ALERT_WINDOW` + 前台服务 + Shizuku / ADB 授权，是很多游戏和反作弊会主动枚举的信号。
- 部分游戏直接禁止悬浮窗或检测 overlay，被看到就可能判定违规。

### 8.3 行为特征

- 宏的固定间隔、轨迹过于规整、长时间零抖动、重复节奏一致；拟人化能降低规律性，但无法消除。
- 高回报率鼠标在注入端会降采样（保留总位移、丢弃中间采样点），极端情况下轨迹采样率与真实触摸不同。

### 8.4 安装包与权限面

- 侧载 APK（非应用商店）+ `REQUEST_INSTALL_PACKAGES` + 悬浮窗 + `QUERY_ALL_PACKAGES`（读取已安装应用用于选游戏），会被部分安全软件标为高风险；这是权限组合导致的，不等同于恶意代码。
- 应用内更新从 GitHub Release 下载 APK。Android 安装器会校验签名，不同签名的包无法覆盖已安装应用；但仓库账号若被入侵并发布新版本，仍有供应链风险。建议只从本仓库 Release 下载，并核对版本号与文件大小。

### 8.5 使用边界

- 多数竞技游戏的用户协议把键鼠映射、宏、自动点击视为外设模拟或作弊；被检测可能导致警告、回滚成绩或封号，风险由使用者承担。
- 不建议在排位、比赛、带内核反作弊的游戏里使用；不要用宏做自动压枪、连发这类明显非人类的操作。
- 本项目不读写游戏内存、不修改游戏文件、不注入游戏进程，只做系统级触摸注入；这不会让它「不可检测」。
