# KeySync Enhanced 调试记录（踩坑与修复）

本文件记录开发过程中**真实出现过的问题**：现象 → 根因 → 修复 → 防回归红线。
README 讲「怎么用、怎么改」，这里只讲「哪里会炸、为什么炸、怎么修的」。

维护规则：

- 每条必须有**可观察的现象**，不要写成版本流水账。
- 修复必须写清**改了哪个文件/哪个函数**，以及怎么验证。
- 标 **红线** 的条目，改动相关代码前必须先读，历史上都复现过。
- 新坑修完后直接追加，不按版本号归档。

---

## 1. 事件注入链路

### 1.1 同步注入导致「不跟手」

- **现象**：按键响应正常，但视角移动、拖拽总有延迟感，像隔了一层。
- **根因**：早期用 `injectInputEvent(event, WAIT_FOR_RESULT)`，每个事件都要等 InputDispatcher 处理完才返回；高频率 MOVE 时等于把注入线程锁死在单帧上。
- **修复**：改为 `INJECT_INPUT_EVENT_MODE_ASYNC`（0），投递即返回。见 `data/external/ShizukuSystemServerAPi.kt`。
- **验证**：连发 MOVE 时注入线程不应被单个事件阻塞；主观手感无「粘滞」。
- **红线**：不要为了「确认注入成功」改回 `WAIT_FOR_RESULT`，这正是本项目拒绝的实现方式。

### 1.2 高回报率鼠标导致位移积压（甩枪过头 / 鼠标停了视角还在转）

- **现象**：1000Hz 鼠标快速甩动时，准星移动量明显多于实际手部移动，停下后视角还在继续转。
- **根因**：每条 MOVE 都单独入注入队列，而注入吞吐低于事件频率，队列越积越长。合并逻辑早期还会**丢弃旧位移**，导致总位移不足。
- **修复**：
  - `domain/EventInjectorImpl.kt`：专用单线程队列串行注入，保证 DOWN / MOVE / UP 不乱序。
  - `domain/PendingMoves.kt`：同一触点两次 flush 之间的位移**累加**成一条 MOVE，总位移不丢，只丢中间采样点。
- **正确行为**：注入能力不足时是**降采样**，不是积压、也不是丢距离。
- **红线**：任何改动都不能让合并逻辑丢掉累计位移；也不能让 MOVE 与 DOWN/UP 走不同队列。

### 1.3 Shizuku 断开后「虚拟手指」卡在屏幕上

- **现象**：Shizuku 被杀或 binder 断开后，游戏里出现按住不放的触点，角色自己走、镜头自己转。
- **根因**：注入端持有的按下状态没有随连接断开而释放。
- **修复**：`MainActivityViewModel` 注册 `Shizuku.OnBinderDeadListener`，回调里除了更新状态，还必须调用 `stateManager.clearActivePointers()`。失焦、停止服务、切换预设同样要释放。
- **验证**：注入过程中强杀 Shizuku / 退出应用，游戏内不得残留按住状态。

---

## 2. WASD 虚拟摇杆

### 2.1 Shift 疾跑时「向后顿一下」（历史最高风险）

- **现象**：纯 WASD 走位正常，叠加 Shift 疾跑就一顿一顿，像网卡。
- **根因**：`JoystickMotion.update()` 的重锚判定写成 `(lastMask or mask) and sprintBit != 0`，用的是**或**。疾跑期间每次 `update()` 都被判成「疾跑切换」，于是 `release()` + 重新 `start()`，从摇杆中性位重新起拖，游戏读到的是「松手再推」。过去 `update()` 只在物理按键时调用，频率低看不出来；固定的 140ms 拟人化抖动循环把它放大成了明显卡顿。
- **修复**：
  - 新增 `JoystickMotion.jitter()`：**只发 MOVE，绝不 reanchor**，抖动循环改调它。
  - `wasdMap` 没有对应项时停掉抖动循环。
  - `updateKeyMapping()` / `clear()` 重置映射时调用 `stopWasdJitter()`。
- **红线**：`jitter()` 与 `update()` 职责必须分离。任何让抖动路径触发 reanchor 的改动都会复现此 bug。

### 2.2 旋转屏幕 / 换视口后疾跑失效

- **现象**：旋转屏幕或改变悬浮窗视口后，WASD 还能走，但疾跑按不出来。
- **根因**：`updateKeyMapping()` 在 Compose 完成测量之前执行，读到的是上一个布局的坐标。疾跑注入点必须落在实际屏幕位置的外侧才能越过游戏阈值。
- **修复**：`EventHandler.refreshWasdGeometry()` 在测量完成后重建摇杆条目；**只重写 WASD 条目**，不触碰普通按键与开镜触点。
- **验证**：旋转屏幕后依次测 W / A / S / D + Shift，四个方向都要能疾跑。
- **注意**：这里仍然不能触发 reanchor，2.1 的红线不变。

