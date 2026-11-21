package com.starlwr.bot.adapter.mirai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemplatesValidationTest {

    private static StandardEnvironment loadYaml(String relativePath) throws Exception {
        Path file = Path.of(relativePath).toAbsolutePath().normalize();
        assertTrue(file.toFile().isFile(), "Template missing: " + file);
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
            .load(file.getFileName().toString(), new FileSystemResource(file));
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        for (PropertySource<?> ps : loaded) {
            sources.addFirst(ps);
        }
        return env;
    }

    @Test
    void standardApplicationTemplateBindsCleanlyWithoutOnebotConflict() throws Exception {
        StandardEnvironment env = loadYaml("templates/application.yml");
        assertEquals("7828", env.getProperty("server.port"));
        assertNull(env.getProperty("starbot.adapter.onebot.senders[0].name"), "onebot block must be omitted");

        MiraiAdapterProperties props = Binder.get(env)
            .bind("starbot.adapter.mirai", MiraiAdapterProperties.class)
            .get();
        assertTrue(props.isEnabled());
        assertEquals("mirai", props.getPlatform());
        assertEquals(7827, props.getPort());
        assertNotEquals(env.getProperty("server.port"), String.valueOf(props.getPort()));
        assertTrue(props.isAutoFollowOnlineBot());
        assertTrue(props.isWebsocketEnabled());
        assertTrue(props.isAtAllFallbackEnabled());
        assertTrue(props.isVerifyGroupReceipt());
        assertEquals("all", props.getCommandPermission());
        assertFalse(props.getDetect().isEnableHttpDetect());
        assertFalse(props.getDetect().isEnableMessageDetect());
    }

    @Test
    void mailAlarmApplicationTemplateBindsCleanlyWithMailAndDetectEnabled() throws Exception {
        StandardEnvironment env = loadYaml("templates/application-mail-alarm.yml");
        assertEquals("7828", env.getProperty("server.port"));
        assertNull(env.getProperty("starbot.adapter.onebot.senders[0].name"), "onebot block must be omitted");
        assertEquals("smtp.qq.com", env.getProperty("spring.mail.host"));
        assertEquals("587", env.getProperty("spring.mail.port"));
        assertNotNull(env.getProperty("starbot.core.mail.default-to"));

        MiraiAdapterProperties props = Binder.get(env)
            .bind("starbot.adapter.mirai", MiraiAdapterProperties.class)
            .get();
        assertTrue(props.isEnabled());
        assertEquals(7827, props.getPort());
        assertTrue(props.getDetect().isEnableHttpDetect());
        assertEquals(300, props.getDetect().getHttpDetectInterval());
        assertEquals(3600, props.getDetect().getHttpAlarmMailInterval());
        assertTrue(props.getDetect().isEnableMessageDetect());
        assertEquals(1800, props.getDetect().getMessageDetectInterval());
        assertEquals(3600, props.getDetect().getMessageAlarmMailInterval());
    }

    @Test
    void miraiApiHttpSettingTemplateParsesCleanly() throws Exception {
        StandardEnvironment env = loadYaml("templates/mirai-api-http-setting.yml");
        assertEquals("http", env.getProperty("adapters[0]"));
        assertEquals("ws", env.getProperty("adapters[1]"));
        assertEquals("true", env.getProperty("enableVerify"));
        assertEquals("false", env.getProperty("singleMode"));
        assertEquals("7827", env.getProperty("adapterSettings.http.port"));
        assertEquals("7827", env.getProperty("adapterSettings.ws.port"));
    }
}
