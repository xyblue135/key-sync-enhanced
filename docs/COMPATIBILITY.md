# KeySync Enhanced — Compatibility Notes

## 已处理

### 1. 外接键盘 keyCode 范围

旧 EventHandler 使用固定长度数组保存键盘状态。Enhanced 改为 HashSet / HashMap，避免特殊或厂商键值超过固定数组后出现越界。

### 2. DataStore JSON 容错

DataStore 读取增加：

- ignoreUnknownKeys
- isLenient
- coerceInputValues
- 解析失败回退为空映射 / 默认 AppConfig

这样未来增加字段时不会因为未知字段导致旧数据无法读取。

### 3. Preset Schema 与 Runtime 解耦

预设 JSON 不直接依赖 `DraggableItem` 的 Kotlin sealed class 序列化名称。

### 4. 分辨率

推荐新预设使用 normalized 坐标。pixel 坐标保留给需要迁移旧布局的场景。

### 5. Mapping 冲突

加载 Runtime Mapping 时检查重复 keyCode，并输出 Logcat 警告。

### 6. MotionEvent 时间戳

事件创建时使用当前 uptimeMillis 作为 eventTime，而不是让 downTime 与 eventTime 永远相同。

### 7. 多指容量

EventManager 的活动 pointer 上限从原来的 10 提高到 16，以减少复杂键盘组合同时按下时的拒绝。

### 8. 多指手势 downTime（关键修复）

**问题**：`createMotionEvent` 给每个事件都重新取一次 `SystemClock.uptimeMillis()` 当作
downTime，于是同一手势里每个事件的 downTime 都不同。Android（以及大部分游戏引擎）用
downTime 判断「这些事件属于同一次手势」，downTime 一变，多根手指就被拆成多次独立手势，
接收端只认其中一根 —— 表现就是「多指不生效，只有一根手指有反应」。

**修复**：downTime 由 EventManager 统一持有。第一根手指按下时记录，最后一根手指抬起
（`activePointerCount` 归零）或 `clear()` 时重置，期间所有事件共用同一个值。

### 9. 触点几何信息补全

`PointerCoords` 以前只填了 `x / y / pressure / size`，`touchMajor`、`touchMinor`、
`toolMajor`、`toolMinor` 全是 0。真实触摸屏永远会带上接触面积，部分引擎 / ROM 会直接忽略
接触面积为 0 的触点。现在统一填 `CONTACT_SIZE_PX = 24f`。

### 10. ACTION_POINTER_* 索引越界保护

ACTION_POINTER_DOWN / ACTION_POINTER_UP 把「第几个手指」编码在 action 的高位。
一旦索引超出本次事件携带的 pointer 数量，Android 会**丢弃整个事件**（连同其它手指一起）。
`createMotionEvent` 现在会做索引校验，越界时降级为 ACTION_MOVE，而不是让手势断掉。

### 11. 事件入队顺序（关键修复）

`EventInjectorImpl` 原来每次调用都 `scope.launch { taskQueue.send { … } }`。
两条独立协程在 `Dispatchers.Default` 上没有执行顺序保证，UP 完全可能插到它自己的 DOWN
前面。结果是 pointer 卡在「已按下」状态永远不释放，`activePointerCount` 不再归零，
**下一次的第一根手指也会被当成 ACTION_POINTER_DOWN 发出去** —— 一个非法手势，
接收端整条丢弃。

修复：队列改成无界 Channel + `trySend`。所有 public 方法都在主线程调用，
同步入队就能严格保持请求顺序；同时 `addEventDown` 遇到「pointer 已经按下」时
改为下发 MOVE，不再重复发 DOWN。

### 12. 按键状态机隔次吞键

Tap / Mixed 两个 handler 在 ACTION_UP 时 `return isPressed`，导致 `mappingPressed` 一直是
`true`。下一次按下会走进「已按下」分支，只 `releasePointer` 不发 DOWN —— 隔次吞键，
并且会留下卡死触点（见第 11 条）。现在 UP 之后状态可靠地回到 `false`；
Mixed 模式下短按仍会保持 200ms 最小脉冲再抬起。

