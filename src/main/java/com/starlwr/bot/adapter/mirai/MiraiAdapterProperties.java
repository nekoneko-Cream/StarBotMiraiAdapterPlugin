package com.starlwr.bot.adapter.mirai;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
    private int delay = 1000;
    private boolean receiveEnabled = true;
    private int pollInterval = 1000;

    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
