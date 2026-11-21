# StarBotMiraiAdapterPlugin

在 StarBot 3 中继续使用现有 MCL / Mirai Console + mirai-api-http，按官方适配器的接口接入 Core。

**版本：1.1.2。** 本版新增 `@全体成员` 权限预检与自动回退（机器人无管理员权限或当日全体次数用尽时自动去掉 `@全体成员` 按普通消息发出并补发群提示，避免整条推送被 QQ 静默吞掉）、群消息回执核验（识别 `messageId=-1` 静默丢弃）、WebSocket `/all` 群权限/入退群/禁言事件实时同步以及群指令权限控制（`command-permission` / `admin-qqs`），保留单 Bot 自动跟随与 WebSocket 断连自动回退 HTTP 轮询。**当前仅适配并验证支持 StarBot 3.0-beta10，其他版本不一定能用。** 与旧版的差异和验证范围见 [适配清单](docs/compatibility.md)。

## 与官方流程一致的部分

- 注册普通 `Sender`，消息由 `StarBotMessageSender` 排队，Core 执行发送前拦截、间隔、完成时间和成功/失败回调。
- 支持群消息、好友消息，以及文字、表情、@、URL/路径/Base64 图片。`{next}` 由 Core 拆分。
- 默认群聊和好友私聊收到 `status` 时回复 `Running on StarBot v3.0.0`，英文大小写不限，无需在数据源预先订阅该群。
- 连接检测、收消息检测和邮件告警为可选项，默认关闭。邮件配置沿用 Core。
- B 站监听、动态、直播报告及绘图继续由官方 Bilibili 插件负责。

## 环境与安装

**仅保证支持 StarBot 3.0-beta10（其他版本因 Core 接口变化不一定能用）**，依赖 `starbot-core:3.0.0`；Java 17 或以上，本次使用 Java 21、Maven 3.9.10。需要 mirai-api-http 2.x 和已经登录的 Mirai Bot。插件不提供 QQ 登录或签名服务。

