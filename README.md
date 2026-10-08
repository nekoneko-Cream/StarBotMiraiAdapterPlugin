# StarBotMiraiAdapterPlugin

通过现有 MCL / Mirai Console 的 mirai-api-http，将 StarBot 3 的推送发送到 QQ。适用于希望继续使用 Mirai 的用户。

**当前版本：1.0.1-beta.1，测试版。** 编译和 10 项本地测试已通过；本次发布没有验证已登录 QQ 的实际消息投递。请先在测试群确认开播、动态和图片推送。

## 环境

- 面向 StarBot 3.0 beta10，编译依赖 `starbot-core:3.0.0`。插件使用 Core 的内部发送方法，其他版本需要重新验证。
- Java 17 或以上；本次构建使用 Java 21、Maven 3.9.10。
- 现有 MCL / Mirai Console，安装 mirai-api-http 2.x，启用 HTTP adapter，QQ Bot 已登录。
- 插件不提供 QQ 登录或签名服务，原有登录环境仍需正常工作。

## Windows 安装

1. 从 [Releases](https://github.com/nekoneko-Cream/StarBotMiraiAdapterPlugin/releases) 下载 JAR，可用附带的 SHA256 文件核对。
2. 停止 StarBot 3，备份 `application.yml` 和数据源配置。
3. 将 JAR 放入 **StarBot 3** 的 `plugins` 文件夹。同一插件只保留一个版本；不要放入 MCL 的 `plugins`。
4. 按下方配置合并设置，先启动 MCL 并登录 QQ，再启动 StarBot 3。
5. 检查 StarBot 日志是否出现插件注册和“Mirai API HTTP 鉴权和 Bot 绑定成功”。这只代表连接成功，还需检查测试群实际收到消息。

## 配置

### MCL / mirai-api-http

编辑 MCL 的 `config/net.mamoe.mirai-api-http/setting.yml`，在现有设置中确认：

```yaml
adapters:
  - http
enableVerify: true
verifyKey: "replace-with-your-mirai-verify-key"
singleMode: false
adapterSettings:
  http:
    host: 127.0.0.1
    port: 7827
    cors: ["*"]
```

示例密钥必须自行替换，两边保持一致。此插件使用 `/verify`、`/bind` 和发送接口；WebSocket 无需为此插件额外开启。保留现有其他 adapter 设置。

### StarBot 3

在 StarBot 的 `application.yml` 合并：

```yaml
server:
  port: 7828

starbot:
  adapter:
    mirai:
      enabled: true
      platform: mirai
      host: 127.0.0.1
      port: 7827
      verify-key: "replace-with-your-mirai-verify-key"
      bot-qq: 123456789
      delay: 1000
```

`123456789` 是示例 Bot QQ，必须替换为 MCL 已登录的账号。`delay` 单位是毫秒，表示每条消息处理后的等待时间。StarBot 的服务端口和 Mirai HTTP 端口必须不同。合并到已有 `starbot` 节点，避免重复 YAML 键。

将 **3.0 格式**数据源中需要通过 Mirai 推送的目标 `platform` 改成 `mirai`，与插件配置保持一致。保留目标类型、群号、启用状态和消息模板。插件直接接管此平台的发送，不需要配置该平台的 Core HTTP sender。

这是发送适配器，未实现 StarBot 2.0 数据源、Redis 数据或旧命令的自动迁移。不要直接用 2.0 配置覆盖 3.0 配置；请按 3.0 的数据源结构迁移订阅。

## 支持范围

- 群消息、好友消息。
- 文字、`{at=all}`、`{at=QQ号}`、`{face=表情ID}`。
- `{image_url=URL}`、`{image_path=路径}`、`{image_base64=内容}`。本地路径由 Mirai 所在机器读取。
- `{next}` 由 StarBot Core 拆分，插件顺序发送拆分后的消息。
- 串行内存队列、发送间隔、发送前拦截器、完成/成功/失败回调。
- 会话错误码 3/4 时重新鉴权绑定，重发一次；其他错误记录日志并触发失败回调。

未知或格式不正确的占位符保留为文本。此版本不接收 QQ 消息，不处理群命令、撤回、语音、视频和临时会话；队列不持久化，停止程序会丢弃待发送消息。AOP 接管仅作用于配置的平台，其他平台交给 StarBot 原有发送器。

## 排查连接

- `Connection refused`：检查 MCL 是否启动、HTTP adapter 是否开启、端口是否一致。
- 鉴权失败：核对两边的 `verifyKey`。
- `/bind` 返回 code 2：检查指定 QQ 是否已经在 Mirai 登录。
- 连接成功但没有推送：检查数据源的 `platform`、目标启用状态、Bot 是否在群内及测试消息是否产生。

## 源码构建与验证

```powershell
mvn -B -ntp clean verify
Get-FileHash .\target\starbot-mirai-adapter-1.0.1-beta.1.jar -Algorithm SHA256
```

本地测试覆盖消息转换、鉴权/绑定/群发送请求、平台过滤、拦截取消、成功/失败回调、失败后继续处理以及发送间隔。测试使用虚构账号和本机模拟 HTTP 服务，不连接真实 QQ。

JAR 含 `plugin.json` 与 `dependency.json`，依赖由 StarBot 插件加载器处理。重复构建只复制这两个元数据文件，避免将构建报告或上次 JAR 打包进插件。

## 许可证与参考

保留上游 [LICENSE](LICENSE) 全文，其中包含 AGPL-3.0 及上游附加条款。项目配置参考 [StarBotExamplePlugin](https://github.com/Starlwr/StarBotExamplePlugin)，发送回调语义参考 [StarBotCore](https://github.com/Starlwr/StarBotCore)。此仓库由 nekoneko-Cream 维护。