### 2.3 持键微抖误触发「重新按下」

- **现象**：按住开火 / 连发键时变成「按一下停一下」，或按住方向键时角色抽搐。
- **根因**：抖动循环如果调用 `injectPointer`（重新 DOWN）而不是移动已有触点，会打断正在生效的按压状态；滑动类按键（`position != end`）若按锚点重定位，会把触点从矩形落点拽回角落。
- **修复**：`EventHandler.startKeyJitter()` 只调用 `transFormGesture`（`updatePointer` + ACTION_MOVE），且只对 `position == end` 的单点按键生效；`handleKeyUp`、`updateKeyMapping()`、`clear()` 都会停止对应抖动。
- **红线**：抖动路径永远不得调用 `injectPointer` / `releasePointer`，与 `jitter()` 不得 reanchor 同源。

### 2.4 拟人化强度调高后 WASD 完全不动

- **现象**：强度只有最低一档能正常走路，再往上调角色就完全不动（或频繁顿住）。
- **根因**：WASD 的方向点是「从摇杆中心向外推的位移」。早期实现对方向点做**各向同性**随机偏移（`position + jitterOffset()`），强度越大越容易把触点拉回中心，落进游戏的摇杆死区，判定为「没有推动」；同时 `update()` 侧的重锚判定不受影响，所以看起来是「突然不能走」。
- **修复**：新增 `EventHandler.jitterTarget(center, target)` —— 只绕中心旋转极小角度（最高 ±12°，仍在 8 向扇区内），半径仅允许 ±2%~8% 且**不低于原值 97%**，从数学上排除落回死区的可能。
- **验证**：强度 0 / 50 / 100 三档分别测 W、W+A、W+Shift，角色都应持续移动且不顿挫。

### 2.5 按换位键（交换对）会松开正在按的键

- **现象**：按住 W 走路时按一次换位键，角色停下，必须重新按 W；若在开火，连射也会中断。
- **根因**：`applySwap()` 早期调用 `eventHandler.updateKeyMapping(items)`，该方法内部 `eventInjector.clear()` 释放所有触点并重建 pointer id。而交换对只改了按钮位置，键位与手势状态完全没变，属于不必要的全量重建。
- **修复**：新增 `EventHandler.repositionItems(items)`，只覆盖受影响按键在 `keyMap` / `cancelKeyMap` 中的坐标，pointer id、触点与手势状态全部保留。操作因此是幂等的：连按还原键不会累计偏移，也不会卡住。
- **红线**：交换对、拖动按钮后刷新坐标这类「只改位置」的场景，一律走 `repositionItems()`，禁止调用 `updateKeyMapping()`。

### 2.7 快捷键录入被屏幕键盘吞掉 / 横竖屏键位错位 / 分辨率数字不一致

- **现象一**：交换对对话框里点按键框，软键盘弹出并挡住录入区，按 X / 1 没反应。
  - **根因**：依赖可聚焦 `Text` 的 `onKeyEvent` 收集按键，焦点会触发输入法，且对话框窗口里焦点不稳。
  - **修复**：取消手动配置，改为预设级开关「R ↔ 右键（X 换位 / 1 还原）」，A/B 按当前绑定实时解析，不再需要录入任何键。旧 `swapPairs` 数据仍兼容执行。
- **现象二**：横屏摆好的键位，切到竖屏后位置完全错乱。
  - **根因**：`projectLayout()` 对两个轴独立缩放，横屏 (2608×1200) 到竖屏 (1200×2608) 相当于把布局横向压扁、纵向拉长，按钮不是被旋转而是被拉伸。
  - **修复**：朝向交叉时整体旋转 90°（横→竖顺时针、竖→横逆时针，互为逆运算），同朝向仍按轴缩放。`LayoutProjectionTest` 增加两条旋转用例。
- **现象三**：设备信息显示 1200×2608，预设显示 1200×2466。
  - **结论**：不是 bug。预设记录的是**悬浮层可用区域**（扣除状态栏与导航栏，142px），设备信息显示整块屏幕。现在 UI 已标注「悬浮层可用区域，不含系统栏」。

### 2.8 编辑条挡住所摆位置 / 按 3 需要连点 2-3-4

- **编辑条**：原来固定在 `TopEnd`，无法避开目标区域。现在整条可拖动（左侧 `⠿` 手柄 + `detectDragGestures`），偏移只在当前编辑会话内有效，退出编辑复位。拖动必须放在独立手柄上，直接给整条加手势会和按钮点击抢事件。
- **2-3-4 连点**：预设级开关（默认开启）。`runTapSequence234()` 用 `eventHandler.handleKeyEvent` 串行注入 2/3/4（45ms 按下、110ms 间隔），并吞掉原始 `3`。注意：三个按钮缺任一时不接管，否则用户会以为按 3 失效；同时用 Job 互斥，避免连按叠加。

