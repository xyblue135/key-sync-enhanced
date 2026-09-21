# KeySync Enhanced

基于 KeySync 的 Android 键鼠映射增强版。免 root，通过 [Shizuku](https://github.com/RikkaApps/Shizuku) 获取输入注入权限，将外接键盘 / 鼠标的按键事件翻译成屏幕触摸点（MotionEvent），注入到前台游戏。

相比原版，Enhanced 在保留运行时模型的同时，补齐了一层**可扩展的键盘预设系统**，并修复了大量输入兼容性关键 Bug。

## 功能亮点

### 预设系统（Preset System）
- 22 个内置键盘预设，覆盖 通用 / FPS / MOBA / MMORPG / 动作 / 竞速 六大类
- 人类可读的预设 Schema（JSON，`data/mapping/*.json`）：改预设不用动运行时代码，diff 友好
- 归一化坐标（双轴 0.0–1.0）+ 像素坐标双模式，同一预设自动适配不同分辨率
- 符号化 keyCode：可直接写 `"SPACE"`，也支持 Android `KEYCODE_*` 名称与 `#123` 原始值
- 加载前预设校验：非法 schema 版本 / 坐标模式 / 类型提前报错，不暴露坏预设
- 运行时键位冲突诊断：重复物理键自动输出 Logcat 告警
- 应用内预设选择：长按已配置游戏即可从预设目录选布局并存到对应包名

### 输入兼容性修复（Enhanced 的核心价值）
- 外接键盘 keyCode 范围：固定长度数组 → HashMap / HashSet，避免厂商键值越界导致 IndexOutOfBounds
- 多指 downTime 统一由 EventManager 持有：修复「多指只有一根手指生效」的断指问题
- 触点几何信息补全（touchMajor / touchMinor / toolMajor / toolMinor），避免被引擎忽略面积为 0 的触点
- `ACTION_POINTER_DOWN/UP` 索引越界时降级为 `ACTION_MOVE`，防止整条手势被 Android 丢弃
- 事件入队顺序修复：无界 Channel + 主线程同步入队，杜绝 UP 插到自身 DOWN 之前造成的卡死触点
- 按键状态机「隔次吞键」修复：UP 后状态可靠回到 false，Mixed 模式短按仍保持 200ms 最小脉冲
- WASD 掩码与真实 Android keyCode 拆分命名空间，解决静默覆盖（如 `KEYCODE_1=8=MASK_D` 互踩）
- FIRE / SCOPE 默认 keyCode 修正为鼠标左右键，开火 / 开镜键真正查得到、鼠标左右键有反应
- 重映射前先 release 孤儿触点，避免残留触点变成非法 `ACTION_POINTER_DOWN`
- 鼠标瞄准「不跟手」修复：改读相对位移轴（`AXIS_RELATIVE_X/Y`）+ DPI 增益，对齐熊猫映射 1.2–1.5x 默认灵敏度
- 注入链路去延迟：`INJECT_INPUT_EVENT_MODE_ASYNC` + 专用单线程 executor + 去掉每事件 `sliceArray` 分配

### 多预设与切换
- 每个 Profile 自带独立 items / appConfig，可建多套布局手动切换
- 预设复制副本 / 导出导入（pretty-print JSON 走剪贴板，导入重生成 id 不覆盖来源）
- 预设切换热键：按下走映射、抬手才切换（边沿分离），映射零延迟、切换不冲突
- 切换时保持按住的键（heldKeyCodes 快照 + replay），避免按着 W 切预设时 W 假松手
- 切换热键总开关默认关闭，升级不会在用户不知情下把某键变热键
- 每预设独立指针灵敏度 + 隐藏 overlay 键开关

### 射击 / 瞄准体验
- 拖拽准星瞄准 + 视图级 key capture
- 准星锚定屏幕中心，射击模式边缘自动回中（edge recenter）
- 低延迟鼠标瞄准：读相对 delta + 异步注入
- 高频 MOVE 事件合并（coalesce），降低掉帧掉点
- 射击模式默认绑 `` ` `` 键，开火 / 开镜真实映射鼠标左 / 右键
- 冲刺摇杆 + 每键触摸模式（tap / hold / mixed）+ 滚轮选择器

### 其它
- Android 16 toolchain 升级
- 主界面 / 设置界面重构
- 屏幕镜像兼容模式（PC 鼠标 via 熊猫映射）
- DataStore JSON 容错解析（ignoreUnknownKeys / isLenient / coerceInputValues），旧数据不崩
- 活动 pointer 容量 10 → 16，复杂键组同按更稳
- 手势插值改为按真实移动方向计算垂直偏移，更自然

## 工作原理

1. 手机安装并激活 Shizuku（提供 adb 或 root 方式激活），授予 KeySync Enhanced 权限。
2. App 通过 Shizuku 拿到输入注入能力（无需 root）。
3. 外接键盘 / 鼠标的按键事件经映射层翻译成屏幕坐标上的触摸点。
4. 生成的 MotionEvent 通过系统输入派发器注入到前台游戏。

## 构建与运行

环境要求：
- Android SDK（在 `local.properties` 配置 `sdk.dir`，例如 `sdk.dir=C:\Users\xyblu\AppData\Local\Android\Sdk`）
- 已安装 Shizuku App 并完成授权
- JDK 17+（Android 16 toolchain）

常见清理 / 构建步骤：
```bash
# 停止 Gradle
.\gradlew.bat --stop
# 关闭 Android Studio 后删除生成目录
Remove-Item -Recurse -Force .\app\build
# 重新 clean build（跳过测试）
.\gradlew.bat clean build -x test
```

## 已知限制

- Android 厂商输入设备、游戏自身输入策略、Shizuku 版本、系统权限与游戏反作弊机制都可能影响实际行为，不保证所有游戏兼容，也不提供绕过反作弊的实现。
- 当前阶段聚焦预设系统与输入兼容性，刻意未实现以下能力（需要独立的 Runtime / UI / 状态机设计，而非仅加 JSON 字段）：DSL 脚本引擎、自定义轮盘、鼠标拖动施法 DSL、FPS 边缘回中状态机、静步状态机、自定义 overlay 图片管理器、多点点击 DSL。

## 文档

- `docs/ENHANCED_SUMMARY.md` — 增强功能总述
- `docs/COMPATIBILITY.md` — 兼容性修复细节
- `docs/CHANGELOG_ENHANCED.md` — 变更日志
- `docs/MAPPING_FORMAT.md` — 预设 JSON 格式

## 许可证

见 `LICENSE`（沿用原 KeySync 许可证）。
