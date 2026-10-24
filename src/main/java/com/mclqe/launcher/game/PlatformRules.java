package com.mclqe.launcher.game;

import com.mclqe.launcher.model.Rule;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 平台规则与 natives 判定。
 * 实现官方 rules 语义：按顺序遍历，命中者覆盖 allowed 状态，最终值为最终判定。
 */
public final class PlatformRules {

    public static final String OS_NAME = detectOsName();
    public static final String OS_ARCH = detectOsArch();
    public static final String OS_VERSION = System.getProperty("os.version", "");

    /** 当前会话支持的特性开关（如自定义分辨率）。 */
    private static volatile Map<String, Boolean> features = Map.of();

    private PlatformRules() {
    }

    public static void setFeatures(Map<String, Boolean> features) {
        PlatformRules.features = features == null ? Map.of() : features;
    }

    public static void setFeature(String name, boolean enabled) {
        java.util.HashMap<String, Boolean> copy = new java.util.HashMap<>(features);
        copy.put(name, enabled);
        features = copy;
    }

    public static boolean isFeatureEnabled(String name) {
        return Boolean.TRUE.equals(features.get(name));
    }

    /** windows / osx / linux */
    public static String detectOsName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    /** x86_64 / x86 / arm64 / arm32 */
    public static String detectOsArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("arm")) {
            return "arm32";
        }
        if (arch.contains("64")) {
            return "x86_64";
        }
        return "x86";
    }

    /**
     * 判断规则列表是否放行。rules 为空视为放行。
     */
    public static boolean isAllowed(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (matches(rule)) {
                allowed = "allow".equalsIgnoreCase(rule.getAction());
            }
        }
        return allowed;
    }

    private static boolean matches(Rule rule) {
        Rule.Os os = rule.getOs();
        if (os != null) {
            if (os.getName() != null && !os.getName().isEmpty() && !os.getName().equalsIgnoreCase(OS_NAME)) {
                return false;
            }
            if (os.getArch() != null && !os.getArch().isEmpty() && !os.getArch().equalsIgnoreCase(OS_ARCH)) {
                return false;
            }
            if (os.getVersion() != null && !os.getVersion().isEmpty()) {
                try {
                    if (!Pattern.compile(os.getVersion()).matcher(OS_VERSION).find()) {
                        return false;
                    }
                } catch (Exception ignored) {
                    // 非法正则视为不匹配
                    return false;
                }
            }
        }
        List<String> required = rule.getFeatures();
        if (required != null) {
            for (String feature : required) {
                if (!isFeatureEnabled(feature)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 取该 library 在当前平台对应的 natives classifier 名称。
     * 例如 windows 平台返回 "natives-windows"（对应 downloads.classifiers 的键）。
     */
    public static String nativesClassifier(Map<String, String> natives) {
        if (natives == null || natives.isEmpty()) {
            return null;
        }
        String classifier = natives.get(OS_NAME);
        if (classifier == null) {
            return null;
        }
        // 部分旧版本使用 ${arch} 占位（32 位平台）
        if (classifier.contains("${arch}")) {
            String arch = "x86_64".equals(OS_ARCH) ? "64" : "32";
            classifier = classifier.replace("${arch}", arch);
        }
        return classifier;
    }
}
