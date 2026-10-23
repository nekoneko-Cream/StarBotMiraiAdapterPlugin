package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiraiMessageConverterTest {
    private final MiraiMessageConverter converter = new MiraiMessageConverter();

    @Test
    void convertsPlainText() {
        JSONArray result = converter.convert("hello");
        assertEquals("Plain", result.getJSONObject(0).getString("type"));
        assertEquals("hello", result.getJSONObject(0).getString("text"));
    }

    @Test
    void convertsAtAll() {
        JSONArray result = converter.convert("开播{at=all}");
        assertEquals("Plain", result.getJSONObject(0).getString("type"));
        assertEquals("开播", result.getJSONObject(0).getString("text"));
        assertEquals("AtAll", result.getJSONObject(1).getString("type"));
    }

    @Test
    void convertsAtMember() {
        JSONArray result = converter.convert("{at=123456}");
        assertEquals("At", result.getJSONObject(0).getString("type"));
        assertEquals(123456L, result.getJSONObject(0).getLongValue("target"));
    }

    @Test
    void convertsRemoteImage() {
        JSONArray result = converter.convert("{image_url=https://example.com/a.png}");
        assertEquals("Image", result.getJSONObject(0).getString("type"));
        assertEquals("https://example.com/a.png", result.getJSONObject(0).getString("url"));
    }

    @Test
    void convertsFaceLocalImageAndBase64WithLiteralExpectedChain() {
        JSONArray result = converter.convert("{face=14} {image_path=C:/example/a.png}{image_base64=YWJj}");
        assertEquals(com.alibaba.fastjson2.JSON.parseArray("""
            [{"type":"Face","faceId":14},{"type":"Plain","text":" "},
             {"type":"Image","path":"C:/example/a.png"},{"type":"Image","base64":"YWJj"}]
            """), result);
    }

    @Test
    void preservesUnknownAndMalformedPlaceholdersAsText() {
        JSONArray result = converter.convert("hello{unknown=x}{face=NaN}{unfinished");
        String text = result.stream().map(item -> ((com.alibaba.fastjson2.JSONObject) item).getString("text"))
            .collect(java.util.stream.Collectors.joining());
        assertEquals("hello{unknown=x}{face=NaN}{unfinished", text);
    }
}
