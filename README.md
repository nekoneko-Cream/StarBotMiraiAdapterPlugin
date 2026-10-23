# StarBotMiraiAdapterPlugin

在 StarBot 3 中继续使用现有 MCL / Mirai Console + mirai-api-http，按官方适配器的接口接入 Core。

**版本：1.1.0-beta.1。** 本版将发送流程交回 Core，并默认采用官方 `status` 行为。与旧版的差异和验证范围见 [适配清单](docs/compatibility.md)。

## 与官方流程一致的部分

- 注册普通 `Sender`，消息由 `StarBotMessageSender` 排队，Core 执行发送前拦截、间隔、完成时间和成功/失败回调。
- 支持群消息、好友消息，以及文字、表情、@、URL/路径/Base64 图片。`{next}` 由 Core 拆分。
- 默认群聊和好友私聊收到 `status` 时回复 `Running on StarBot v3.0.0`，英文大小写不限，无需在数据源预先订阅该群。
- 连接检测、收消息检测和邮件告警为可选项，默认关闭。邮件配置沿用 Core。
- B 站监听、动态、直播报告及绘图继续由官方 Bilibili 插件负责。

## 环境与安装

面向现有 StarBot 3.0 beta10，依赖 `starbot-core:3.0.0`；Java 17 或以上，本次使用 Java 21、Maven 3.9.10。需要 mirai-api-http 2.x 和已经登录的 Mirai Bot。插件不提供 QQ 登录或签名服务。

1. 备份 StarBot 配置和旧适配器 JAR，停止 StarBot。
2. 从 [Releases](https://github.com/nekoneko-Cream/StarBotMiraiAdapterPlugin/releases) 下载 JAR，放入 **StarBot 3/plugins**，同一插件仅保留一个版本。不要放进 MCL/plugins。
3. 合并下方配置。先启动 MCL 并登录 QQ，再启动 StarBot。
4. 日志应出现“Mirai 已注册为原生推送平台”和鉴权绑定成功。用群内或好友 `status` 验证真实收发。

## 配置

MCL 的 `config/net.mamoe.mirai-api-http/setting.yml` 需开启 HTTP adapter，`enableVerify: true`、`singleMode: false`，HTTP 端口例如 7827，`unreadQueueMaxSize` 大于 0。保留已有其他 adapter；本插件不要求 WebSocket。

StarBot 的 `application.yml` 合并到已有节点：

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
      receive-enabled: true
      poll-interval: 1000
      command-mode: native
      detect:
        enable-http-detect: false
        http-detect-interval: 300
        http-alarm-mail-interval: 3600
        enable-message-detect: false
        message-detect-interval: 1800
        message-alarm-mail-interval: 3600
```

账号和密钥均为示例，必须替换，密钥与 Mirai 一致。`delay`、`poll-interval` 单位为毫秒，检测和告警间隔单位为秒。轮询最小间隔 200 毫秒。StarBot 与 Mirai 端口必须不同，不要重复 YAML 节点。

3.0 数据源中需要通过 Mirai 推送的目标使用 `platform: mirai`。平台由插件自动注册，无需另外在 `starbot.core.sender` 声明同名 `mirai`，否则会与 Core 的重名保护冲突。其他推送平台配置照常使用。

## 命令模式

### native：默认

群聊和好友私聊响应 `status`，返回官方 3.0 文字。保留过期、重复、自身及错误 @ 过滤。不会启用 2.0 命令或额外帮助菜单。

### extended：保留旧版便利功能

设置 `command-mode: extended` 后支持以下附加命令；群指令仅响应当前数据源中已启用的 Mirai 群：

| 指令 | 功能 |
| --- | --- |
| `status` / `状态` | 连接说明、本群订阅数量 |
| `help` / `帮助` / `菜单` | 扩展菜单 |
| `version` / `版本` | 插件版本 |
| `subscriptions` / `订阅` / `主播列表` | 本群前 20 位主播 |

扩展模式支持 `/` 前缀，每个目标至少间隔 3 秒回复一次。这些是插件扩展，不代表官方 3.0 原生群管理命令。

## 检测与发送限制

连接检测校验绑定账号，并请求 Bot 资料及版本接口。接收检测按最后一条真实消息计时，空轮询不算消息；安静的群也可能触发接收告警。检测在独立线程执行，不阻塞收消息。告警沿用 Core 的 SMTP/收件人配置；调用邮件服务不等于邮件已送达。

普通轮询可与慢速发送并行；会话恢复与关闭仍可能等待当前请求。发送响应预算小于 Core 外层读取超时，超时后返回非零结果，禁止尚未开始的请求和后续鉴权重试。已在途请求可能实际送达，因此超时表示结果不确定，不自动重复发送。会话错误码 3/4 的一次恢复重试仅在有效预算内执行。

Core 队列在内存中，停止程序会丢弃未处理消息。原生 Core 没有暴露撤回、语音、视频、临时会话等消息占位符，本插件没有自行增加这些业务接口。当前配置只接入一个 Mirai Bot；多 Mirai 实例/多 Bot 尚未实现。

## 排查

- 7828 被占用：检查是否已有 StarBot 在后台运行，保持一个实例。
- 拒绝连接：检查 MCL HTTP adapter 和 7827 端口。
- 鉴权失败：核对密钥；绑定 code 2：核对 Bot QQ 是否已在 Mirai 登录。
- 没有群推送：检查数据源平台、群目标和消息模板；`status` 成功不代表 B 站事件或图片已验证。
- Mirai/QQ 发送仍可能有延迟；本插件不会更换签名服务或 QQ 协议。

## 构建与许可证

```powershell
mvn -B -ntp clean verify
Get-FileHash .\target\starbot-mirai-adapter-1.1.0-beta.1.jar -Algorithm SHA256
```

测试覆盖真实 Core 发送器到本地 HTTP/MockMvc 再到模拟 Mirai 的路径，以及转换、群/好友命令、会话恢复、取消和检测。模拟测试不会登录真实 QQ。

保留上游 [LICENSE](LICENSE) 全文，包含 AGPL-3.0 及上游附加条款。参考 [StarBotExamplePlugin](https://github.com/Starlwr/StarBotExamplePlugin)、[StarBotCore](https://github.com/Starlwr/StarBotCore) 和 [StarBotOneBotAdapterPlugin](https://github.com/Starlwr/StarBotOneBotAdapterPlugin)。本仓库由 nekoneko-Cream 维护。
