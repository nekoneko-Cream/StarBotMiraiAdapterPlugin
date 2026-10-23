package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

/** Mirai transport only; StarBot Core owns message queue and callbacks. */
@Slf4j
@StarBotComponent
public class MiraiAdapterService implements AutoCloseable {
    private final MiraiAdapterProperties properties;
    private final MiraiMessageConverter converter;
    private final MiraiApiClient client;

    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter, MiraiApiClient client) {
        this.properties = properties;
        this.converter = converter;
        this.client = client;
    }

    @Autowired
    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter, RestTemplate http) {
        this(properties, converter, new MiraiApiClient(properties.getBaseUrl(), http));
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled() || properties.getBotQq() <= 0) return;
        try {
            client.connect(properties.getBotQq(), properties.getVerifyKey());
            log.info("Mirai API HTTP 鉴权和 Bot 绑定成功");
        } catch (Exception e) {
            log.error("Mirai 鉴权或绑定失败，下次请求将重试（{}）", e.getClass().getSimpleName());
        }
    }

    public JSONObject send(String platform, int type, long target, String content) {
        if (!properties.isEnabled() || !properties.getPlatform().equals(platform)) return failure("Mirai 平台不存在或已禁用");
        if (target <= 0 || content == null) return failure("Mirai 消息目标或内容无效");
        try {
            JSONArray chain = converter.convert(content);
            if (chain.isEmpty()) return failure("Mirai 消息为空");
            String id = switch (PushTargetType.of(type)) {
                case GROUP -> client.sendGroupMessage(target, chain);
                case FRIEND -> client.sendFriendMessage(target, chain);
                default -> throw new IllegalArgumentException("Unsupported target type");
            };
            return new JSONObject().fluentPut("code", 0).fluentPut("message", "success").fluentPut("id", id);
        } catch (Exception e) {
            log.warn("Mirai 发送接口异常（{}）", e.getClass().getSimpleName());
            return failure("Mirai 消息发送失败：" + e.getClass().getSimpleName());
        }
    }

    static JSONObject failure(String reason) {
        return new JSONObject().fluentPut("code", 1).fluentPut("message", reason).fluentPut("id", null);
    }

    public JSONArray fetchMessages(int count) {
        return client.fetchMessages(count);
    }

    public JSONObject checkConnection() {
        return client.checkConnection();
    }

    @Override
    @PreDestroy
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("释放 Mirai 会话失败（{}）", e.getClass().getSimpleName());
        }
    }
}
