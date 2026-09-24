# KeySync Enhanced — AI 交接文档

> 面向下一个接手本项目的 AI / 开发者。最后更新：2026-09-24
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
| versionCode / versionName | 19 / `1.43-android16` |
| debug 变体 | applicationId 后缀 `.fpsdebug`，versionName 后缀 `-fps-sprintfix1` |
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

- `app/src/test` 与 `app/src/androidTest` 目录存在但**是空的，项目没有任何自动化测试**。
- 因此「验证」= 编译通过 + 真机手动验证。**编译是唯一可用的自动化回归网**，重命名或删除符号后要立刻跑一次，让编译器点名遗漏。
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

- `Profile`：`id` / `name` / `items` / `appConfig` / `swapPairs` / `layoutScreenWidth` / `layoutScreenHeight`。
  后两个字段是**布局保存时的屏幕分辨率基准**，`0` 表示未记录；横竖屏由宽高关系隐含决定。
- `DraggableItem`：拖拽按键，坐标为**归一化 0..1**；含 `DraggableItem.FixedKey`，以及 `DraggableItemType` 的 `WASD_KEY` / `KEY` / `SHOOTING_MODE` / `WALK_TOGGLE` 等。
- `AppConfig`：`buttonScale`、`pointerSensitivity`、`deleteDataOnRemove`、`screenMirrorCompatMode`、`themePreference` 等。

预设资产：`data/` 通过 `sourceSets["main"].assets.srcDir("$rootDir/data")` 挂成 assets，`data/mapping/**` 共 24 个 JSON（含 `index.json` 索引），分类为 universal / fps / moba / mmorpg / action / racing 与 `templates/blank.json`。**新增预设不需要改 Kotlin**。

---

## 5. 硬性约束（改代码前必读）

1. **坐标一律归一化**（相对屏幕宽高的 0..1），禁止存绝对物理像素。
2. **布局基准必须持久化**：每个 Profile 记住保存时的 `layoutScreenWidth/Height`；**加载/保存预设时不要用当前屏幕去覆盖已记录的基准**。迁移旧数据时基准分辨率用「当前屏幕」，不要写死固定值。
3. 加载预设时若基准方向与当前屏幕相反（如竖屏基准 vs 横屏游戏），**直接使用布局中存储的绝对坐标**，并把当前屏幕设为新基准。
4. **游戏界面必须横屏**，禁止把预设做成竖屏。
5. **交换对（`swapPairs`）只修改按钮的物理布局位置**，不改键位绑定与名称。参与交换的按钮需维护 `anchorPosition`：普通拖动时 `position` 与 `anchorPosition` 同步，交换时只改 `position`。
6. **拟人化必须零延迟**：只允许通过调整注入触点位置 / 轨迹随机性实现，**禁止**添加等待、渐进、缓冲逻辑。抖动循环本身约 140ms 重掷一次（`EventHandler.humanizeScope`），这是允许的，因为它不阻塞按键响应路径。
7. 拟人化需**独立开关 + 强度滑杆（0 表示关闭）**，且**全局生效**（对所有预设统一），不要做成按预设存储。
8. 键位交换热键必须保留；「预设切换热键」已被要求删除，不要加回来。
9. **全局功能配置放 `DataStoreManager`**（全局键），不要塞进 `Profile`。
10. 坐标换算统一使用等比缩放（`sSize`），不要按宽高分别拉伸。

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

### 6.4 工具链 / 工作方式（对 AI 尤其重要）

- **无法用普通文件编辑工具直接写 `D:` 盘**：本会话的工作目录被限制在 `C:\Users\...\work-mode-projects\6ab361389e05498d436bad83`。要改 D 盘文件时必须写 `.ps1` 脚本，用 `[IO.File]::ReadAllText / WriteAllText` 操作。
- **含中文的 `.ps1` 必须先转成 UTF-8 BOM 再执行**，否则 PowerShell 5.1 会按 ANSI 解析导致中文乱码：
  `$t=[IO.File]::ReadAllText($p,[Text.Encoding]::UTF8); [IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($true)))`
- 推荐补丁流程：读取 → 把 `\r\n` 归一化成 `\n` → `.Replace(旧, 新)` → 归一化回 `\r\n` → 以 UTF-8 **无 BOM** 写回。并在脚本里对每个锚点做 `Contains` 检查，最后打印 `MISS` 列表，避免静默失败。
- **here-string 的坑**：`@'...'@` **不包含末尾换行**，拼接 `$a + $b` 很容易把两行粘成一行（本项目就因此出过 `}                Card(` 的语法错误）。拼完记得显式补 `` `n ``。
- 临时脚本用完要删掉，不要留在仓库里。

---

## 7. 最近一次改动（尚未提交）

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
**注意：这批改动尚未 git commit，也尚未在真机上验证过。**（`git log` 最新提交仍是 `60d95b6 新增键位交换对 + 修复横屏 + 移除切换热键`）

---

## 8. 待办 / 建议下一步

1. **真机验证**（目前只做到编译通过）：
   - 通知栏三个 action 是否都生效，标签是否随状态正确翻转；
   - 点通知正文能否稳定进入设置页（trampoline 限制下的实际表现）；
   - 从通知栏进编辑态后，字母键 / 方向键能否被悬浮层捕获（焦点竞态）；
   - 拒绝 `POST_NOTIFICATIONS` 后是否有合理提示，以及用户后续是否能在系统设置里补授权。
2. 设置页新增的「关闭悬浮窗」按钮**不区分服务是否在运行**（未运行时点击是 no-op），如需可加运行状态判断。
3. 提交前确认仓库里没有残留临时脚本。
4. 可选清理：`FloatingWindow.kt` 中因删除函数而变成孤儿的 import（如 `Icons.Rounded.Done`、`Profile`）仅为 warning，目前未清理。

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