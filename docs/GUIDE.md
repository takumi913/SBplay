# SBplay 使用与开发指南

README 只保留概览；安装、构建、测试、发布等说明都在这里。

目标设备：领克 CS11（`model=CS11`，ECARX / GKUI 平台，Android 4.4.3 / API 19 / Dalvik，Freescale i.MX6Q，
系统按 1280×720 逻辑运行、面板 1920×1080）。本项目是 [DiPlay](https://github.com/shihabal3amri/DiPlay)
（GPL-3.0）的长期分支，已移除 BYD / DiLink 适配，只对 CS11 做承诺。

当前各模块的实车结论见 README 的「状态」；之后的修改以提交说明里的验证状态为准，
稳定性与失败排查相关改动的细节和上机复验清单见 [`_digest/104`](_digest/)。

## 安装

### 下载

在 [Releases](https://github.com/takumi913/SBplay/releases) 页面下载 `SBplay-<版本>.apk`，旁边有同名 `.sha256` 校验文件。每个 Release 的说明里带有 What's Changed、安装步骤和校验值。

也可以在 [Actions](https://github.com/takumi913/SBplay/actions/workflows/android.yml) 里打开任意一次成功的运行，下载 `SBplay-CS11-API19-apk`（保留 30 天）。

### 装到车机

车机需开启 USB 调试并允许 TCP 5555（见 [`docs/DEV-ENVIRONMENT-CS11.md`](DEV-ENVIRONMENT-CS11.md)）。

```bash
adb connect 192.168.x.x:5555
adb install -r SBplay-<版本>.apk
```

需要注意：

- 覆盖安装即可，**不要先卸载**，以保留已有设置；换了签名密钥就无法覆盖安装旧版。
- 包名 `com.shihab.diplay.hudtest`，debug 签名；APK 内版本名 `0.2.26`，`minSdk` 和 `targetSdk` 都是 19，仅含 `armeabi-v7a`。
- `adb reboot` 之后车机的 USB 调试会被关掉，要重新到车机设置里打开。
- iPhone 是黑盒发送端，直接插上即可测试；无线连接需要先和车机完成蓝牙配对。

## 构建

要求：**JDK 21**，Android SDK / NDK 按 AGP 9.3.0 配置（**NDK r25c `25.2.9519653`**，是仍支持 API 19 的最新版；cmake `3.22.1`），以及仓库自带的 Gradle wrapper。

```bash
./gradlew :mobile:clean :mobile:assembleStandaloneDebug
# -> mobile/build/outputs/apk/debug/mobile-debug.apk  (约 10.4 MB)
```

Windows 本地构建（wrapper 指向国内镜像，常用 `--offline`）：

```powershell
.\gradlew.bat :mobile:clean :mobile:assembleStandaloneDebug --console=plain --offline
```

- 配件身份**已提交**在 `mobile/src/main/auth-assets/offline-mfi/`（见[配件身份](#配件身份)），克隆后无需额外输入即可构建。`DIPLAY_AUTH_ASSETS_DIR` 仍可用，会**替换**该身份。
- 构建前先 `:mobile:clean`：增量打包曾在 APK 里留下约 5 MB 死空间，干净构建约 10.4 MB。
- CI 在 GitHub Actions 上做同样的构建，完整说明见 [`docs/BUILD.md`](BUILD.md)。

## 测试

```bash
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest --rerun-tasks
./gradlew :shared:lintDebug :common:lintDebug :mobile:lintDebug   # NewApi 违规会让构建失败
```

失败集合必须保持为空，判定看 `BUILD SUCCESSFUL`，不要信控制台摘要。不要同时跑两个 Gradle 构建，它们会争用同一份构建输出。

编译、单测和 lint 只证明代码正确，**不证明车上可用**；功能结论必须来自 CS11 实机。

## 发布

发布前先更新 `mobile/build.gradle.kts` 的 `versionName` 和 `versionCode`，将更新提交到 `main`，再从这个新提交创建 tag（例如 `v0.2.26`）。**Release 使用 tag 所指向的源码，不会自动使用随后推送到 main 的修改。**

在 GitHub 上发布 Release（tag 形如 `v0.2.26`），工作流会自动：

1. 构建并校验 APK：`minSdk` / `targetSdk` 为 19、仅 `armeabi-v7a`、v1 签名、内置配件身份。
2. 跑完单测和 lint。
3. 把 `SBplay-<tag>.apk` 和 `.sha256` 上传到该 Release。
4. 发布 Release 时，CI 会在编译前校验 tag 与源码版本，并在编译后校验 APK 内部版本，不匹配会立即停止发布。
5. 如果 Release 描述为空，自动填入安装说明和 GitHub 生成的 What's Changed，按 [`.github/release.yml`](../.github/release.yml) 里的标签分组。已有的描述不会被覆盖。

给 PR 打上 `feature`、`bug`、`api19`、`ci`、`docs` 等标签，它们才会出现在对应分组里。

## 适用范围

- **唯一**支持的设备是上述 CS11。所有调优都针对它的 Dalvik 运行时、厂商编解码器和内核，其它设备的行为不做承诺。
- APK 面向 API 19，在 Android 14+ 上默认无法安装，需要 `adb install --bypass-low-target-sdk-block`；平板台架已退役。
- 有线 CarPlay 走用户态 NCM 桥，不需要 root；内核 `cdc_ncm` 路径及其 root 触发已移除。
- 应用不修改 `/system`，不安装打过补丁的第三方 APK，无线路径不需要 root。

## 配件身份

`DiPlayBootstrap` 在首次启动时把 `assets/offline-mfi/{identity.pk8,certificate.p7b}` 复制到应用私有目录，`LocalMfiAuthenticationClient` 用它完成配件认证。仓库里提交的这一对，与上游 DiPlay 公开发布的 APK（`DiPlay-0.2.12.apk`）里的**字节完全一致**，所以不会扩大已有的暴露面。但要知道：它是 Apple 签发的配件身份，**任何人都能提取其私钥**，公开它可能违反 MFi 条款并导致吊销。

个人使用之外请**替换它**：把你自己的一对放进 `mobile/src/main/auth-assets/offline-mfi/`，或让 `DIPLAY_AUTH_ASSETS_DIR` 指向含 `offline-mfi/{identity.pk8,certificate.p7b}` 的目录。当前生效的身份缺失，构建会失败；资源里出现任何其它 `.pk8`、`.p7b`、`.key`、`.pem`、`.p12`、`.pfx`、`.jks`、`.keystore` 同样会让构建失败，CI 的公开树检查也只放行这两个文件。

## 目录结构

```
shared/   协议与核心：AirPlay（RTSP、H.264、AAC/LPCM、HID、NTP、加密）、iAP2、USB/NCM、
          媒体管线、网络与热点、仪表桥、API 19 兼容层
common/   Android 宿主：Activity、设置界面、持久化、通知、权限、圆角遮罩、封面 Provider
mobile/   打包出的应用，以及已提交的配件身份
docs/     构建、车机环境、兼容性、Release 说明、许可证，以及 _digest/ 里的历史结论
tools/    开发脚本（装机、取证、归档原生符号）
```

## 文档

- [`AGENTS.md`](../AGENTS.md)：本仓库的硬规则，只许用 API ≤ 19 的符号，验证必须在车机上进行。
- [`docs/BUILD.md`](BUILD.md)：构建指南，顶部有本分支实际适用的命令和 CI 说明。
- [`docs/DEV-ENVIRONMENT-CS11.md`](DEV-ENVIRONMENT-CS11.md)：CS11 开发调试环境、车机 adb、崩溃取证。
- [`docs/_digest/`](_digest/)：历史会话结论与测量数据。
- [`docs/UPSTREAM-README.md`](UPSTREAM-README.md)：上游 README 原文；`docs/INSTALL.md` 和 `docs/COMPATIBILITY.md` 描述的是比亚迪目标，不适用于本分支。
- [`docs/THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)、`docs/licenses/`：致谢与许可证。

## 反馈

到 [Issues](https://github.com/takumi913/SBplay/issues) 提交，写明 iPhone 型号、iOS 版本和连接方式（有线或无线）。
连接失败时，请拍下「失败详情」的每一页：首页点「查看失败详情」，或 设置 → 诊断 →「查看最近错误」。
请不要贴出热点密码。

## 致谢

GPL-3.0（见 [LICENSE](../LICENSE)）。基于 shihabal3amri 的 [DiPlay](https://github.com/shihabal3amri/DiPlay)，它又基于 [xcertplay](https://github.com/shilapi/xcertplay)；主页与设置界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0，许可证文本见 `docs/licenses/`）。内置 Opus 编解码器（`shared/src/main/jniLibs/armeabi-v7a/libdiplay_opus.so`）为 Opus / libopus（BSD，见 `docs/Opus-LICENSE.txt`）。

同时参考了开源的吉利 H52 / Android 4.3 移植 [`xikai6282/DiPlay-Geely-Android43`](https://github.com/xikai6282/DiPlay-Geely-Android43)（同一代 ECARX / GKUI）：USB 短写恢复与共享截止时间、常驻 USBMUX 读请求、视频线程优先级取值、iAP2 `0x5703` BSSID 字段，都参照了该项目的报告。没有复制代码，每处改动都在这里独立实现并带有自己的测试。

再分发修改版时请保留上游和 DiAuto 的版权声明。
