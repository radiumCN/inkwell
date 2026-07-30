# Inkwell · 墨水

> 一款克制、专注的 Android 小说阅读器 —— 暖纸质感、仿真翻页、进度永不丢失。本地 txt / EPUB / MOBI(AZW3) 阅读 · 自定义书源在线追更 · WebDAV 跨端同步。

![platform](https://img.shields.io/badge/Android-15%2B%20(minSdk%2035)-3DDC84?logo=android&logoColor=white)
![kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4)
![license](https://img.shields.io/badge/License-MIT-blue)
[![release](https://img.shields.io/github/v/release/radiumCN/inkwell?display_name=tag)](https://github.com/radiumCN/inkwell/releases)

Inkwell 用 Jetpack Compose 从零写成，没有广告、没有账号、没有推送。排版引擎全自绘、进度以「章节 + 章内偏移」为真身，改字号也不丢位置；书源、进度、书架通过 WebDAV 单文件同步，书籍文件不上云。

## ✨ 功能特性

**阅读**
- 四种翻页：仿真（贝塞尔卷页 + 背面镜像 + 折线阴影）/ 覆盖 / 平移 / 无动画，拖拽跟手、按位移与速度裁决提交或回滚，左右点击与音量键复用同一动画。
- 自绘排版引擎：Canvas + `TextMeasurer` 分页，测量产出的 `TextLayoutResult` 即渲染对象，机制上杜绝分页溢出。
- 进度真身为 `(章节索引, 章内字符偏移)`，页码只是当前排版下的投影 —— 换字号 / 字体 / 屏幕都不丢位置。
- 四款系统预设字体（默认 / 衬线 / 无衬线 / 等宽），亮暗主题跟随暖纸配色。
- 阅读中可换源；同一次阅读会话内会复用已搜到的候选源，不必每次重搜。

**书架与书源**
- 本地导入 txt / EPUB / MOBI(AZW3)，与网络书源统一解析为 `ContentElement` 段落流，一套引擎渲染全部格式；同名同作者的本地书不会重复入库。
- 自定义 JSON 书源在线阅读；也可直接导入 Legado（阅读）文本书源，运行时按原生规则求值（含 JS / XPath 等，见下节）。
- 追更：书架下拉刷新、最新章节、未读红点。
- 分组筛选、书内 / 全局搜索、可开关的发现页。
- 长按书籍弹出操作面板（详情 / 分组 / 删除等）；面板内可进入多选，批量删除、分组或隐藏。
- 私密书架：可选生物识别 / 密码保护的隐藏分区，入口本身不可见（长按书架标题进入）。
- RSS 订阅：导入订阅源、浏览文章列表与正文。

**同步与维护**
- WebDAV 单文件（`inkwell/backup.json.gz`）增量同步，字段级 Last-Write-Wins 合并（进度按 `readAt`、元数据按 `updatedAt`）；凭据经系统密钥库加密存放。
- 应用内更新检查，稳定 / 测试双通道。
- 一键清理正文缓存（不影响书架与阅读进度）。

## 🧩 模块结构

| 模块 | 说明 |
|---|---|
| `:core` | 纯 JVM：统一书籍模型、txt/EPUB/MOBI 解析器、书源规则引擎（含 Rhino JS）、WebDAV 客户端与备份合并（全部可 JVM 单测） |
| `:reader` | 阅读排版引擎：Canvas 自绘 + `TextMeasurer` 分页；测量层抽象为接口，分页算法纯 Kotlin 可测 |
| `:app` | Compose UI、Room、DataStore、导航、DI（Koin）、同步 |

技术栈：Kotlin 2.3 · Jetpack Compose · Room · Koin · OkHttp · Jsoup · Rhino · kotlinx.serialization。

## 🔨 构建

```bash
# 需要 JDK 21 与 Android SDK (platform 36+, build-tools 与 compileSdk 对齐)
./gradlew assembleDebug   # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # 全部 JVM 单元测试
```

在 `local.properties` 配置 `sdk.dir=<Android SDK 路径>`。正式签名从环境变量注入（CI 用），本地未配置时自动回落 debug 签名。

## 🎨 核心设计

- **测量渲染同源**：分页时产出的 `TextLayoutResult` 就是渲染时绘制的对象，从机制上消灭「分页看着对、渲染却溢出」。
- **进度与排版解耦**：进度是 `(章节, 章内偏移)`，页码是投影；任何排版参数变化都能重新定位到同一句话。
- **统一段落流**：txt / EPUB / MOBI / 网络书源统一解析为 `ContentElement`（段落 / 标题 / 图片 / 分隔线），一套排版引擎渲染全部格式。
- **可测优先**：核心逻辑（解析、分页、书源规则、备份合并）下沉到纯 JVM 模块，脱离 Android 也能单测。

## 📖 书源规则

自研 JSON Schema，选择器 mini-DSL 支持 `css:` / `json:` / `regex:` / `text:` 前缀、`||` 回退、`&&` 拼接与后处理管道：

```json
{
  "schemaVersion": 1,
  "id": "com.example.mysite",
  "name": "示例书源",
  "baseUrl": "https://www.example.com",
  "version": 1,
  "search": {
    "request": { "url": "/search?q={{keyword}}" },
    "list": "css:div.result-item",
    "fields": {
      "title": "css:h3 a@text",
      "bookUrl": "css:h3 a@href",
      "author": "css:.author@text"
    }
  },
  "detail": { "fields": { "tocUrl": "css:a.toc@href" } },
  "toc": {
    "list": "css:dd a",
    "fields": { "title": "css:@text", "url": "css:@href" }
  },
  "content": {
    "content": "css:div#content@html",
    "purify": [ { "pattern": "本章未完.*", "replacement": "", "isRegex": true } ]
  }
}
```

在「书源管理 → 剪贴板 / 网络导入」粘贴或输入链接（单个对象或数组均可），编辑页支持校验 / 格式化与全链路冒烟测试。

引擎正向「运行时原生 Legado 规则（文本源）」迁移：导入的 Legado 书源不再只做一次性静态转换，多数规则在求值时按 Legado 语义执行。

### Legado 书源兼容

面向**文本小说源**。支持的规则子集包括：

- 默认 Jsoup 层级（`class.foo.0@tag.a@href`）、`@css:`、JSONPath（`$.`）、`:正则`、XPath（`@XPath:` / `//`）
- `||` 回退、`&&` 拼接、`##正则##替换` 净化、`replaceRegex`
- JS 规则（`<js>` / `@js:` / 部分 `{{表达式}}`），由 Rhino 执行；引擎带沙箱与超时，禁止访问本地私网等危险能力
- POST 搜索（`searchUrl` 的 method/body/charset 选项，GBK 站自动 `encode:gbk`）
- `exploreUrl` 发现页（`名称::url` 格式）、`concurrentRate` 限速、倒序目录（`-` 前缀）

**不支持**：音频 / 漫画 / 文件源（`bookSourceType` ≠ 0）；依赖未实现 Legado API 或绕过沙箱的极端 JS；部分动态发现地址无法静态列举。关键规则（搜索列表 / 书名 / 链接、目录、正文）无法解析的书源会在导入或校验结果中说明；次要字段（简介 / 封面等）失败时丢弃该字段、书源仍可用。

## 📦 下载与发布

- **下载**：前往 [Releases](https://github.com/radiumCN/inkwell/releases) 获取签名 APK。应用内「设置 → 检查更新」也可直接拉取。
- **发版流程**：改 `gradle/libs.versions.toml` 的 `inkwell` 版本号 → 提交 → 打**带注解**的 tag（`git tag -a vX.Y.Z`）→ 推送 tag。CI 校验 tag 与版本号一致后自动构建签名 APK 并发布 Release。
- **Release 正文取自 tag 注解本身**，且应用内更新弹窗按**纯文本**渲染 —— tag 注解请写纯文本、不要用 Markdown。
- 带 `-` 后缀的 tag（如 `v0.1.9-beta.7`）会标为预发布，只推测试渠道。

## 📄 许可

[MIT](LICENSE)
