# Inkwell

Android 阅读应用：本地 txt / EPUB / MOBI(AZW3) 阅读 + 自定义书源在线阅读 + WebDAV 同步。

- **技术栈**: Kotlin 2.2 · Jetpack Compose · Room · Koin · OkHttp · Jsoup · kotlinx.serialization
- **系统要求**: Android 15+ (minSdk 35)

## 模块结构

| 模块 | 说明 |
|---|---|
| `:core` | 纯 JVM：统一书籍模型、txt/EPUB/MOBI 解析器、书源规则引擎、WebDAV 客户端与备份合并（全部可 JVM 单测） |
| `:reader` | 阅读排版引擎：Canvas 自绘 + TextMeasurer 分页；测量层抽象为接口，分页算法纯 Kotlin 可测 |
| `:app` | Compose UI、Room、DataStore、导航、DI、同步 |

## 构建

```bash
# 需要 JDK 21 与 Android SDK (platform 36, build-tools 36)
./gradlew assembleDebug   # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # 全部 JVM 单元测试
```

`local.properties` 中配置 `sdk.dir=<Android SDK 路径>`。

## 核心设计

- **翻页**：仿真（经典贝塞尔卷页 + 背面镜像 + 折线阴影）/ 覆盖 / 平移 / 无动画四种模式，拖拽跟手、按位移与速度裁决提交或回滚，点击左右区域与音量键复用同一动画路径。
- **字体**：系统预设四款（默认/衬线/无衬线/等宽），不做字体文件导入。
- **进度真身**是 `(章节索引, 章内字符偏移)`，页码只是当前排版设置下的投影——改字号不丢位置。
- **测量渲染同源**：分页时产出的 `TextLayoutResult` 就是渲染时绘制的对象，从机制上消灭分页溢出。
- **统一段落流**：txt/EPUB/MOBI/网络书源统一解析为 `ContentElement`（段落/标题/图片/分隔线），一套排版引擎渲染全部格式。
- **书源规则**：自研 JSON Schema（不兼容 Legado），选择器 mini-DSL 支持 `css:` / `json:` / `regex:` / `text:` 前缀、`||` 回退、`&&` 拼接与后处理管道。
- **WebDAV 同步**：单文件 `inkwell/backup.json.gz`，字段级 Last-Write-Wins 合并（进度按 `readAt`、元数据按 `updatedAt`），只同步元数据/进度/书源，不上传书籍文件。

## 书源规则示例

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

在「书源管理 → 剪贴板 / 网络导入」粘贴或输入链接（单个对象或数组均可），编辑页支持校验/格式化与全链路冒烟测试。

## Legado 书源兼容

导入时自动识别 Legado（阅读）书源格式并转换，支持的规则子集：

- 默认 Jsoup 层级（`class.foo.0@tag.a@href`）、`@css:`、JSONPath（`$.`）、`:正则`
- `||` 回退、`&&` 拼接、`##正则##替换` 净化、`replaceRegex`
- POST 搜索（`searchUrl` 的 method/body/charset 选项，GBK 站自动 `encode:gbk`）
- `exploreUrl` 发现页（`名称::url` 格式）、`concurrentRate` 限速、倒序目录（`-` 前缀）

**不支持**：JS 规则（`<js>`/`@js:`/`{{表达式}}`）、XPath 规则、音频/漫画源。关键规则（搜索列表/书名/链接、目录、正文）无法转换的书源会被跳过并在导入结果中说明；次要字段（简介/封面等）无法转换时丢弃该字段、书源仍可用。
