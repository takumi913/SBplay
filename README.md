<div align="center">
  <h1>SBplay</h1>
  <p><strong>领克 CS11 · 有线 / 无线 CarPlay 接收端</strong></p>
  <p>
    <a href="https://github.com/takumi913/SBplay/releases"><img src="https://img.shields.io/github/v/release/takumi913/SBplay?include_prereleases&amp;display_name=tag&amp;style=flat-square&amp;logo=github&amp;logoColor=white&amp;label=Release&amp;cacheSeconds=300" alt="最新版本"></a>
    <a href="https://github.com/takumi913/SBplay/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/takumi913/SBplay/android.yml?branch=main&amp;style=flat-square&amp;logo=githubactions&amp;logoColor=white&amp;label=CI" alt="CI 状态"></a>
    <a href="https://github.com/takumi913/SBplay/releases"><img src="https://img.shields.io/badge/Android-4.4.3%20(API%2019)-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 4.4.3 / API 19"></a>
    <a href="https://github.com/takumi913/SBplay/releases"><img src="https://img.shields.io/badge/ABI-armeabi--v7a-17191D?style=flat-square" alt="ABI: armeabi-v7a"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square" alt="GPL-3.0"></a>
  </p>
</div>

在领克 CS11 车机上使用 CarPlay：支持有线和无线连接，导航和音乐信息可以显示在仪表上。

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 修改，只适配 CS11，其他车机不保证可用。

> 非 Apple 认证产品。CarPlay 是 Apple Inc. 的商标；本项目与 Apple、领克、吉利、亿咖通均无关联。

## 功能

- 无线 CarPlay
- 有线 CarPlay（USB）
- 仪表显示导航和正在播放的音乐
- 连接失败时可以查看「失败详情」，方便拍照反馈

## 状态

| 功能 | 状态 |
|:---|:---|
| 无线 CarPlay | 可用 |
| 有线 CarPlay | 可用 |
| 仪表导航、媒体卡片 | 可用 |
| 仪表专辑封面 | 未完成 |
| 稳定性与失败排查改进 | 待实车验证 |

## 下载

到 [Releases](https://github.com/takumi913/SBplay/releases) 下载最新的 APK。安装、构建和开发说明见 [`docs/GUIDE.md`](docs/GUIDE.md)。

## 反馈

到 [Issues](https://github.com/takumi913/SBplay/issues) 提交问题，写明 iPhone 型号、iOS 版本和连接方式。连接失败时，请附上「失败详情」每一页的照片。请不要贴出热点密码。

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 和 [xcertplay](https://github.com/shilapi/xcertplay)，界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0），第三方许可见 [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)。

仅供个人学习与研究，使用风险自负。安装包内置的配件身份来自公开发布的版本，二次分发前请自行评估合规性。
