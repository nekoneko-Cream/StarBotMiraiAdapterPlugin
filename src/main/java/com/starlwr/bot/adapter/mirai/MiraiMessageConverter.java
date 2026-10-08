package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.plugin.StarBotComponent;

/** Converts StarBot placeholders into Mirai HTTP message-chain elements. */
@StarBotComponent
public final class MiraiMessageConverter {
    public JSONArray convert(String content) {
        JSONArray elements = new JSONArray();
        if (content == null || content.isEmpty()) {
            return elements;
        }

        int cursor = 0;
        while (cursor < content.length()) {
            int start = content.indexOf('{', cursor);
            if (start < 0) {
                addPlain(elements, content.substring(cursor));
                break;
            }
            if (start > cursor) {
                addPlain(elements, content.substring(cursor, start));
            }

            int end = content.indexOf('}', start + 1);
            if (end < 0) {
                addPlain(elements, content.substring(start));
                break;
            }

            String placeholder = content.substring(start, end + 1);
            if (!appendPlaceholder(elements, placeholder)) {
                addPlain(elements, placeholder);
            }
            cursor = end + 1;
        }
        return elements;
    }

    private boolean appendPlaceholder(JSONArray elements, String placeholder) {
        if (placeholder.startsWith("{at=") && placeholder.endsWith("}")) {
            String target = placeholder.substring(4, placeholder.length() - 1);
            if ("all".equalsIgnoreCase(target)) {
                elements.add(new JSONObject().fluentPut("type", "AtAll"));
                return true;
            }
            try {
                long qq = Long.parseLong(target);
                elements.add(new JSONObject().fluentPut("type", "At").fluentPut("target", qq));
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        if (placeholder.startsWith("{face=") && placeholder.endsWith("}")) {
            try {
                int faceId = Integer.parseInt(placeholder.substring(6, placeholder.length() - 1));
                elements.add(new JSONObject().fluentPut("type", "Face").fluentPut("faceId", faceId));
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        if (placeholder.startsWith("{image_url=") && placeholder.endsWith("}")) {
            String url = placeholder.substring(11, placeholder.length() - 1);
            if (!url.isBlank()) {
                elements.add(new JSONObject().fluentPut("type", "Image").fluentPut("url", url));
                return true;
            }
        }

        if (placeholder.startsWith("{image_path=") && placeholder.endsWith("}")) {
            String path = placeholder.substring(12, placeholder.length() - 1);
            if (!path.isBlank()) {
                elements.add(new JSONObject().fluentPut("type", "Image").fluentPut("path", path));
                return true;
            }
        }

        if (placeholder.startsWith("{image_base64=") && placeholder.endsWith("}")) {
            String base64 = placeholder.substring(14, placeholder.length() - 1);
            if (!base64.isBlank()) {
                elements.add(new JSONObject().fluentPut("type", "Image").fluentPut("base64", base64));
                return true;
            }
        }

        return false;
    }

    private void addPlain(JSONArray elements, String text) {
        if (!text.isEmpty()) {
            elements.add(new JSONObject().fluentPut("type", "Plain").fluentPut("text", text));
        }
    }
}