### 13. 映射键空间冲突（关键修复）

WASD 掩码（1/2/4/8…）和真实 Android keyCode 之前共用同一个 `HashMap`。
两者会撞：`KEYCODE_BACK = 4 = MASK_S`、`KEYCODE_1 = 8 = MASK_D`，
于是「切武器 1」按钮会静默覆盖摇杆的 D 方向，反之亦然。
多指场景下表现为第二根手指打到错位或者干脆没反应。

修复：拆成 `wasdMap`（摇杆方向）和 `keyMap`（真实 keyCode）两个命名空间。

### 14. FIRE / SCOPE 默认 keyCode

`SHOOTING_MODE`、`FIRE`、`SCOPE`、`MOUSE_MID` 四个按钮的 keyCode 默认全是
`KEYCODE_MMC(256)`，互相覆盖、共用一个 pointer id。而射击模式下
`handleMouseButton` 是按 `KEYCODE_LMC` / `KEYCODE_RMC` 查表的，
所以开火键和开镜键**根本查不到**，鼠标左右键完全没反应。

修复：FIRE 默认 `KEYCODE_LMC`、SCOPE 默认 `KEYCODE_RMC`、SHOOTING_MODE 保持 `KEYCODE_MMC`。
同时 `MappingConflictDetector` 原来只检查 `type == KEY` 的 FixedKey（这个 type 实际不存在），
现在检查所有 FixedKey，重复键位会在 Logcat 里报出来。

### 15. 重映射前先释放触点

`updateKeyMapping` 会重新分配 pointer id。之前残留的触点会变成孤儿：
EventManager 还记着它，下一根手指就只能发成 ACTION_POINTER_DOWN。
现在重映射前先 `eventInjector.clear()`。

### 16. 多预设与复制粘贴

每个 `Profile` 自带 `items` / `appConfig`，可建多套布局手动切换。预设行菜单提供：

- **复制副本**：`Profile.copy` 换一个新 id。item id 只需在单个预设内唯一，切换热键
  指向的也是预设 id，所以整份拷贝可以直接复用，不需要重新编号。
- **导出 / 导入**：预设序列化成 pretty-print JSON，走剪贴板。导入时重新生成 id，
  不会覆盖来源预设；指向本机不存在的预设的热键目标会被清成「循环下一个」。

### 17. 预设切换热键：按下触发映射，抬手才切换（关键设计）

一个键常常既是映射按键、又想用来切预设（比如预设1里 X 是「上车」，同时希望 X 切到
载具预设）。靠「谁优先」裁决必然牺牲一个，所以改成**按时间边沿分离**：

- **ACTION_DOWN**：照常走映射，立即注入触摸 —— 原来的动作一个不少，零延迟。
- **ACTION_UP**：先释放映射，再判定是不是切换热键，是才切预设。

这样映射在时间上排前面、切换排后面，两者互不冲突。

两个实现细节：

- 热键判定必须放在 `pointerIds[keyCode] ?: return false` 守卫**之前**。否则一个
  在当前预设里没有映射的纯热键会在守卫处被提前 return，热键永远不触发。
- 长按产生的自动重复 ACTION_DOWN 由 `pressedKeyCodes` 去重，只有第一次 DOWN 生效；
  切换回调只在真正的 UP 上调用一次。

替代方案里，「切换抢先」会让预设1里的 X 彻底失效；「长按/短按消歧」必须等抬手或等
超时才能决定用途，映射动作会有 200ms 以上的延迟，射击游戏里不可用。所以选边沿分离。

### 18. 切换时保持按住的键

`updateKeyMapping` 会抬起所有触点，直接切预设会让物理上仍按住的键「假松手」——
按着 W 跑动时按 X 切预设，W 会失效直到松开重按。

