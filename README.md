# 水印相机（WatermarkCamera）

一款个人自用的 Android 水印相机：拍照时把时间、地点、天气等信息直接烧录进照片，预览与成片所见即所得。**无广告、无内购、无账号体系**，不采集上传照片。

## 当前版本与状态

- 版本：**v0.0.1**（versionCode 1），版本号取自构建产物 `BuildConfig.VERSION_NAME`。
- 状态：个人自用可用版本。单模块、纯 Kotlin + Jetpack Compose，无远程后端。
- 真机验证：小米 M2007J22C / Android 12（API 31），1080×2340。

## 功能亮点

- **拍摄与实时预览**：CameraX 实时取景，水印在预览中实时渲染，拍照后按同一份规格烧录到成片；前后摄像头切换、双指缩放与倍率滑杆。
- **对焦**：点按画面空白区域做一次对焦 / 测光，白色四角框约 1.6 秒淡出；不支持对焦的摄像头会给出提示。
- **水印信息**：居中时间、日期、星期、天气；地点（省 / 市 / 区县粒度）与经纬度；自定义文案（最多 20 字）。
- **水印样式**：内置「时间地点（HERO）」与「简洁时间（COMPACT）」两套样式；水印字号、文字颜色、整体透明度可调；按在水印块上拖动调整位置（块外拖动不移动水印）。
- **底板样式**：经纬度行、自定义文案行的底板颜色与透明度各自独立可调（0% 即不画底板）。
- **闪光灯**：自动 / 强制闪光 / 关闭三档；无闪光灯摄像头置灰并提示。
- **横竖屏**：页面布局固定竖屏，横屏时只把预览画面与水印横置；方向判定带滞回与驻留，避免临界角度反复抖动。
- **倍率滑杆**：按镜头实际范围显示可用档位，支持点击档位直达、松手吸附与点击数值回到 1x。
- **我的模板**：拍摄页水印设置弹窗内「存为模板」保存当前设置的完整快照，保存后立即出现在「快速套用」；模板页支持独立新建、独立编辑、改名、更新、删除与套用，编辑模板不会改动当前拍摄水印。
- **图片页**：成片由系统 MediaStore 查询（默认无需相册权限）；全屏查看支持左右滑动切换；长按进入多选，删除带二次确认，归属被系统清空的老照片会走系统授权确认框。
- **分享**：图片页可分享单张或多选照片；「分享时移除位置信息」默认开启，分享副本会剥离 EXIF GPS。
- **重新编辑水印**：仍有私有原图的成片可重新编辑水印，覆盖相册中的同一条成片，原图保留，可反复编辑。
- **外观**：深色 / 浅色 / 跟随系统主题；界面字号（小 / 标准 / 大）与水印字号独立调节。

## 技术栈与架构

- **语言 / UI**：Kotlin 2.2.20、Jetpack Compose（BOM 2025.09.01）、Material 3。
- **相机**：CameraX 1.5.3（camera-core / camera2 / lifecycle / view），`ImageCapture.flashMode` 三档闪光。
- **构建**：AGP 8.13.2、Gradle 8.14.2（wrapper 分发走腾讯镜像）、compileSdk / targetSdk 36、minSdk 29、Java 11。
- **数据**：SharedPreferences + `org.json` 手写编解码（水印设置、模板、界面偏好）；成片经 MediaStore 写入 `Pictures/WatermarkCamera`，图片列表按本应用归属查询；私有原图与水印快照保存在应用私有目录。
- **网络**：高德 Web 服务 API（`restapi.amap.com`）做逆地理编码与天气查询。
- **图片处理**：`PhotoComposer` 将水印 Canvas 合成到原图后压缩保存；分享副本用 `exifinterface` 剥离 GPS。

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
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 获取经纬度并解析地址；未授权时水印显示「未获取位置」，可在水印设置里跳转系统设置开启 |
| `INTERNET` | 调用高德 Web API（逆地理编码、天气） |
| `READ_MEDIA_IMAGES`（Android 13+）/ `READ_EXTERNAL_STORAGE`（Android 12 及以下，`maxSdkVersion=32`） | **默认不申请**。图片页只查询本应用写入的成片，默认路径无需任何相册权限；只有用户主动点「授予权限找回旧照片」时才请求，用于清除应用数据 / 重装后找回历史成片，以及删除归属已被系统清空的老照片 |

