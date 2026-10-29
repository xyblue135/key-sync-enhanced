# CI / Release 与 APK 签名教程

> 写给第一次接触 GitHub Actions 和 APK 签名的人。
> 对应本仓库的两个 workflow：`.github/workflows/android-ci.yml` 和 `.github/workflows/release-apk.yml`。
> 更新：2026-10-29

---

## 1. 先弄懂三件事

### ① CI（持续集成）是什么

你每次 `git push`，GitHub 的服务器（不是你的电脑）会自动拉取代码，跑一遍：

- 97 个 JVM 单元测试
- Android Lint（静态检查）
- 试构建一个 debug APK

这就是仓库里的 **Android Checks**。它相当于一个不知疲倦的守门员：任何一次 push 如果把代码改坏了，几分钟后 GitHub 上就会亮红灯，而不用等你在真机上装出来才发现。

### ② Release（发布流水线）是什么

当你推送一个 `v*` 开头的标签（比如 `v1.48-android16`），会触发另一条流水线 **Android Release APK**：

1. 校验标签名必须等于 Gradle 里的 `versionName`（防止版本号和标签对不上）
2. 再跑一遍测试 + Lint（正式包门槛更高）
3. 取出签名密钥（见下文）
4. 编译**正式版** APK 并用密钥签名
5. 自动创建 GitHub Release 页面，把签名 APK 挂上去供下载

### ③ 签名是什么，为什么绕不开

安卓系统规定：**每个 APK 必须用私钥签名才能安装**。并且：

- 同一个应用的**新版本必须用同一把钥匙**签名，手机才允许覆盖安装；
- 换了钥匙 = 系统认为这是两个不同的应用，只能卸载重装（数据全丢）。

平时 Android Studio 装到手机上的 debug 包，用的是 IDE 自动生成的**临时调试钥匙**，只适合自己测试。要公开发布正式包，就必须有一把自己的钥匙，并且**永远保管好**。

本项目的钥匙是 2026-10-28 生成的：

| 文件 | 说明 |
|---|---|
| `.secrets/keysync-release.jks` | 密钥库文件（里面装着私钥），RSA 4096，有效期约 27 年 |
| 根目录 `keystore.properties` | 密钥库路径 + 两个密码（本地构建用） |

---

## 2. 为什么要配 GitHub Secrets（你正在做的这一步）

一句话：**把你本地的签名钥匙，安全地交给 GitHub 的构建机器。**

展开说：

- 正式包是在 GitHub 的云端机器上编译的，而钥匙文件 `keysync-release.jks` 只存在你的电脑上，云端拿不到。
- 钥匙文件又**绝不能直接提交进 git 仓库**——提交了，任何能看到仓库的人都能下载这把钥匙，然后冒充你发布带毒的"新版本"（本项目已通过 `.gitignore` 排除 `*.jks` / `keystore.properties` / `.secrets`，这三个永远不会被上传）。
- GitHub 的解法叫 **Secrets（加密保险柜）**：在仓库设置里把钥匙和密码存进去，之后：
  - Actions 构建时可以读到（以环境变量形式注入）；
  - 网页上**任何人（包括你自己）都只能覆盖或删除，不能再查看**；
  - 构建日志里会自动打码成 `***`。

所以那个 "New secret" 表单，做的就是"把钥匙放进保险柜"这件事，一共要放 4 样东西。

---

## 3. 4 个 Secret 分别是什么

用"保险箱"类比理解 `.jks` 文件的结构：**jks 是一个保险箱，storePassword 是保险箱的密码，箱子里贴着标签（alias）的才是那把私钥，keyPassword 是使用那把私钥的密码。**