### 2.9 按钮太大 / 需要单独调尺寸

- 原来按钮尺寸只有全局 `buttonScale`，单个键无法调整。现在 `VariableKey` / `FixedKey` / `CancelableKey` 都带 `customSizeDp`（默认 50），齿轮配置框里用滑块调 28–110 dp；渲染时 `IconKey(sizeDp = item.customSizeDp.dp)`，全局 `buttonScale` 继续叠加。
- **注意**：`customSizeDp` 是渲染尺寸，`size` 仍是测量后的像素宽度（注入用的触点靠它算）。改尺寸后 `onSizeChanged` 刷新 `size`、`onGloballyPositioned` 重新上报 `touchCenter`，所以不要自己手算触点中心。
- **注意**：`DraggableItem` 是可变对象，改完字段必须 `_containerItems.value = _containerItems.value.toList()` 换新列表实例，否则 Compose 不会重组，滑块看起来"没反应"。

### 2.10 重新打开配置菜单会把工具条顶到一边 / 拟人化抖动太频繁
- **工具条位移**：`EditToolbar` 的根 `Box` 是 wrap-content，`＋` 菜单是同一个 Box 的子节点；菜单展开后 Box 被撑大，而 Box 在父级按 `TopEnd` 对齐，于是整条工具栏（含 `⠿` 手柄）被挤到左边——表现就是「重新打开配置菜单，按钮被顶到一边」。修复：根节点改为 `fillMaxSize()`，工具条与菜单各自 `align(TopEnd)`，菜单尺寸不再影响工具条位置。
- **附带防护**：任何配置对话框（`configuringFixedKey/VariableKey/Wasd`）打开时，`DraggableItem` 的 `enabled=false`，底层按钮不响应拖动。拖动手势绝不能穿透对话框。
- **抖动强度**：`jitterOffset()` 半径 `40px → 8px`、`jitterTarget()` 角度 `(2+10s)° → (0.4+2s)°`、半径扰动 `±(2%+6s%) → ±(0.4%+1.2s%)`、TAP 位移 `(1.5+4.5s)px → (0.3+0.9s)px`，整体为原强度的 1/5。
- **抖动频率**：原来固定 100–220ms；现在 `humanization_interval_ms`（默认 1000，200–3000ms）控制，实际间隔 ±20% 随机。新增「随机偏移间隔」滑杆。
- **红线**：抖动仍只发 MOVE、绝不 reanchor（见 6.1）；调间隔不要引入阻塞式 delay 到按键响应路径。

### 2.23 鼠标偶尔突然甩出 >180° 的大转向

- **现象**：正常转视角时偶发一次巨大转向，人从看前面直接变成看背后，之后又恢复正常。
- **根因**：不是游戏灵敏度问题，而是 `onMouseEvent()` 的绝对坐标回退路径。部分 ROM / 投屏端不提供 `AXIS_RELATIVE_X/Y`，只能拿 `rawX - lastMouseRawX` 当位移；一旦光标被系统回中、换了鼠标、悬浮窗重新捕获指针、或投屏端重新按下，`rawX` 会从完全不同的位置开始，单帧差值是几百像素。这个假位移再乘灵敏度（最高 3x）后注入，视角就被甩到背后。
- **修复**：
  - 新增 `MAX_MOUSE_DELTA_PER_EVENT = 240px`。绝对坐标路径上单帧差值超过它，视为断点：更新基准并丢弃这一帧，不注入。
  - `ACTION_DOWN / ACTION_HOVER_ENTER / ACTION_CANCEL / ACTION_OUTSIDE` 主动把 `lastMouseRawX/Y` 置 `NaN`，新一段输入从 0 位移开始。
  - 相对设备（`SOURCE_MOUSE_RELATIVE`）与触控板不受影响；射击模式下再对缩放后的位移做一次上限钳制。
- **红线**：绝对坐标只能用来求差，不能直接当位置；任何时候都不要把跨输入的 `rawX` 差直接乘灵敏度送进注入器。

### 2.22 点击宏支持标准间隔 / 自定义间隔

- **需求**：类似罗技宏，既能统一设一个「标准延迟」，也能每个点位单独设间隔。
- **数据**：`ClickMacro` 新增 `customIntervals`（默认 `true`）与 `standardIntervalMs`（默认 100）。默认值故意保持「自定义」，旧版本导出的宏没有这两个字段，升级后仍按每步 `delayAfterMs` 执行，节奏不变。
- **执行**：`ClickMacroRunner` 改走 `ClickMacro.intervalAfter(index)`：自定义模式读每步延迟，标准模式统一读 `standardIntervalMs`；最后一步之后仍然不等待。
- **校验**：`standardIntervalMs` 与每步延迟一样限制在 0–5000ms，导入非法值直接拒绝。
- **UI**：宏编辑框新增「标准间隔 / 自定义间隔」两个 chip；标准模式只显示一个总间隔滑杆，自定义模式才显示每个点位的间隔滑杆。