现在切换前先 `eventHandler.heldKeyCodes()` 拍快照，`applyActiveProfile()` 之后
`replayHeldKeys()` 按新映射重新按下。同时 `updateKeyMapping` 会复位
`wasdMask` / `lastWasdMask`，否则重放的摇杆键会走「变换已有手势」分支，
去变换一个已经被清掉的手势。

射击模式不是「按住的键」而是常驻触点：`toggleShootingMode` 开启时会往 SHOOTING_MODE
按钮位置压一个不松开的点。切换预设时它同样被 `eventInjector.clear()` 抬起，而
`shootingMode` 标志位仍是 true → 状态说在射击模式（光标不再跟随鼠标），游戏那边却没有
瞄准触点，必须手动关再开。所以切换后额外调用 `restoreShootingModeContact()`。

**已知未处理**：鼠标按键的按下状态没有纳入 `pressedKeyCodes`，所以按着左键开火时切预设，
开火不会自动续上，需要松开重按。刻意不改——那会动到 `handleMouseButton` 这条开火主路径，
而「按着开火切预设」是极罕见的场景，不值得拿主路径的稳定性去换。

### 19. 热键总开关默认关闭

`AppConfig.profileSwitchEnabled` 默认 `false`。升级后不会在用户不知情的情况下
把某个已在使用的按键变成切换热键。开关关闭时热键配置保留但不生效。

### 20. 鼠标瞄准「不跟手」（关键修复）

`requestPointerCapture()` 之后系统会冻结屏幕上的光标，鼠标移动量写在
`MotionEvent.AXIS_RELATIVE_X / AXIS_RELATIVE_Y`（相对位移），`rawX/rawY`
则是捕获后不再变化的绝对坐标。旧 `onMouseEvent` 对 `ACTION_MOVE` 直接读
`rawX/rawY`，拿到的是一组固定值，于是瞄准触点只会跳到某个固定位置、完全不跟随
鼠标，表现就是「键鼠映射一点不跟手」。

修复：改读相对轴，并保留「相邻事件 raw 坐标差」兜底给不填相对轴的 ROM；灵敏度
系数从「为绝对坐标调大的 `10f`」改回相对位移语义下的 DPI 增益 `3f * value`
（默认 0.5 → 1.5x，对齐熊猫映射等工具的 1.2–1.5x 默认灵敏度）。

### 21. 注入链路去延迟

瞄准热点路径上的三处开销一并去掉：

- `injectInputEvent` 从 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT`（1，每个事件
  都阻塞等分发完成）改为 `INJECT_INPUT_EVENT_MODE_ASYNC`（0，立即返回）。
  输入派发器本身是 FIFO，异步注入不破坏 DOWN/MOVE/UP 的顺序；事件经 Shizuku
  binder 序列化后系统侧用的是自己的拷贝，本地 `MotionEvent` 调用返回后即可
  recycle。
- `EventInjectorImpl` 的串行队列从共享的 `Dispatchers.Default` 线程池改为
  专用单线程 executor，避免被应用里其它协程工作抢占。
- `EventManagerImpl.createMotionEvent` 去掉每事件一次的 `sliceArray` 分配，
  直接把固定大小缓冲交给 `MotionEvent.obtain`（它只拷贝 `pointerCount` 个元素）。

## 当前仍然受原项目限制的部分

Enhanced 本阶段重点是**预设系统和输入兼容性**，没有伪装成已经实现以下能力：

- DSL 脚本引擎
- 自定义轮盘
- 鼠标拖动施法 DSL
- FPS 边缘自动回中
- 静步状态机
- 自定义 overlay 图片管理器
- 多点点击 DSL

这些功能需要独立的 Runtime / UI / 状态机设计，不能只增加 JSON 字段就算完成。

## 特别注意

Android 厂商的输入设备、游戏自身输入策略、Shizuku 版本、系统权限和游戏反作弊机制都可能影响实际行为。

本项目不保证所有游戏都兼容，也不提供绕过游戏反作弊的实现。
