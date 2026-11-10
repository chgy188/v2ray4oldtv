# V2Ray TV · v2ray4oldtv

> **支持 Android 5.0（API 21）及以上版本** —— 面向老旧手机、Android 电视盒子与 Android TV 的 V2Ray 客户端。

项目 `minSdkVersion 21`，可在 Android 5.x / 6.x / 7.x 等老设备上直接安装；同时提供 Leanback 电视启动入口，支持遥控器操作。基于 [2dust/v2rayNG](https://github.com/2dust/v2rayNG) 1.7.x 定制，V2Ray 核心（`libv2ray.aar`）已随仓库提供，克隆后无需额外下载即可编译。

## 主要特性

- **老设备友好**：最低支持 **Android 5.0（API 21）**，提供 `armeabi-v7a` / `arm64-v8a` / `x86` / `x86_64` 分包及 universal 全架构 APK
- **TV 入口**：`LEANBACK_LAUNCHER` 启动项、遥控器可操作的电视主界面与配置界面
- **配对下发**：可通过局域网配对页面把配置发送到电视端
- **完整代理能力**：VPN 模式、分应用代理、按域名/IP 分流、订阅与二维码导入等 v2rayNG 原有功能
- **内置核心**：`v2rayNG/app/libs/libv2ray.aar` 随仓库提交，开箱即用

## 版本与构建环境

| 项目 | 值 |
| --- | --- |
| 最低系统 | **Android 5.0（API 21）** |
| targetSdkVersion | 31 |
| compileSdkVersion | 34 |
| buildToolsVersion | 34.0.0 |
| JDK | 17 |
| Gradle Wrapper | 7.5.1 |
| Kotlin / AGP | 1.6.21 / 7.2.1 |

## 本地构建

1. 准备 JDK 17、Android SDK Platform 34 与 Build Tools 34.0.0。
2. 在 `v2rayNG/` 目录下创建 `local.properties`（该文件被 `.gitignore` 忽略，CI 会自动生成）：

   ```
   sdk.dir=/你的/Android/Sdk 路径
   ```

3. 执行构建：

   ```bash
   cd v2rayNG
   ./gradlew :app:assembleDebug
   ```

产物位于 `v2rayNG/app/build/outputs/apk/debug/`，按 ABI 分包的 APK 与 universal APK 都会生成；debug 构建自带调试签名，可直接安装。不确定设备架构时请使用 universal 包。

## GitHub Actions

仓库提供 `.github/workflows/build.yml`：

- 推送到 `main`、提交 PR 或手动触发（Actions → Build APK → Run workflow）：自动编译，并把全部 APK 上传为构建产物（Artifacts，保留 30 天）
- 推送 `v*` 标签（如 `v1.0.0`）：构建完成后自动创建同名 GitHub Release，并把全部 APK 作为附件上传

CI 会自动安装 JDK 17 与 Android SDK 组件、生成 `local.properties`、修正 `gradlew` 执行权限，无需配置任何密钥即可完成构建。首个发布版本为 `v1.0.0`。

## 使用提示

- 首次启动需授予 VPN 权限；WSA / 部分模拟器需先执行：
  `adb shell appops set com.v2ray.ang ACTIVATE_VPN allow`
- `geoip.dat` / `geosite.dat` 放在 `Android/data/com.v2ray.ang/files/assets`，可在应用内下载增强版规则（需已有可用代理）

## 许可

GPL-3.0，详见 [LICENSE](LICENSE)。上游项目：[2dust/v2rayNG](https://github.com/2dust/v2rayNG)。
