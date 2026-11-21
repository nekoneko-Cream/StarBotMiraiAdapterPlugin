package com.starlwr.bot.adapter.mirai;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Configuration
@StarBotComponent
@ConfigurationProperties(prefix = "starbot.adapter.mirai")
public class MiraiAdapterProperties {
    private boolean enabled = true;
    private String platform = "mirai";
    private String host = "localhost";
    private int port = 7827;
    private String verifyKey = "StarBot";
    private long botQq;
    private boolean autoFollowOnlineBot = true;
    private int delay = 200;
    private boolean receiveEnabled = true;
    private boolean websocketEnabled = true;
    private int pollInterval = 200;
    private String commandMode = "native";
    private String commandPermission = "all";
    private List<Long> adminQqs = new ArrayList<>();
    private boolean atAllFallbackEnabled = true;
    private String atAllNoPermissionMessage = "已设置@全体成员但机器人没有群管理员权限，本次推送已自动转为普通消息发送。";
    private String atAllLimitedMessage = "今日@全体成员次数已达上限，本次推送已自动转为普通消息发送。";
    private int groupPermissionCacheSeconds = 30;
    private boolean verifyGroupReceipt = true;
    private Detect detect = new Detect();

    @Getter
    @Setter
    public static class Detect {
        private boolean enableHttpDetect = false;
        private int httpDetectInterval = 300;
        private int httpAlarmMailInterval = 3600;
        private boolean enableMessageDetect = false;
        private int messageDetectInterval = 1800;
        private int messageAlarmMailInterval = 3600;
    }

    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
