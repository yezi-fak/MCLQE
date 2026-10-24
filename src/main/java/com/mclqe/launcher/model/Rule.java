package com.mclqe.launcher.model;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 平台规则条目：{"action": "allow|disallow", "os": {...}, "features": [...]}。
 * 用于决定某条 library / argument 是否适用于当前运行环境。
 */
public class Rule {

    private String action;
    private Os os;

    /**
     * 规则要求开启的特性。1.13+ 早期写作数组 {@code ["is_demo_user"]}，
     * 26.x 起改为对象 {@code {"is_demo_user": true}}，故以 {@link JsonElement} 承载并归一化。
     */
    private JsonElement features;

    public String getAction() {
        return action;
    }

    public Os getOs() {
        return os;
    }

    /**
     * 归一化后的必需特性列表：数组写法取全部元素，对象写法只取值为 true 的键。
     * 无特性要求时返回 {@code null}。
     */
    public List<String> getFeatures() {
        if (features == null || features.isJsonNull()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        if (features.isJsonArray()) {
            for (JsonElement element : features.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive()) {
                    result.add(element.getAsString());
                }
            }
        } else if (features.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : features.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive()) {
                    continue;
                }
                boolean enabled = value.getAsJsonPrimitive().isBoolean()
                        ? value.getAsBoolean()
                        : Boolean.parseBoolean(value.getAsString());
                if (enabled) {
                    result.add(entry.getKey());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** {"name": "windows", "version": "^10\\.", "arch": "x86"} */
    public static class Os {
        private String name;
        private String version;
        private String arch;

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getArch() {
            return arch;
        }
    }
}
