
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
| versionCode / versionName | 29 / `1.53-android16` |
| debug 变体 | applicationId 后缀 `.fpsdebug`，versionName 后缀 `-debug` |
| Kotlin / Java | 17 |

依赖要点：Jetpack Compose（含 `constraintlayout-compose`）、Hilt 2.55（KSP）、kotlinx-serialization、DataStore Preferences、Navigation Compose、Shizuku API。

### 1.1 基础模式与完整模式

同一个 APK 内置两档功能模式，默认**完整模式**。切换入口在设置页顶部的「功能模式」卡片，改动立即生效并持久化（全局键 `feature_tier`）。

| 能力 | 基础模式 | 完整模式 |
|---|---|---|
| 键鼠 → 触屏映射（预设、WASD、开火、倍镜、滚轮四向） | ✅ | ✅ |
| 悬浮窗、编辑布局、按键显隐、通知栏控制面 | ✅ | ✅ |
| 拟人化抖动、快捷换位（X / 1）、指针灵敏度、透明度、状态提示条 | ✅ | ✅ |
| 点击宏（顺序点位、每宏独立开关、执行） | ❌ 停用且入口隐藏 | ✅ |
| 预设导入 / 单预设导出 / 复制全部预设 | ❌ 隐藏 | ✅ |

- **基础模式只裁剪「宏 + 便携配置」**：按键映射与悬浮窗的全部能力都保留。
- **数据零风险**：基础模式只是不再把 `Profile.macros` 挂到键位分发上（`syncMacros` 传空列表），宏数据原样保留；切回完整模式立即恢复，不需要重新配置。
- 基础模式下编辑布局不渲染宏编号点，预设菜单里也不显示「点击宏」与「导出」。
- 存储值缺失、非法或旧版本升级时一律回退到**完整模式**，避免升级后功能静默消失。
- 换了模式不需要重启服务，也不影响当前激活的预设与布局。

---

## 2. 构建与验证

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain -q
.\gradlew.bat :app:assembleDebug --console=plain -q
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 31 MB）

环境依赖：`local.properties` 中 `sdk.dir=C\:\\Users\\xyblu\\AppData\\Local\\Android\\Sdk`

关于验证的现实情况：

- `app/src/test` 有 26 个 JVM 测试类（137 个用例），覆盖 `domain/` 下的纯状态机与 `model/` 的序列化；`app/src/androidTest` 有普通按钮与宏编号点的拖动回归测试（另保留模板测试）。
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
3. `EventHandler.handlePointerMove()` 分流：轮盘激活时转轮盘；射击模式乘 `pointerSensitivity` 转视角；光标模式 1:1 移动光标，且**只有按住左键时才拖动 LMC 触点**（否则触点会被拖出屏幕，导致游戏重置全部触点、WASD 断触）。
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
- 宏的 50ms 点击与拟人化抖动循环（100–220ms 随机）不在这条路径上，和高回报率互不影响。
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

### 3.3 检查更新（手动触发）

更新源就是本仓库：`github.com/xyblue135/key-sync-enhanced`。

- 入口在「关于」页的**检查更新**按钮；页面打开时**不会**自动请求，只有点击才发一次查询。
- `data/external/GitHubReleaseClient.kt` 读取 `releases/latest`；仓库只打了标签、还没建 Release 时回退到 `tags` 的第一项。8 秒超时，无后台轮询、无自动下载。
- `util/VersionComparator.kt` 只比较版本号核心（开头的点分数字），因此 `v1.48-android16` 与 `1.48` 视为同一版本，`-android16` / `-debug` 后缀里的数字不会造成误报。
- 判定有更新时显示「前往下载」，跳转 Release 页面；失败时展示具体原因（HTTP 状态 / 超时 / 仓库暂无发布）。
- 需要 `INTERNET` 权限；该权限只用于这一条手动查询。

### 3.4 预设导入导出

预设（键鼠布局 + 键位绑定 + 宏）支持两种方式带走：

| 方式 | 入口 | 说明 |
|---|---|---|
| 文件 | 预设卡片「导出到文件」/ 单预设导出对话框「保存为文件」/ 「从文件导入」/ 对话框「从文件选择」 | 走系统文件选择器（SAF），无需存储权限；跨设备拷贝 `.json` 即可 |
| 剪贴板 | 「复制全部预设」/ 导出对话框「复制」/ 导入对话框「粘贴」 | 适合快速分享单套预设 |

- 文件格式与剪贴板完全一致：单预设为 `Profile` JSON，全部预设为 `ProfileBundle { version: 1, profiles: [...] }`。
- 两种入口共用同一套校验：版本号、宏点位、重复触发键、预设 ID 去重、同名自动加「副本 N」。
- 导入一律作为**独立副本**加入，不覆盖现有预设；快捷键冲突时保留切入键但停用。
- 导入导出属于「完整模式」；基础模式下入口隐藏（见 1.1）。

### 3.5 拟人化（模拟人手）

目标是让注入触点在线迹、时序、触点参数上不再呈现机械规律，**同时保持零延迟**：只改位置与触点数值，从不增加等待。