### 2.21 编辑态齿轮/删除图标过大且容易误删

- **现象**：每个映射按钮右下角挂 32dp 齿轮、右上角挂「×」；按钮本身可以缩到 28dp，图标反而比按钮还大，视觉上很别扭；「×」离按钮太近，滑动时误触就把映射删掉。
- **修复**：悬浮窗不再渲染任何齿轮/删除图标。**双击**按钮才打开对应设置框（绑定 / 点击模式 / 尺寸 / 滚轮半径 / 删除），删除只放在设置框底部（红色「删除按键」）；WASD 双击打开疾跑距离，删除同样在框内。
- **实现**：`DraggableItem` 新增 `onDoubleTap`，用独立的 `pointerInput + detectTapGestures(onDoubleTap)`；拖动仍由 `detectDragGestures` 处理。手指移动超过 touch slop 后拖动会消费位移，tap 检测自动取消，二者不会互相打架。
- **背包映射（`CancelableKey`）**：旧逻辑是「点按钮抢焦点 → 物理按键直接改绑定」，双击手势会被内部 `clickable` 消费。现在改成双击走 `KeyCaptureDialog`，新增 `FloatingWindowStateManager.updateCancelableKeyCode()` 统一写入；不要再给按钮内部加 `clickable`，否则双击收不到。
- **红线**：`onDoubleTap` 只在编辑态传入非空；配置框打开时 `draggingEnabled=false`，双击与拖动同时禁用，避免手势穿透。

### 2.20 光标模式按住鼠标无法拖动/滑动

- 1.60 清理「光标模式拖动」分支时连带删掉了按住移动触点的逻辑，结果按住左键上下滑、拖列表都没反应。
- 现在 `handlePointerMove()` 的非射击分支：只要 `cursorDownAt` 里还有按住的触点，就用 `transFormGesture(id, mousePointerPosition)` 把触点跟到光标位置；松开时才 release。
- 触点位置每次 MOVE 都由 `FloatingWindowStateManager` 先写入 `mousePointerPosition`（含屏幕边界钳制），因此拖到边缘不会越界。
- **红线**：非射击模式下的鼠标移动不能只更新视觉光标，必须同步移动已按住的触点，否则所有需要「按住拖动」的 UI 都会失效。

### 2.19 应用内自动更新

- 查询沿用 `GitHubReleaseClient`，1.7 起额外解析 `assets` 里第一个 `.apk` 的直链与 `body` 说明；只有 tag 没有 Release 时没有直链，按钮退回「前往下载」。
- 下载交给 `DownloadManager`（会跟随 GitHub 的 302 跳转，自己写 HttpURLConnection 容易漏掉重定向），保存到 `externalFilesDir/Download/updates/`。
- FileProvider 的 `file_paths.xml` 必须写 `Download/updates/`，因为 `setDestinationInExternalFilesDir(context, DIRECTORY_DOWNLOADS, "updates/x.apk")` 的相对根是 `externalFilesDir`。
- 必须声明 `REQUEST_INSTALL_PACKAGES`，否则 `canRequestPackageInstalls()` 永远 false、安装器拉不起来。
- 版本比较只看 `-` 之前的点分数字（`1.70-android16` → `1.70`），否则 `android16` 里的 16 会被当成更高版本。
- **坑**：`1.7` 数值上小于 `1.63`，发布 1.7 会让所有 1.63 用户判定为「已是最新」。要升到 1.7 时代号请写 `1.70`。
- 自动检查只放在 `LaunchedEffect(Unit)`，失败静默；不要在 onResume/轮询里查，避免游戏过程中产生网络请求。

### 2.18 光标模式点击策略（已定稿：不做区域屏蔽）

- 1.59 为了修「光标压在开火键上误开火」，把光标模式左右键全禁了 → 点不了屏幕。
- 1.62 改成「落在映射矩形内不注入」→ 用户反馈想点的那块恰好被映射按钮盖住时点不了，仍然不能用。
- **1.63 定稿**：光标模式左右键一律在光标位置注入原生点击，**不做任何区域屏蔽**。点到开火/右键按钮的屏幕位置就会真实开火/开镜，这是有意的取舍并写进 README。
- 原因：注入只携带坐标与按压，游戏无法区分 UI 点击与开火；任何「屏蔽」都等价于「某些位置点不了」。
- **红线**：不要再加坐标区域判断或双击/修饰键门槛，除非用户明确要求。

### 2.17 疾跑中松开 A 方向甩到 D / 顿一下

