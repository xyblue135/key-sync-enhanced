# KeySync Enhanced — AI 交接文档

> 面向下一个接手本项目的 AI / 开发者。最后更新：2026-10-02
> 阅读顺序建议：第 1、3 节建立全局观 → 第 5、6 节是「改代码前的红线」→ 第 7、8 节是最新进度与待办。

---

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
| versionCode / versionName | 21 / `1.45-android16` |
| debug 变体 | applicationId 后缀 `.fpsdebug`，versionName 后缀 `-debug` |
| Kotlin / Java | 17 |

依赖要点：Jetpack Compose（含 `constraintlayout-compose`）、Hilt 2.55（KSP）、kotlinx-serialization、DataStore Preferences、Navigation Compose、Shizuku API。

---

## 2. 构建与验证

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain -q
.\gradlew.bat :app:assembleDebug --console=plain -q
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 31 MB）

环境依赖：`local.properties` 中 `sdk.dir=C\:\\Users\\xyblu\\AppData\\Local\\Android\\Sdk`

关于验证的现实情况：

- `app/src/test` 有 21 个 JVM 测试类（97 个用例），覆盖 `domain/` 下的纯状态机与 `model/` 的序列化；`app/src/androidTest` 有普通按钮与宏编号点的拖动回归测试（另保留模板测试）。
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
| `domain/JoystickMotion.kt` | WASD 虚拟摇杆的「重锚点」逻辑（见 6.1，改动风险最高的小文件） |
| `domain/MultiModeTouchHandlerImpl.kt`、`TouchToMouseTranslator.kt` | 点击 / 按住 / 滑动等手势模式与鼠标翻译 |
| `data/mapping/MappingPresetRepository.kt` | 读取 `data/mapping/*.json` 预设 |
| `data/local/DataStoreManager.kt` | DataStore 读写（单一配置仓） |
| `ui/overlay/FloatingWindow.kt` | `ItemsContainer`（按键渲染）、`EditToolbar`（编辑态操作条）、`MenuItems`（加号菜单） |
| `ui/overlay/ContainerItems.kt` | 单个可拖拽按键的渲染与手势 |

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
3. 加载及旋转时统一通过 `projectLayout()` 投影。普通按键按扣除按键尺寸后的可移动范围分别换算 X/Y，WASD 点按视口宽高比例换算；运行期布局使用自己的 `layoutViewport`，不能把旧坐标直接标为新基准。
4. **游戏界面必须横屏**，禁止把预设做成竖屏。
5. **交换对（`swapPairs`）只修改按钮的物理布局位置**，不改键位绑定与名称。参与交换的按钮需维护 `anchorPosition`：普通拖动时 `position` 与 `anchorPosition` 同步，交换时只改 `position`。
6. **拟人化必须零延迟**：只允许通过调整注入触点位置 / 轨迹随机性实现，**禁止**添加等待、渐进、缓冲逻辑。抖动循环本身约 140ms 重掷一次（`EventHandler.humanizeScope`），这是允许的，因为它不阻塞按键响应路径。
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

## 7. 历史改动：通知栏控制入口（已提交）

主题：**控制入口从屏幕悬浮气泡迁移到通知栏**（用户要求「更隐蔽」）。

- 屏幕上那枚 50dp 悬浮气泡**彻底删除**，游戏过程中屏幕上不再有任何常驻控件。
- 通知栏成为唯一常驻控制面：点通知正文 → 设置页；三个 action = `编辑布局 / 退出编辑`、`隐藏按键 / 显示按键`、`关闭`；action 标签随状态自动刷新（`observeNotificationState()` 用 `combine(isEditMode, keysVisible)` 驱动 `notify`，并用 `notificationObserverStarted` 防止重复挂载）。
- 编辑态保留极简操作条 `EditToolbar`（`＋` 添加按键 / 显隐 / 完成），**只在编辑态渲染**。
- 原气泡里的设置面板拆解：预设切换与按钮大小设置页本来就有；**指针灵敏度**与**悬浮透明度**补进设置页；设置页底部新增「关闭悬浮窗」按钮。
- 全局重命名：`isBubbleExpanded` → `isEditMode`，`onFloatingBubbleClick` → `toggleEditMode`。
- 删除三个 Compose 函数：`FloatingBubble`、`SettingsLayout`、`PreviewSettingsLayout`。

`git diff --stat`（11 个文件，+587 / -509）：

