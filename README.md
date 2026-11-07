<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="96" alt="Melaan 图标" />
  <h1>Melaan</h1>
  <p>Android 本地漫画阅读器 · Local comics, read your way.</p>
  <p>
    <a href="https://github.com/LXFLGH/Melaan/releases/tag/lastRelease"><img src="https://img.shields.io/github/v/release/LXFLGH/Melaan?label=lastRelease" alt="Latest release" /></a>
    <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white" alt="Android 12 or newer" />
    <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose" />
  </p>
</div>

Melaan 是一款面向 **本地漫画收藏** 的 Android 阅读器。选择设备上的漫画目录，扫描归档、整理书架，然后离线阅读；漫画内容由你自行提供，应用不内置在线漫画源。

[下载当前 APK](https://raw.githubusercontent.com/LXFLGH/Melaan/main/artifacts/Melaan-debug.apk) · [查看 Releases](https://github.com/LXFLGH/Melaan/releases/tag/lastRelease) · [查看源码](https://github.com/LXFLGH/Melaan) · [反馈问题](https://github.com/LXFLGH/Melaan/issues)

## 应用截图

<!-- 由 ADB 从设备截取；请勿放入含有个人漫画标题或封面的截图。 -->

![Melaan 原生首页展示虚构漫画样例](preview/screenshots/melaan-exhibition.png)

## 功能

### 阅读

- 左右翻页或纵向连续滚动，支持 RTL/LTR 阅读方向。
- 双指缩放与平移、页码滑杆、音量键翻页和阅读时亮度调节。
- 横屏对开阅读、图片适配、书签、阅读进度恢复和章节自动衔接。
- 全局阅读偏好可由单本漫画覆盖。

### 书架

- 通过 Android 系统文件选择器授权一个或多个目录，递归扫描本地漫画。
- 收藏、用户书单、最近阅读历史、标签筛选和批量标签整理。
- 重复条目可检查后移除书架记录；不会删除归档原文件。
- R18 封面屏蔽开关可将所有漫画封面统一替换为灰色；它只遮住封面，不隐藏标题或漫画正文。
- 支持导出与合并恢复本地阅读和书架数据。

## 支持格式

| 类型 | 支持范围 |
| --- | --- |
| 外层归档 | `.7z`、`.zip`、`.cbz`、`.rar`、`.cbr` |
| 嵌套章节 | ZIP/CBZ 外层归档中的一层 ZIP/CBZ 章节归档 |
| 漫画页面 | JPEG、PNG、WebP、BMP、AVIF（Android 12 及以上） |

页面按需从归档读取并在内存中解码，不会解压漫画页到公共存储。封面缓存和最小书架索引保存在应用私有空间。RAR/CBR 的页面读取可能比 ZIP/7z 慢，尤其是大归档。

## 开始使用

1. 下载上方的当前 APK 并安装；也可以在 [GitHub Releases](https://github.com/LXFLGH/Melaan/releases/tag/lastRelease) 查看版本记录。需要 Android 12（API 31）或更新版本。
2. 打开应用，进入 **我的 → 扫描管理**，选择漫画库目录并授予读取权限。
3. 返回书架或首页打开作品；阅读进度和书签会由应用本地保存。

> `lastRelease` 当前提供的是 Debug 构建（versionName `1.0.12`，versionCode `13`），并非 Play 商店签名版本。

## 从源码构建

需要 Android SDK、JDK，以及仓库提供的 Gradle Wrapper。在项目根目录运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

生成文件：`app/build/outputs/apk/debug/app-debug.apk`

只需编译 APK 时可运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 项目结构

- `app/` — Android 应用、阅读器和本地书架。
- `Prismal/` — Liquid Glass UI 组件。
- `doc/` — 需求合同、模块说明与验证记录。
- `preview/` — UI 预览和 README 截图。
- `artifacts/` — 仓库中附带的 Debug APK。

## 隐私与许可

Melaan 只读取用户通过系统文件选择器授权的本地目录。应用不附带漫画内容或在线内容源。漫画页面按需读取，不写入公共目录；阅读记录、标签、书单及设置保存在本机。

**项目限制：禁止商用。**本项目没有授予整体开源许可证。第三方组件依照各自许可证使用，详见 [`Prismal/LICENSE`](Prismal/LICENSE) 与 [`Junrar 许可文本`](app/src/main/res/raw/junrar_license.txt)。

请仅导入你有权存储和阅读的漫画内容。功能建议和 Bug 反馈请提交到 [GitHub Issues](https://github.com/LXFLGH/Melaan/issues)。