- 根因：`JoystickMotion.update()` 的 reanchor 条件是 `(lastMask or mask) and sprintBit != 0`——**只要在疾跑状态，任何换向都 release + 重新 DOWN**。松开 A 时游戏看到摇杆先回中再按下，表现为顿一下、方向短暂甩向另一侧。
- 现在：只有 `(lastMask xor mask) and sprintBit`（进出疾跑）或 pointer/center 变化才 reanchor；普通换向只发 MOVE（先中点再目标）。
- 同时把 WASD 的摆头幅度从整体强度里独立出来：`wasdSway`（1–100，默认 50，满值 ±1.5°），前进时的晃动可以单独调小。
- 测试 `JoystickMotionTest.directionChangesDuringSprintMoveWithoutReanchor` 锁死「疾跑换向不得 release」。
- **红线**：不要为了「浮动摇杆原点漂移」恢复每次换向都 reanchor，那会直接造成这个 bug。浮动原点的修正只在进出疾跑时做。

### 2.16 编辑条遮住右上角按钮 / 连点太慢

- 编辑条虽然可拖动，但展开时仍占着右上角一条区域，挡在上层的 Row 会吃掉右上角按钮的触摸，看起来像「按钮被顶掉」。
- 现在加「收起」：收成 36dp 的 `⠿` 圆点，`detectDragGestures` 拖、`detectTapGestures` 点一下展开；两个 pointerInput 分开挂，互不冲突。
- 2-3-4 原来固定 45ms 按下 / 110ms 间隔，一轮约 465ms。现在 `tapSequence234GapMs` 按预设存储（20–300ms，默认 60ms），按住时长取间隔一半，整轮约 200ms。
- **注意**：间隔调太小（<30ms）时部分游戏会丢点，需要按实际武器切换动画调。

### 2.15 切出鼠标后开火键/右键仍被点击

- 根因：光标模式（`!shootingMode`）下 `handleMouseButton()` 会调用 `simulateNativeClick()`，在光标位置注入一次 60ms 的点击。光标刚好压在开火/右键按钮上时就会误触发。
- 现在：光标模式下左键、右键直接 `return true`，不注入任何事件；顺带删掉 `handlePointerMove()` 里「按住左键拖动 LMC 触点」的分支（没有 DOWN 就不会有触点，留着反而会移动残留触点）。
- 中键 / 侧键不受影响，仍走各自的绑定（中键通常就是射击模式切换键）。
- **红线**：光标模式必须保持「只移动光标」，不要再用点击模拟去点 UI；否则会与「切出鼠标 = 不操作游戏」的语义冲突。

### 2.14 WASD 偏移过大 / 强度滑杆无效

- 根因一：`biasedDirection()` 的角度（±5°）与半径（0.94–1.00）是**常量**，完全不看强度，所以滑杆怎么调都没反应；半径 -6% 会掉进部分游戏的「慢走/死区」，表现为按 W 走不动。
- 根因二：`tapWiggle()` 与 `jitterOffset()` 混用了 0–1 与 px 的尺度，改成 1–100 后必须先除以 100。
- 现在：强度统一 1–100（`humanization_strength_scale`，默认 50），内部用 `strengthFactor = strength / 100`；WASD 满强度角度 ≤ ±1.5°、半径 ≥ 98.8%；TAP 位移 ≤ 1.2px。
- 迁移：旧 float 键 0–1 只在新键缺失时读取并 ×100，避免新值 1 重启后被误判成旧值。

### 2.13 Shift 取消蹲/趴的状态跟踪

- 游戏里的蹲/趴是「点一次切换」，读不到真实状态，只能像 `WalkToggle` 一样跟踪**我们发起的姿态**：按 C/Z/Ctrl 记录，同一键再按清除。
- Shift 只在 `stanceKeyCode != null` 时补点，绝不无条件点 C（否则会把站着的玩家点成趴下）。
- 补点走 `eventHandler.handleKeyEvent`，不能直接操作 pointer（复用触控模式/位置）；姿态键或 Shift 没绑按钮就不介入。
- 关闭开关时立即清空 `stanceKeyCode`，避免下次打开开关时用旧状态误点。
- **红线**：不要在 Shift 的 WASD 疾跑逻辑里改 reanchor（见 6.1）；这里的补点是独立的短促 tap。

### 2.12 Tab 双击触发 Tab + Esc 的窗口与冷却

- 判定窗口：两次 `ACTION_DOWN`（`repeatCount == 0`）间隔 ≤ 2000ms 视为双击；第一次 Tab 正常透传并记录时间。
- 第二次 Tab 被吞掉（`runTabEscCombo` 返回 true），由协程串行注入 Tab → Esc，时序采样，避免固定节拍。
- 触发后写 `tabComboCooldownUntil = now + 2000`；冷却期内的 Tab 直接放行且**不更新** `lastTabDownAt`，否则连按会再次命中窗口。
- 与 2-3-4 连点一样：Tab / Esc 任一按钮缺失就 `return false`，不接管，避免按了没反应。
- **红线**：组合注入必须走 `eventHandler.handleKeyEvent`（复用用户的触控模式与位置），不要另写一套注入；不要在 `onKeyEvent` 里阻塞等待。