```
 app/src/main/AndroidManifest.xml                   |   1 +
 .../MainActivity.kt                                |  80 +++-
 .../MainActivityViewModel.kt                       |  12 +
 .../data/local/DataStoreManager.kt                 |  17 +
 .../domain/EventHandler.kt                         |  82 +++-
 .../domain/JoystickMotion.kt                       |  16 +
 .../service/FloatingBubbleService.kt               | 491 +++++++--------------
 .../service/FloatingWindowStateManager.kt          |  65 ++-
 .../ui/SettingsScreen.kt                           | 115 +++++
 .../ui/overlay/FloatingWindow.kt                   | 202 +++------
 app/src/main/res/values/strings.xml                |  15 +
```

状态：`compileDebugKotlin` 与 `assembleDebug` **均已通过**，产物 `app/build/outputs/apk/debug/app-debug.apk`。
这批改动已提交为 `481b5a3 增加随机抖动`。**真机验证仍未完成**（见第 8 节）。

---

## 8. 当前版本：1.45 鼠标扩展与独立点击宏

- 原生识别后退／前进侧键，合并 Android 鼠标事件与鼠标来源的 Back/Forward 键事件，避免重复触发和误返回。
- 滚轮上／下／左／右可以绑定普通点击或宏；小数增量累计到完整刻度，已打开的旧轮盘优先使用滚轮。
- 每个 Profile 保存独立 `macros`。宏由有序编号点构成，不引用 Tab/Esc 等普通按钮；每点使用相对悬浮视口的归一化中心坐标。
- 设置 → 预设右侧菜单 →「点击宏（顺序点位）」管理宏；游戏通知栏 →「编辑布局」中拖动编号点。
- 每宏独立开关，首次创建默认停用；最多 16 个宏、每宏最多 16 点，步骤间隔 0–5000ms。每次点击保持 50ms，第一步即时下按，间隔由主线程定时器调度，不阻塞普通输入。
- 同时只执行一个宏，执行中再次触发不排队；宏优先于同键普通映射。失焦、进入编辑、映射重建、切换预设和关闭服务均取消剩余步骤。
- 宏随预设复制、导入导出、持久化，老预设缺少 `macros` 时默认为空；导入校验坐标、间隔、重复触发键等。

使用及真机回归说明见 `docs/MOUSE_AND_MACROS.md`。

### 1.44 稳定性修复（保留）

- 通知权限拒绝时不启动映射；启动前还检查应用通知开关和 `channel1` 频道。禁用时提供系统设置入口，开启后需再次点击游戏。
- 悬浮层失去窗口焦点时统一释放触点、停止抖动、清除按键高亮和鼠标相对位移基准；恢复焦点只恢复捕获，不重放旧按住状态。
- 预设读取失败时进入临时会话：禁止覆盖保存 Profile 和 active id，跳过迁移，尽可能备份损坏 JSON。备份失败及整个 Preferences 文件损坏也不能自动清空原数据。
- 新增存储故障回归测试，覆盖临时 I/O 失败、JSON 损坏、备份失败、旧备份保护、取消、首次启动与实际损坏文件保留。
- `master` push / PR 运行单测、Lint、debug 构建。正式发布只由 `v*` 标签或手动触发，标签必须匹配 Gradle 版本；测试和 Lint 成功后才能签名发布。
- Android 14 以下启动前台服务时不传入仅 API 34 支持的 `specialUse` 类型。

真机操作及预期结果见 `docs/STABILITY_VALIDATION.md`。本地 JVM 测试和构建不等于真机验证。

---

## 9. 与下一个 AI 的协作建议

- **先 `Read` 再改**。状态耦合很重（`FloatingWindowStateManager` 946 行、`EventHandler` 856 行），凭猜测改动的代价很高。
- 涉及坐标 / 布局基准 / 预设加载 → 回看第 5 节。
- 涉及 WASD / 开火 / 注入触点 → 回看第 6.1 节。
- 涉及通知栏 / 前台服务 / 权限 → 回看第 6.2 节。
- 用户的既有偏好（已被明确要求过，不要回退）：
  - 中文沟通；
  - 功能迭代用版本号推进；
  - 每个功能要有**独立开关**，可按需启用/禁用；
  - 极端在意**跟手性**，拒绝任何引入延迟的实现；
  - 不喜欢屏幕上多余的常驻 UI（本次删除悬浮气泡就是这个诉求）；
  - 不喜欢半透明窗口；偏好侧边栏常驻展开、按钮样式统一、面板间距充裕。