图片页的相册权限是按需申请：未授权时列表照常显示本应用成片，不会弹出权限请求；只有在空态或删除失败对话框中点击「授予权限」才会触发系统权限弹窗。

## 隐私说明

- 照片仅保存在本机相册（MediaStore）与应用私有目录，不上传任何服务器。
- 获取地址与天气时，经纬度 / 行政区编码会请求高德 `https://restapi.amap.com`；不请求时不会发送位置。
- 分享时才会在缓存目录生成临时副本（1 小时后清理）；「分享时移除位置信息」默认开启，副本会剥离 EXIF 中的 GPS 后再交给目标应用。
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
├── gradle/                                      # wrapper 配置
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

## 已知限制

- **清除数据 / 重装后的历史照片**：图片页直接查询系统 MediaStore，默认只显示本应用归属的成片。清除应用数据或卸载重装后，媒体行的归属记录会被系统清空，旧照片需要授予相册读取权限才能重新找回；不授权不影响新拍照片。
- **删除老照片可能需要系统确认**：归属已被清空的老照片无法直接删除，会拉起系统确认框请求授权，用户允许后才真正删除；在系统框取消则照片保留。
- **重新编辑依赖私有原图**：原图只保存在应用私有目录，清除应用数据、卸载重装或被系统清理后，对应成片仍可查看、分享与删除，但无法再重新编辑水印。
- **覆盖写回**：重新编辑只覆盖相册里的同一条成片，不会另存新副本；原图与水印快照保留，因此可反复编辑。
- **验证范围有限**：仅在 API 31 真机验证，API 29 / 30 及 32+ 未逐机型实测。
- **网络依赖**：天气与地址依赖高德 Web API；断网或未配置 Key 时对应内容降级为空。
- 仅中文界面；未做 release 签名、混淆与上架配置。

## 版本记录

### v0.0.1（2026-11-05）

> 0.0.1 标签发布后继续迭代的功能也并入本条目描述；版本号暂未提升。

- 拍摄与预览：CameraX 实时取景，水印预览与成片所见即所得；前后摄像头切换；双指缩放与倍率滑杆（档位直达、吸附、点数值回到 1x）；点按空白区域对焦，白色四角框约 1.6 秒淡出；自动 / 强制闪光 / 关闭三档闪光灯。
- 方向与布局：页面固定竖屏，横屏时只横置预览画面与水印，不重排页面；方向切换带滞回与驻留，避免临界角度抖动。
- 水印信息：时间、日期、星期与天气；地点（省 / 市 / 区县粒度）与经纬度；自定义文案（最多 20 字）。
- 水印样式：「时间地点」与「简洁时间」两套内置样式；水印字号、文字颜色与整体透明度；按在水印块上拖动调整位置；经纬度底板、自定义文案底板可分别设置颜色与透明度。
- 模板：拍摄页弹窗「存为模板」保存当前完整设置并立即进入快速套用；模板页独立新建与编辑（3:4 实时预览、可拖动位置），支持套用、改名、更新、删除；编辑模板不影响当前拍摄设置。
- 图片页：查询系统 MediaStore 中的本应用成片；全屏查看左右滑动切换；长按多选，删除二次确认；归属被清空的老照片走系统授权确认框；分享单张 / 多选并默认移除 GPS 位置信息；仍有私有原图的成片可重新编辑水印并覆盖回相册。
- 外观：深色、浅色、跟随系统主题；界面字号（小 / 标准 / 大）与水印字号独立调节。

## 使用声明

本项目为个人自用工具，保留所有权利，未附开源许可证，不对外分发。如需引用代码，请先联系作者。