### 2.11 8 向摇杆与固定点击节拍太机械

- **WASD 方向**：注入点原来永远落在 8 个精确角度（0°/45°/90°…）。现在换向/按下时采样一次偏置：角度 ±5°、半径 0.94–1.00，方向不变期间保持（`biasedDirection()`），再叠加原有低频微抖。
- **方向过渡**：换向时先补一帧旧方向与新方向的中点（`JoystickMotion.update()` 内），再发目标点，模拟拇指划过；**不能**触发 reanchor，否则会复现 6.1 的疾跑断触。
- **点击时序**：`TapPulse` 的固定 50ms、宏的固定 50ms、2-3-4 的 45/110ms 全部改为采样（带长尾）。`TapPulse` / `ClickMacroRunner` 都提供 `sampleHoldMs` 参数，测试注入固定值保持确定性。
- **验证**：`TapPulseTest` 增加方向过渡与冲刺 reanchor 用例；`ClickMacroRunnerTest` 增加采样范围用例。

### 2.6 交换对对话框：换位键无法录入 / 按钮选不到

- **现象**：交换对对话框里 A/B 只能选到少数按钮，换位键、还原键点上去没反应；交换对一多（第 3、4 组）后，输入区域被挤出对话框，完全无法操作。
- **根因**：
  1. 换位键原来复用 `KeyConfigTextField`（依赖内部 `Text` 的焦点 + `onKeyEvent`）。在 `AlertDialog` 的独立窗口里焦点容易落在按钮上，按键收不到。
  2. A/B 用 `DropdownMenu`，菜单在对话框窗口内会被裁剪，成员一多就点不到。
  3. 对话框内容是普通 `Column`，不滚动；列表变长后底部字段超出可视区域。
- **修复**：对话框根 `Column` 改为 `heightIn(max = 420.dp) + verticalScroll`，并挂 `focusRequester + focusable + onPreviewKeyEvent` 统一捕获下一次按键（`capturing` 状态区分换位键 / 还原键）；A/B 选择改成横向滚动的 `FilterChip`，WASD 组从候选中剔除。
- **验证**：连续添加 4 组交换对后仍能录入新键；A/B 列表可横向滑动并选中每一项。

---

## 3. 通知栏与前台服务

### 3.1 点击通知没反应（Android 12+ 通知 trampoline）

- **根因**：Android 12 起禁止通知先跳 Service/Broadcast 再 `startActivity`。
- **修复**：`contentIntent` 必须用 `PendingIntent.getActivity` 直接指向目标 Activity。

### 3.2 前台服务 5 秒超时崩溃

- **根因**：通知 action 会重入 `onStartCommand`，只要有一次没调用 `startForeground()`，系统就抛 `ForegroundServiceDidNotStartInTimeException`。
- **修复**：**每次** `onStartCommand` 都无条件 `startForeground()`；`ACTION_STOP` 是唯一例外，它提前 `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` 后直接 return。
- **注意**：action 用 `PendingIntent.getService`（startService 语义），不要用 `getForegroundService`，否则平白引入 5 秒义务。

### 3.3 从通知栏进编辑态后按键无响应（一直显示 ＋）

- **现象**：点通知里的「编辑布局」，编辑条出现，但按键捕获不到，按钮上一直是＋。
- **根因**：通知栏收起的过程会抢走悬浮窗焦点，此时 View 层 `setOnKeyListener` 收不到按键。
- **修复**：在 `addOnWindowFocusChangeListener` 中镜像编辑态分支（`requestFocus` + `releasePointerCapture` + `clearActivePointers`），并补 500ms 兜底重试。

### 3.4 隐藏按键后进编辑态整个空掉

- **根因**：`keysVisible == false` 时所有按键不渲染，编辑态就没有任何可操作对象。
- **修复**：编辑态操作条 `EditToolbar` 必须保留「显示/隐藏」按钮，不能只把它放通知栏。

### 3.5 Android 13+ 通知权限导致悬浮窗起不来

- **根因**：没有 `POST_NOTIFICATIONS` 时通知栏控制面不可用，而通知栏是唯一控制入口。
- **修复**：`MainActivity` 用 `rememberLauncherForActivityResult(RequestPermission())` 的 `pendingLaunch` 模式实现「先授权，再启动」；未授权时不启动映射，并提供系统设置入口。
- **注意**：除了权限本身，还要检查应用通知总开关和 `channel1` 频道是否被关闭。

### 3.6 Android 14 以下启动前台服务崩溃

- **根因**：`specialUse` 前台服务类型是 API 34 才有的，低版本传了会崩。
- **修复**：按 `Build.VERSION.SDK_INT` 判断后再传。