| Secret 名称 | 填什么 | 它是什么 |
|---|---|---|
| `KEYSTORE` | `.secrets/keystore.base64.txt` 的全部内容 | 保险箱本身。因为保险柜只能存文本，而 jks 是二进制文件，所以先转成一长串 base64 字母数字；流水线里会再转回来 |
| `KEYSTORE_PASSWORD` | `keystore.properties` 里 `storePassword=` 后面的值 | 打开保险箱的密码 |
| `KEY_ALIAS` | `keysync` | 私钥在保险箱里的标签名 |
| `KEY_PASSWORD` | `keystore.properties` 里 `keyPassword=` 后面的值 | 使用私钥的密码（本项目与 storePassword 相同） |

流水线里对应的代码（`release-apk.yml`）：

```yaml
- name: Decode keystore   # 把 base64 还原成 jks 文件
  run: printf '%s' "$KEYSTORE_BASE64" | base64 --decode > app/key.jks
- name: Build signed release APK   # Gradle 拿着这 4 样东西给 APK 签名
  env:
    ORG_GRADLE_PROJECT_storeFile: .../key.jks
    ORG_GRADLE_PROJECT_storePassword: ${{ secrets.KEYSTORE_PASSWORD }}
    ORG_GRADLE_PROJECT_keyAlias: ${{ secrets.KEY_ALIAS }}
    ORG_GRADLE_PROJECT_keyPassword: ${{ secrets.KEY_PASSWORD }}
```

---

## 4. 全流程图

```
你 git push
   │
   ├─→ 【Android Checks】跑测试 + Lint + 构建 debug 包
   │       目的：验证代码没被改坏。红灯 = 有问题，不会发任何东西。
   │
你 git push v1.48-android16 标签
   │
   └─→ 【Android Release APK】
           ① 标签名 == versionName ？        （对不上 → 中止）
           ② 测试 + Lint 全过 ？              （不过 → 中止）
           ③ 从 Secrets 取钥匙，还原 jks 文件  ← 你正在配的这一步
           ④ 编译 release APK，用钥匙签名
           ⑤ GitHub Releases 页面自动出现
              "Release v1.48-android16" + app-release.apk
```

也就是说：**不配 Secrets，前 ①② 步都能跑通，只有 ③ 开始会失败**——这正是 2026-10-29 那次运行的现象（`Decode keystore` 报 `KEYSTORE_BASE64` 为空）。

---

## 5. 配好后怎么验证

1. 打开仓库 → **Actions** → 左侧 **Android Release APK**
2. 点开失败的 run `40899729766`（标签 v1.48-android16 那次）
3. 右上角 **Re-run failed jobs**
4. 等 10 分钟左右全绿后，仓库首页右侧 **Releases** 会出现 `v1.48-android16`，里面挂着签名好的 `app-release.apk`

以后每次发新版：改 `versionName`/`versionCode` → push → 打对应标签 → 自动出包。

---

## 6. 本地直接出正式包（不走 GitHub 也行）

`.secrets/` 生成时已顺手配好根目录 `keystore.properties`，所以本地一条命令就能出签名包：

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
# 产物：app\build\outputs\apk\release\app-release.apk
```

2026-10-29 已验证过：本地构建出的包通过 `apksigner verify` 验签。

---

## 7. 安全须知（重要）

1. **备份** `.secrets/` 整个目录和根目录 `keystore.properties`（密码管理器 / 离线 U 盘）。钥匙一丢，以后所有正式包都无法覆盖安装老版本。
2. Secrets 保存后在网页上**再也看不到内容**，只能覆盖重填——所以密码务必以 `.secrets/README.md` 和 `keystore.properties` 这两个本地文件为准。
3. 不要把 jks、密码、base64 文件提交进 git、发到聊天工具或截图里。
4. debug 包（`-debug` 后缀）和 release 包签名不同，**不能互相覆盖安装**：装过 debug 包的设备换装正式包前要先卸载。

---

## 8. 一次已经处理过的事故（供参考）

2026-10-05 ~ 10-28，CI 每次都在 30 秒内失败：`android-actions/setup-android@v3` 与新版 sdkmanager 不兼容。已修复（改为直接写入 SDK 许可证文件），详细分析见会话记录 `ci-incident-2026-10-28.html`，修复提交 `b8c91d0`。
