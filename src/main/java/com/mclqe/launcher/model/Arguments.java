package com.mclqe.launcher.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 1.13+ 版本的 arguments 节点。
 * 数组元素可能是纯字符串，也可能是 {"rules": [...], "value": "x" | ["x", "y"]}。
 * 因此 value 用 {@link JsonElement} 承载，由参数构建器在运行时展开。
 */
public class Arguments {

    private List<Entry> game;
    private List<Entry> jvm;

    public List<Entry> getGame() {
        return game;
    }

    public List<Entry> getJvm() {
        return jvm;
    }

    @JsonAdapter(EntryDeserializer.class)
    public static class Entry {
        private List<Rule> rules;
        private JsonElement value;

        public List<Rule> getRules() {
            return rules;
        }

        public JsonElement getValue() {
            return value;
        }
    }

    /**
     * 兼容 1.13+ 的两种写法：arguments 数组元素既可能是纯字符串（{@code "-Xmx2G"}），
     * 也可能是带 rules 的对象（{@code {"rules":[...],"value":"..."}}）。
     */
    public static class EntryDeserializer implements JsonDeserializer<Entry> {
        @Override
        public Entry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            Entry entry = new Entry();
            if (json == null || json.isJsonNull()) {
                return entry;
            }
            if (json.isJsonObject()) {
                JsonObject object = json.getAsJsonObject();
                entry.value = object.get("value");
                JsonElement rules = object.get("rules");
                if (rules != null && rules.isJsonArray()) {
                    entry.rules = context.deserialize(rules, new TypeToken<List<Rule>>() { }.getType());
                }
            } else {
                entry.value = json;
            }
            return entry;
        }
    }
}