### 3.7 通知 action 排列与「暂停映射」

- **折叠态只显示前 3 个 action**：`addAction` 的顺序即优先级。当前顺序是「添加按键 → 暂停映射 → 隐藏按键」，`编辑布局` 与 `关闭` 放展开态；`关闭` 故意藏一层防误触。
- **暂停映射必须同时做四件事**：`FLAG_NOT_TOUCHABLE`、`releasePointerCapture()`、`clearActivePointers()`、退出编辑态。少了 `releasePointerCapture`，鼠标事件仍被悬浮窗截走，用户会发现「暂停了还是打不了字」；少了 `clearActivePointers` 会留下卡住的虚拟手指。
- **暂停不持久化**：只存在于本次服务生命周期，重启服务自动恢复映射，避免忘记恢复。
- **「添加按键」依赖编辑态**：`addNewItem(KEY)` 只设置 `_pendingVariableKeyBind`，绑定对话框的 `LaunchedEffect` 在 `ItemsContainer` 内，非编辑态不渲染。必须先 `toggleEditMode()` 再 `addNewItem()`，顺序不能反。
- **暂停时不渲染按键**：窗口已 `NOT_TOUCHABLE`，再显示按钮只会误导；恢复时 `keysVisible` 保持原值。

---

## 4. 坐标与布局

### 4.1 横屏时左侧按钮被截断

- **根因**：悬浮窗根 View 坐标与屏幕坐标混用，横屏左侧出现负偏移。
- **修复**：统一到悬浮窗坐标系（提交 `a66d2ec`）。新增投影逻辑后，务必用 `LayoutProjectionTest` 的旋转与边界用例回归。

### 4.2 按钮越拖越小 / 尺寸漂移

- **根因**：把「位置投影」和「按键尺寸」混在一起，反复对缓存尺寸做缩放。
- **修复**：`projectLayout()` 的位置按每轴可用范围换算；尺寸以 Compose 实测为准，不再从缓存缩放。
- **验证**：`LayoutProjectionTest` 的往返用例（投影→反投影应回到原值）。

### 4.3 重启后布局轻微偏移

- **根因**：`overlayOrigin` 是运行期私有字段，由实测位置推出，**不随 Profile 存储**；一致性依赖 Profile 的 `layoutScreenWidth/Height` 基准 + 实测重算。
- **注意**：改测量、缩放、`updateMeasuredPosition` 时，两个东西必须一起看，否则布局会「看起来对了但差几像素」。

### 4.4 换设备/旋转后按键位置整体缩放

- 这是**预期行为**：`projectLayout()` 会按「悬浮窗视口 vs 预设基准分辨率」比例缩放。
- 排查手段：主页设备信息卡片同时显示悬浮窗视口与激活预设的基准分辨率，不一致时会给出提示。

---

## 5. 鼠标

### 5.1 侧键触发两次 / 误触系统返回

- **根因**：同一颗侧键会同时以 `MotionEvent.BUTTON_BACK/FORWARD` 和 `KeyEvent.KEYCODE_BACK/FORWARD` 到达。
- **修复**：`MouseInputs.mouseKeyAlias()` 只在 `source` 是鼠标时做别名；`MouseButtonTracker` 把两条路径归一成一次边沿。
- **红线**：不要把鼠标来源的 Back/Forward 直接交给系统处理，否则会退出当前页面。

### 5.2 滚轮轻拨没反应 / 一次拨动触发多次

- **根因**：高分辨率滚轮和触控板给的是小数增量。
- **修复**：`ScrollTracker` 累计小数，满一格才产生一次点击；方向反转先归零；单次累计限制 ±16，防止一次突发刷屏。
- **注意**：轮盘打开时滚轮优先控制轮盘，不再触发普通绑定。

### 5.3 光标模式下拖拽导致 WASD 断触

- **现象**：光标模式按住左键拖动后，WASD 突然失效或角色停住。
- **根因**：光标移动被同时用于拖动 LMC 触点，触点被拖出屏幕后，游戏重置全部触点，WASD 触点一起丢。
- **修复**：**只有按住左键时才拖动 LMC 触点**，其余情况只移动光标，不动任何已按下的触点。

### 5.4 高回报率相关问题

见 1.2。鼠标 125–1000Hz 属于设计支持范围，但注入是「合并后转发」，不是逐报文 1:1 转发。

### 5.5 G502 等鼠标的额外键用不了

