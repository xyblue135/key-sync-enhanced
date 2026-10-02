# 1.44 稳定性验证

## 本地自动检查

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
```

离线缓存不足时去掉 `--offline`，网络按本机代理配置处理。

## 真机回归（需要用户设备、Shizuku 和键鼠）

1. Android 13+ 首次启动，拒绝通知权限：不进入游戏、不启动映射，出现说明与设置入口。授权后再次点击游戏，应正常启动。
2. 系统设置单独禁用「按键悬浮窗」频道：启动被阻止，设置入口进入该频道；重新开启后可启动。
3. 按住 W / W+Shift / 鼠标左键时打开通知栏，在通知栏中松开，再回到游戏：不能继续移动或开火，按键高亮消失；重新按键后输入正常。
4. 开启射击模式与拟人化后重复第 3 步：失焦后触点和抖动均停止。恢复后需重新开启射击模式，不恢复旧触点。
5. 从通知栏进入编辑模式、绑定键位、拖动按键、完成编辑、关闭再启动：焦点捕获与布局保存正常。
6. 横竖屏切换、切换预设、修改大小：布局往返不漂移、按钮不持续缩小、不越界。
7. Android 10–13 启动服务，确认前台通知与三个 action 正常；Android 14–16 重复验证。

## 配置故障语义

- 正常读取或真正首次启动允许保存。
- JSON 无法解析：原 `profiles` 不替换，尽可能另存 `profiles_quarantine`；已有不同备份不覆盖。
- I/O 失败或整个 Preferences 文件损坏：保留原数据，禁止自动写默认预设。
- 临时预设可用于当前会话及导出，但不会保存到原配置；界面提示这一限制。
- 临时 I/O 故障恢复后重启应用重新加载；持久损坏需从备份修复，不能通过自动清空掩盖。

## CI / 发布

- 普通 push 到 master 及目标为 master 的 PR：检查，不发布。
- 手动运行 Android Release APK 或推送与版本匹配的标签：检查通过后签名、上传并发布。
- 签名仍使用 build-env 环境的 KEYSTORE、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD。
- 标签与版本不符、检查失败、签名缺失或 APK 不存在时，发布停止。
- 本次本地修改不会自行触发远程发布。
