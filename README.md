# 水印相机（WatermarkCamera）

一款个人自用的 Android 水印相机：拍照时把时间、地点、天气等信息直接烧录进照片，预览与成片所见即所得。**无广告、无内购、无账号体系**，不采集上传照片。

## 当前版本与状态

- 版本：**v0.0.1**（versionCode 1），版本号取自构建产物 `BuildConfig.VERSION_NAME`。
- 状态：个人自用可用版本。单模块、纯 Kotlin + Jetpack Compose，无远程后端。
- 真机验证：小米 M2007J22C / Android 12（API 31），1080×2340。

## 功能亮点

- **拍摄与实时预览**：CameraX 实时取景，水印在预览中实时渲染，拍照后按同一份规格烧录到成片。
- **水印信息**：居中时间、日期、星期、天气；地点（省 / 市 / 区县粒度）与经纬度；自定义文案（最多 20 字）。
- **水印样式**：内置「时间地点（HERO）」与「简洁时间（COMPACT）」两套样式；水印字号、文字颜色、整体透明度可调；可拖动水印位置。
- **底板样式**：经纬度行、自定义文案行的底板颜色与透明度各自独立可调（0% 即不画底板）。
- **我的模板**：把当前完整设置存为模板，支持新建、套用、改名、更新与删除；模板记录位置等全部参数。
- **拍摄控制**：前后摄像头切换、画面缩放、自动 / 强制闪光 / 关闭三档闪光灯（无闪光灯摄像头置灰提示）。
- **图片页**：浏览成片，支持多选删除（带二次确认），删除时同步清理应用私有目录中的原图。
- **外观**：深色 / 浅色 / 跟随系统主题；界面字号与水印字号独立调节。

## 技术栈与架构

- **语言 / UI**：Kotlin 2.2.20、Jetpack Compose（BOM 2025.09.01）、Material 3。
- **相机**：CameraX 1.5.3（camera-core / camera2 / lifecycle / view），`ImageCapture.flashMode` 三档闪光。
- **构建**：AGP 8.13.2、Gradle 8.14.2（wrapper 分发走腾讯镜像）、compileSdk / targetSdk 36、minSdk 29、Java 11。
- **数据**：SharedPreferences + `org.json` 手写编解码（设置、模板、图片索引）；成片经 MediaStore 写入相册。
- **网络**：高德 Web 服务 API（`restapi.amap.com`）做逆地理编码与天气查询。
- **图片处理**：`PhotoComposer` 将水印 Canvas 合成到原图后压缩保存。

单模块分层（`app/src/main/java/com/personal/watermarkcamera/`）：

```
├── AppContainer.kt        # 依赖装配（存储、定位、仓库）
├── MainActivity.kt
├── WatermarkApplication.kt
├── data/                  # SharedPreferences / MediaStore / 高德 / 合成
├── domain/                # 设置、模板、渲染器、绘制规格
└── ui/                    # 主框架、拍摄、图片、模板、设置、版本说明
```

## 如何构建运行

### 环境特殊性（务必先看）

1. **JDK**：使用 Android Studio 自带的 JBR，而不是系统 JDK。PowerShell 示例（路径按本机安装位置调整）：

   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   .\gradlew.bat assembleDebug
   ```

2. **`local.properties`**（不入库，需自行创建）：

   ```properties
   sdk.dir=C\:\\Android\\Sdk
   AMAP_WEB_KEY=你的高德 Web 服务 Key
   ```

   `AMAP_WEB_KEY` 在构建时注入 `BuildConfig`；缺失时构建仍可通过，但地址 / 天气会降级为空。**不要把真实 Key 提交到仓库。**

3. **`buildToolsVersion = "36.0.0"`**：本机只安装了 36.0.0 / 37.0.0，AGP 默认要求的 35.0.0 不存在，因此在 `app/build.gradle.kts` 中显式指定。

4. **`android-36` 平台**：compileSdk 36 需要本机 SDK 中存在 `platforms/android-36`，通过 Android Studio SDK Manager 或 `sdkmanager "platforms;android-36"` 安装。

5. **Gradle 分发**：`gradle/wrapper/gradle-wrapper.properties` 指向腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.2-bin.zip`；依赖仓库同时配置了阿里云镜像。

### 步骤

```powershell
# 1. 配置 local.properties（见上）
# 2. 设置 JAVA_HOME 指向 Android Studio JBR
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 3. 构建 Debug APK
.\gradlew.bat assembleDebug

# 4. 安装到设备
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `CAMERA` | 取景预览与拍照 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 获取经纬度并解析地址；未授权时水印显示「未获取位置」，可在设置中开启 |
| `INTERNET` | 调用高德 Web API（逆地理编码、天气） |

## 隐私说明

- 照片仅保存在本机相册（MediaStore）与应用私有目录，不上传任何服务器。
- 获取地址与天气时，经纬度 / 行政区编码会请求高德 `https://restapi.amap.com`；不请求时不会发送位置。
- 无账号、无统计埋点、无广告 SDK。
- `allowBackup=false`，应用数据不参与系统备份。

## 目录结构

```
WatermarkCamera/
├── app/
│   └── src/main/
│       ├── java/com/personal/watermarkcamera/   # 源码（data / domain / ui）
│       ├── res/                                 # 图标、主题、字符串
│       └── AndroidManifest.xml
├── docs/                                        # 图标资源等说明
├── gradle/                                      # wrapper 配置
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

## 已知限制

- **图片索引随应用数据清除**：图片页的索引存在 `SharedPreferences("photo_index")`，清除应用数据后列表丢失，但相册中的成片仍在。
- **原图不写入相册**：成片写入 MediaStore；拍摄原图保留在应用私有目录 `files/originals`，目前 UI 未提供「重新编辑」入口。
- **验证范围有限**：仅在 API 31 真机验证，API 29 / 30 及 32+ 未实测。
- **网络依赖**：天气与地址依赖高德 Web API；断网或未配置 Key 时对应内容降级/留空。
- 仅中文界面；未做 release 签名、混淆与上架配置。

## 版本记录

### v0.0.1（2026-11-05）

- 拍摄与预览：CameraX 实时取景，水印预览与成片所见即所得；前后摄像头切换与画面缩放；自动 / 强制闪光 / 关闭三档闪光灯，无闪光灯摄像头置灰提示。
- 水印信息：居中时间、日期、星期与天气；地点（省 / 市 / 区县粒度）与经纬度；自定义文案（最多 20 字）。
- 水印样式：「时间地点」与「简洁时间」两套内置样式；水印字号、文字颜色与整体透明度；拖动调整位置；经纬度底板、自定义文案底板可分别设置颜色与透明度。
- 模板与图片：我的模板（新建、套用、改名、更新、删除）；图片页浏览成片，支持多选删除（二次确认）。
- 外观：深色、浅色、跟随系统主题；界面字号与水印字号独立调节。

## 使用声明

本项目为个人自用工具，保留所有权利，未附开源许可证，不对外分发。如需引用代码，请先联系作者。