- **根因**：Android 的 `MotionEvent` 只定义 5 个按钮位（PRIMARY / SECONDARY / TERTIARY / BACK / FORWARD），第 6 个及以后的键无法通过 `buttonState` 进入应用；DPI 加减、狙击键、配置文件键、G Shift 多为固件级功能或厂商私有 HID usage，Android `InputReader` 根本不产生事件。
- **可行方案**：PC 上用 G HUB 把额外键写入鼠标**板载内存**，映射为 `F13–F24`，再在手机上按一下绑定；它们会以普通 `KeyEvent` 到达。
- **不可行**：指望应用层直接读取这些键，没有任何 API 可以做到。
- **排查**：`adb shell getevent -l`，逐个按键看是否有 `BTN_*` / `KEY_*` 输出。

---

## 6. 数据持久化

### 6.1 透明度设置重启丢失

- **根因**：`overlayOpacity` 早期只改 StateFlow 不落盘。
- **修复**：`FloatingWindowStateManager.saveOverlayOpacity()` 写穿 DataStore。

### 6.2 预设文件损坏导致配置被清空

- **修复原则**：
  - 读取失败进入**临时会话**：禁止覆盖保存 Profile 和 `active_profile_id`，跳过迁移。
  - 尽可能备份损坏 JSON；**备份失败也绝不能自动清空原数据**。
  - 整个 Preferences 文件损坏同样不能清空。
- **验证**：`STABILITY_VALIDATION.md` 的存储故障用例（临时 I/O 失败、JSON 损坏、备份失败、旧备份保护）。

### 6.3 全局设置被误写进 Profile

- **规则**：全局功能配置放 `DataStoreManager` 的全局键（如 `status_bar_visible`、拟人化开关、指针灵敏度），不要塞进 `Profile`。按预设存会导致切预设时设置「跳变」。

### 6.4 新增枚举型全局设置把老用户降级

- **坑**：新加的枚举键（如功能模式）如果默认值取「精简档」，已存在的用户在升级后会被静默降级，功能看起来「消失了」。
- **修复**：缺失值、非法值、解析失败一律回退到**兼容性最强的默认值**；只有用户显式切换才进入精简档。
- **验证**：`FeatureTierSettingTest` 覆盖默认值、非法值、跨重启保持。

---

## 7. 构建与测试

### 7.1 测试「通过」但其实没执行

- **现象**：`compileDebugKotlin` 成功，以为测试也过了，实际一个用例都没跑。
- **根因**：编译 Kotlin 不等于编译测试源集。
- **修复/规则**：必须跑 `.\gradlew.bat :app:testDebugUnitTest`；CI 里用 `build` 而不是 `assemble`。

### 7.2 CI 构建失败：setup-android 许可握手

- **根因**：CI 中 SDK 许可未接受，`setup-android` 步骤失败。
- **修复**：见 `.github/workflows/` 与 `docs/CI_RELEASE_GUIDE.md`，许可在 action 里显式接受。

### 7.3 Windows 上删不掉 `app/build`

- **根因**：Gradle daemon 与 Android Studio 占用文件。
- **处理顺序**：`.\gradlew.bat --stop` → 关闭 Android Studio → 再删 `.\app\build`。

### 7.4 批量替换把整个文件标成改动（LF/CRLF）

- **根因**：仓库里 LF 与 CRLF 同时存在，脚本按错误行尾写回会让整文件 diff。
- **正确流程**：读取 → 探测是否含 `\r\n` → 归一化 `\n` → 替换 → 按探测结果还原 → UTF-8 **无 BOM** 写回。
- **附加坑**：PowerShell here-string `@'...'@` **不包含末尾换行**，拼 `$a + $b` 会把两行粘成一行（本项目出过 `}                Card(` 的语法错误）。拼完显式补 `` `n ``。

### 7.5 临时脚本留在仓库里

- **规则**：批量修改用的临时脚本用完即删，不提交。

---

## 8. 仓库维护

### 8.1 根目录残留已应用的补丁

- **现象**：根目录有多个 `.patch`，看起来像「待应用」，实际内容早已进代码。
- **判定方法**（非破坏性）：

```powershell
git apply --check --reverse <patch>
```

  返回 0 表示补丁内容**已完整存在**于当前工作区，可以删除；同时对照补丁里的版本号，若低于当前 `versionName`，基本可判定过时。

- **处理**：2026-11-08 已删除三个过时补丁（`0001-keysync-foundation-correctness.patch`、`0001a-fix-shizuku-lint.patch`、`key-sync-android16-build-foundation.patch`），删除前确认全仓文档无引用。

---

## 9. 尚未真机验证（不要当成已修复）

- 注入链路（`EventHandler`、`EventManagerImpl`、`ShizukuSystemServerAPi`）整体行为。
- 通知栏三个 action 与焦点恢复（尤其是 3.3 的 500ms 兜底）。
- 鼠标侧键、滚轮四向、1000Hz 实际手感。
- 宏执行时序与取消路径。
- 旋转 / 换视口后的 WASD 疾跑（2.2）。

真机验收步骤见 `docs/STABILITY_VALIDATION.md` 与 `docs/MOUSE_AND_MACROS.md`。