| 维度 | 行为 |
|---|---|
| 落点分布（普通按键） | 圆内均匀采样（`EventHandler.jitterOffset()`），半径上限为 `强度 × 40px`；强度 0 时返回零偏移 |
| 落点分布（WASD） | 绕摇杆中心**旋转 ±(2°–12°)** 并允许半径 ±2%–8% 变化，**半径不低于原值的 97%**（`jitterTarget()`）。各向同性偏移会把触点拉回摇杆死区，导致强度调高后角色完全不动 |
| WASD 持键 | 每 100–220ms 随机间隔重掷，只发 MOVE（`JoystickMotion.jitter()`），**绝不 reanchor**（见 6.1） |
| 普通按键持键 | HOLD / MIXED 同样随机间隔微抖，只对单点落位（`position == end`）生效；滑动类按键跳过，避免把触点拽回锚点 |
| TAP 点击 | 按下与抬起之间在 25ms 处补一帧小幅位移（1.5–6px），不改变抬起时刻 |
| 触点参数 | 压力 `0.82–1.00`、`size` `0.85–1.00`、接触面积 ±15%，每个触点在按下时独立取样（`EventManagerImpl`） |
| 关闭行为 | 两路拟人化都关闭时恢复固定压力/面积；强度为 0 时位置零偏移且不启动任何循环 |

拟人化属于基础模式也保留的能力（见 1.1）。它只能改变轨迹与触点参数，无法改变事件的设备身份（`deviceId`）。

### 3.6 快捷换位（X / 1）与横竖屏投影

**快捷换位**是预设级开关（`Profile.quickSwapEnabled`，默认开启），不需要任何手动配置：

| 按键 | 行为 |
|---|---|
| `X` | 让「绑定 R 的按钮」与「绑定鼠标右键（`KEYCODE_RMC`）的按钮」互换屏幕位置 |
| `1` | 两者回到各自的 `anchorPosition` |

- A / B 每次按键时按**当前绑定**实时解析，所以重绑 R 或右键后依然正确。
- 只改位置、不动绑定；刷新坐标走 `EventHandler.repositionItems()`，不会释放正在按的触点。
- 开关位置：设置页 → 预设行的溢出菜单 →「R ↔ 右键（X 换位 / 1 还原）」。
- 换位键本身仍会透传给游戏（与旧交换对一致）。

**2-3-4 连点**（`Profile.tapSequence234Enabled`，默认开启）：按一次 `3` 依次点击 `2 → 3 → 4`，用于一键切换武器组。

- 三个触点是 45ms 按下 / 110ms 间隔，串行执行；重复按 3 不会叠加成多条序列。
- 三个按钮缺任何一个就不接管，`3` 回落到原本的单键映射，避免"按了没反应"。
- 开关位置同快捷换位：设置页 → 预设行溢出菜单 →「按 3 依次点 2/3/4」。

**编辑条可拖动**：编辑态操作条（`＋ / 显隐 / 提示 / 完成`）左侧新增 `⠿` 手柄，按住即可把整条移到不遮挡目标位置的地方。手柄使用独立拖动手势，不会和按钮点击冲突。

**单按钮尺寸**：每个按钮（通用按钮 / 固定键 / 可取消键）新增 `customSizeDp`，在齿轮配置框里用滑块单独调节（28–110 dp），与全局 `buttonScale` 叠乘。位置坐标不受影响，触点中心由测量结果自动跟随，改大改小都不会让点击跑偏。

**横竖屏**：`projectLayout()` 检测基准与当前视口朝向是否一致。交叉时整体旋转 90°（横→竖顺时针、竖→横逆时针，两者互为逆运算），而不是把 X/Y 各自拉伸——后者会把横屏布局直接"搬"到竖屏，左右两侧按钮全部错位。同朝向的不同分辨率仍按轴独立缩放。

**分辨率数字差异**：预设记录的 `layoutScreenWidth/Height` 是**悬浮层可用区域**（扣掉状态栏/导航栏，例如 1200×2466），设备信息显示的是整块屏幕（1200×2608）。两者相差的就是系统栏高度，不是 bug。

---

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
| `humanization_strength` | Float | 拟人化强度 0..1（全局） |

模型（`model/`）：

- `Profile`：`id` / `name` / `items` / `appConfig` / `swapPairs` / `macros` / `layoutScreenWidth` / `layoutScreenHeight`。
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
5. **快捷换位（`quickSwapEnabled`）只修改按钮的物理布局位置**，不改键位绑定与名称；A/B 按当前绑定实时解析（R 与 `KEYCODE_RMC`），位置恢复用 `anchorPosition`。旧 `swapPairs` 数据仍兼容执行，但 UI 已不再提供手动配置。<br>交换后刷新坐标只能调用 `EventHandler.repositionItems()`，**禁止调用 `updateKeyMapping()`**——后者会 `eventInjector.clear()` 释放全部触点，按一次换位键就会把正在按着的 W / 开火松开。
6. **拟人化必须零延迟**：只允许通过调整注入触点位置 / 轨迹 / 触点参数实现，**禁止**添加等待、渐进、缓冲逻辑。抖动循环的间隔随机 100–220ms（`EventHandler.humanizeScope`），它不阻塞按键响应路径。
7. 拟人化需**独立开关 + 强度滑杆（0 表示关闭）**，且**全局生效**（对所有预设统一），不要做成按预设存储。
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