1. 备份 StarBot 配置和旧适配器 JAR，停止 StarBot。
2. 从 [Releases](https://github.com/nekoneko-Cream/StarBotMiraiAdapterPlugin/releases) 下载 JAR，放入 **StarBot 3/plugins**，同一插件仅保留一个版本。不要放进 MCL/plugins。
3. 合并下方配置。先启动 MCL 并登录 QQ，再启动 StarBot。
4. 日志应出现“Mirai 已注册为原生推送平台”和鉴权绑定成功。用群内或好友 `status` 验证真实收发。

## 配置与直接复制模板

仓库内置了可直接复制使用的完整配置模板（支持标准版与**带邮件告警版**）：

| 模板文件 | 用途 | 使用方法 |
| --- | --- | --- |
| [templates/application.yml](templates/application.yml) | 标准版（对应官方 `application.yml`） | 复制覆盖 StarBot 3 根目录的 `application.yml`，修改 `verify-key` 即可 |
| [templates/application-mail-alarm.yml](templates/application-mail-alarm.yml) | **带邮件告警版**（对应官方 `application带邮件告警版本.yml`） | 复制到 StarBot 3 根目录并**重命名为 `application.yml`**，填写 `spring.mail`、`starbot.core.mail.default-to` 和 `verify-key` |
| [templates/mirai-api-http-setting.yml](templates/mirai-api-http-setting.yml) | 配套 Mirai 侧配置 | 复制到 Mirai 目录的 `config/net.mamoe.mirai-api-http/setting.yml`，保持 `verifyKey` 与 StarBot 一致 |

### 从官方 `application.yml` / `application带邮件告警版本.yml` 手动修改清单

如果你是在官方自带的 `application.yml` 或 `application带邮件告警版本.yml` 上直接改，请按下面逐项核对（**不要漏删 `onebot` 占位块**）：

| 操作 | 配置位置 | 说明 |
| --- | --- | --- |
| **必须修改** | `server.port` | 官方默认是 `7827`，会与 `mirai-api-http` 默认端口 `7827` 冲突；请改为 `7828`（或修改 Mirai 端口） |
| **必须删除**（仅用 Mirai 时） | `starbot.adapter.onebot` 整块（含 `senders`、`detect`、`extension.napcat`） | 官方模板里包含 `【此处需要修改，一般默认为 3000】` 等中文占位符，**不删除会导致 StarBot 启动解析端口直接报错**。如果不用 OneBot，删掉该块即可（`plugins/` 里的官方 OneBot 插件不配置也不会冲突，也可以一并移走）；如需与 OneBot 共存，则必须把占位符全部改成真实配置 |
| **必须新增** | `starbot.adapter.mirai` 整块 | 填写 `host`、`port`、`verify-key`（与 Mirai `setting.yml` 一致）。单号模式开启 `auto-follow-online-bot: true` 时 `bot-qq` 可填 `0` 或省略 |
| **不要新增** | `starbot.core.sender` 下的 `mirai` | 平台由本插件自动注册，**不要**再在 `starbot.core.sender` 手动声明同名 `mirai`，否则会触发 Core 重名保护报错 |
| **带邮件告警版必看** | `spring.mail`、`starbot.core.mail.default-to`、`starbot.adapter.mirai.detect` | 保留并填好官方的 `spring.mail` 和 `starbot.core.mail.default-to`；将原本在 `onebot.detect` 里的告警开关迁移到 `starbot.adapter.mirai.detect`（注意收消息检测字段名是 **`enable-message-detect` / `message-detect-interval` / `message-alarm-mail-interval`**，不是 OneBot 的 `enable-websocket-detect`） |
| **数据源必改** | `datasource.json`（或 MySQL 数据源）的 `targets[].platform` | 官方默认示例是 `"qq-onebot"`，使用 Mirai 推送的目标必须改为 `"mirai"`（与 `starbot.adapter.mirai.platform` 一致） |

### 配置片段参考

MCL / Mirai Console 的 `config/net.mamoe.mirai-api-http/setting.yml` 需开启 HTTP adapter，`enableVerify: true`、`singleMode: false`，HTTP 端口例如 7827，`unreadQueueMaxSize` 大于 0。建议同时开启 `ws` adapter（参考 [templates/mirai-api-http-setting.yml](templates/mirai-api-http-setting.yml)），插件会优先通过 `ws://host:port/all` 实时接收消息以及群权限/入退群/禁言事件；未开启 `ws` 或连接断开时会自动无感回退到 HTTP 轮询。

StarBot 的 `application.yml` 关键节点如下（完整可直接复制的文件见 [templates/application.yml](templates/application.yml) 与 [templates/application-mail-alarm.yml](templates/application-mail-alarm.yml)）：

```yaml
server:
  port: 7828 # 不能与 mirai-api-http 端口 7827 相同

starbot:
  adapter:
    # 仅使用 Mirai 时，请删除官方模板中自带的 onebot: 整块！
    mirai:
      enabled: true
      platform: mirai
      host: 127.0.0.1
      port: 7827
      verify-key: "replace-with-your-mirai-verify-key"
      bot-qq: 0                     # 开启 auto-follow-online-bot 时可省略或填 0，Mirai 换号时自动跟随当前唯一在线 Bot
      auto-follow-online-bot: true
      delay: 200
      receive-enabled: true
      websocket-enabled: true
      poll-interval: 200
      command-mode: native
      command-permission: all       # 可选: all / admin / owner（仅约束群聊指令，好友私聊不受影响）
      admin-qqs: []                 # 可选: 额外允许执行受限群指令的 QQ 白名单
      at-all-fallback-enabled: true # 无管理员权限或全体次数耗尽时自动回退普通消息并发送提示
      at-all-no-permission-message: "已设置@全体成员但机器人没有群管理员权限，本次推送已自动转为普通消息发送。"
      at-all-limited-message: "今日@全体成员次数已达上限，本次推送已自动转为普通消息发送。"
      group-permission-cache-seconds: 30
      verify-group-receipt: true    # 校验群发送回执 messageId，避免静默丢消息被误判为发送成功
      detect:
        enable-http-detect: false   # 使用带邮件告警版时改为 true，并配置 spring.mail 与 starbot.core.mail.default-to
        http-detect-interval: 300
        http-alarm-mail-interval: 3600
        enable-message-detect: false # 使用带邮件告警版时可改为 true（安静的群长时间无人发言也会触发）
        message-detect-interval: 1800
        message-alarm-mail-interval: 3600
```

账号和密钥均为示例，必须替换，密钥与 Mirai 一致。`auto-follow-online-bot` 默认为 `true`：当 `bot-qq` 未填写/为 `0`，或配置的 QQ 不在 Mirai 在线（例如在 Mirai 控制台执行 `/switch` 切换了账号）时，插件会自动查询 `/botList` 并绑定当前唯一在线 Bot，同时切换 WebSocket 接收和 `@机器人` 过滤；如 Mirai 同时登录了多个 Bot，请指定具体 `bot-qq` 或将 `auto-follow-online-bot` 设为 `false`。`delay`、`poll-interval` 单位为毫秒（默认均已调整为 200ms，轮询最小间隔 100ms），检测和告警间隔单位为秒。`websocket-enabled` 默认为 `true`（优先使用 `ws://host:port/all` 实时接收消息以及群权限变更、入群/退群、禁言等事件，不可用时自动回退 HTTP 轮询并按 3 秒冷却自动重连）。

`at-all-fallback-enabled` 默认为 `true`：发送含 `@全体成员` 的群消息前会先检查本群机器人权限（缓存默认 30 秒，群内升降管理员时通过 `/all` WebSocket 即时刷新）；若当前为普通成员（`MEMBER`）或当日已触发 `AT_ALL_LIMITED`，会自动去掉 `@全体成员` 改为普通消息发送，并按每群 15 秒冷却补发提示消息（留空 `""` 则仅静默回退不发提示）；若发送期间才遇到 `code=10` 权限不足、全体额度耗尽或回执超时静默丢弃（`messageId=-1`），也会自动回退补发一次普通消息。`verify-group-receipt` 默认为 `true`，对普通群消息返回 `messageId <= 0` 的静默失败会返回失败状态并输出排查日志。StarBot 与 Mirai 端口必须不同，不要重复 YAML 节点。

3.0 数据源（`datasource.json` 或 MySQL）中需要通过 Mirai 推送的目标使用 `platform: mirai`（将官方示例中的 `qq-onebot` 改为 `mirai`）。平台由插件自动注册，无需另外在 `starbot.core.sender` 声明同名 `mirai`，否则会与 Core 的重名保护冲突。其他推送平台配置照常使用。

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
- 鉴权失败：核对密钥；若提示“Mirai 当前没有在线 Bot”或“目标 Bot 未在 Mirai 在线”，请先在 Mirai 控制台登录 QQ（开启 `auto-follow-online-bot: true` 时单账号换号会自动跟随，多账号在线需在 `application.yml` 中明确指定 `bot-qq`）。
- 没有群推送：检查数据源平台、群目标和消息模板；`status` 成功不代表 B 站事件或图片已验证。
- Mirai/QQ 发送仍可能有延迟；本插件不会更换签名服务或 QQ 协议。

## 构建与许可证

```powershell
mvn -B -ntp clean verify
Get-FileHash .\target\starbot-mirai-adapter-1.1.2.jar -Algorithm SHA256
```

测试覆盖真实 Core 发送器到本地 HTTP/MockMvc 再到模拟 Mirai 的路径，以及转换、群/好友命令、会话恢复、取消和检测。模拟测试不会登录真实 QQ。

保留上游 [LICENSE](LICENSE) 全文，包含 AGPL-3.0 及上游附加条款。参考 [StarBotExamplePlugin](https://github.com/Starlwr/StarBotExamplePlugin)、[StarBotCore](https://github.com/Starlwr/StarBotCore) 和 [StarBotOneBotAdapterPlugin](https://github.com/Starlwr/StarBotOneBotAdapterPlugin)。本仓库由 nekoneko-Cream 维护。
