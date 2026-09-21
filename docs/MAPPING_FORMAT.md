# KeySync Enhanced — Mapping Preset Guide

## 1. 为什么不直接保存 `DraggableItem` JSON？

KeySync 原来的 DataStore 使用 Kotlin Serialization 直接保存内部 `DraggableItem` sealed class。这个格式和 Kotlin 类名、字段结构绑定得很紧，代码模型一旦重构，旧配置就容易出现兼容问题。

Enhanced 版本增加了一层**稳定的、人工可编辑的 Mapping Preset Schema**：

```text
JSON preset
   ↓
MappingPresetRepository
   ↓
validate / normalize
   ↓
DraggableItem
   ↓
现有 KeySync Runtime
```

因此预设文件不依赖内部 Kotlin 类名。

## 2. 文件位置

规范的预设源文件位于：

```text
data/mapping/
├── index.json
├── universal/
├── fps/
├── moba/
├── mmorpg/
├── action/
├── racing/
└── templates/
```

Android 构建时通过 `app/build.gradle.kts` 将根目录 `data/` 作为 assets 来源，因此运行时可以读取 `mapping/index.json`。

## 3. 坐标系统

### normalized（推荐）

```json
{
  "coordinateMode": "normalized",
  "x": 0.80,
  "y": 0.82
}
```

`0.0 ~ 1.0` 分别表示屏幕宽高的比例。

例如：

```text
x = 0.5 → 屏幕水平中心
x = 1.0 → 屏幕最右侧

y = 0.5 → 屏幕垂直中心
```

优点：不同分辨率之间不需要重新计算像素坐标。

### pixel

适合从已有固定分辨率布局迁移：

```json
{
  "coordinateMode": "pixel",
  "targetWidth": 1080,
  "targetHeight": 2400,
  "x": 864,
  "y": 1968
}
```

运行时会按照目标分辨率与当前设备分辨率进行等比例缩放。

## 4. KEY

普通键：

```json
{
  "type": "KEY",
  "keyCode": "SPACE",
  "x": 0.80,
  "y": 0.82,
  "size": 0.055
}
```

支持：

- `A`~`Z`
- `0`~`9`
- `SPACE`
- `ENTER`
- `TAB`
- `ESCAPE`
- `SHIFT_LEFT`
- `SHIFT_RIGHT`
- `CTRL_LEFT`
- `CTRL_RIGHT`
- `ALT_LEFT`
- `ALT_RIGHT`
- `UP`
- `DOWN`
- `LEFT`
- `RIGHT`
- Android `KEYCODE_*` 字段名
- `#123` 形式的原始 Android keyCode

## 5. WASD

```json
{
  "type": "WASD",
  "x": 0.04,
  "y": 0.63,
  "scale": 0.95,
  "centerX": 0.1025,
  "centerY": 0.6925,
  "wX": 0.1025,
  "wY": 0.6425,
  "aX": 0.0525,
  "aY": 0.6925,
  "sX": 0.1025,
  "sY": 0.7425,
  "dX": 0.1525,
  "dY": 0.6925
}
```

WASD 是一个逻辑组，运行时还会自动计算四个对角方向。

**注意：不要再单独添加 W/A/S/D KEY，否则会与 WASD 组产生冲突。**

## 6. FIXED

特殊的 KeySync 按钮：

```json
{
  "type": "FIXED",
  "itemType": "FIRE",
  "x": 0.91,
  "y": 0.70,
  "size": 0.06
}
```

当前支持：

- `FIRE`
- `SHOOTING_MODE`
- `SCOPE`
- `KEY`

其中 FIRE / SHOOTING_MODE / SCOPE 使用 AppConfig 中对应的按键，因此修改全局按键设置后，预设不需要重新编辑。

## 7. CANCELABLE

```json
{
  "type": "CANCELABLE",
  "keyCode": "Q",
  "x": 0.80,
  "y": 0.70,
  "cancelX": 0.88,
  "cancelY": 0.78,
  "size": 0.05
}
```

该类型使用现有 KeySync cancelable touch handler。

## 8. 增加新预设

1. 复制 `data/mapping/templates/blank.json`
2. 修改 `id`、`name`、`description`
3. 调整 `items`
4. 把文件放到合适分类
5. 在 `data/mapping/index.json` 增加索引
6. Git commit

例如：

```text
feat: add my-game keyboard preset
```

## 9. 预设冲突

程序加载 Mapping 时会检查重复 keyCode，并写入 Logcat：

```text
Mapping conflict: keyCode 51 is mapped by items 1 and 4
```

这主要用于发现：

- WASD 与单键 W/A/S/D 重叠
- 同一个物理键被多个独立映射占用
- 复制预设后忘记修改按键

## 10. 设计原则

预设应该描述“游戏控制布局”，而不是复制 Runtime 实现。

因此新增一种操作能力时，应先修改 Runtime / Model，再决定是否扩展 Schema。

不要为了让 JSON 看起来支持某功能而加入 Runtime 实际不支持的字段。
