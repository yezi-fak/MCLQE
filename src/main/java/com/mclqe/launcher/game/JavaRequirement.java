package com.mclqe.launcher.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 单个 Minecraft 版本所需的 Java 运行时要求。
 *
 * <p>判定优先级（与设计契约一致）：
 * <ol>
 *   <li>版本 JSON 的 {@code javaVersion.majorVersion}（1.17+ 官方声明，最权威）；</li>
 *   <li>子版本 JSON 未声明时沿 {@code inheritsFrom} 上溯父版本（Forge / Fabric 等模组版本常见）；</li>
 *   <li>JSON 缺失或未声明时按官方版本号规则推断：1.16.5 及以下 → 8，1.17 ~ 1.20.4 → 17，1.20.5 及以上 → 21。</li>
 * </ol>
 * 本类只做本地文件读取，不发起任何网络请求。</p>
 */
public final class JavaRequirement {

    /** 无法判定时的兜底要求（与启动器自身运行版本一致） */
    public static final int DEFAULT_MAJOR = 21;

    private static final int MAX_INHERIT_DEPTH = 5;

    /** Adoptium Temurin 下载页（官方开源 OpenJDK 发行版，国内可访问） */
    private static final String ADOPTIUM_PAGE =
            "https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jdk&version=";
    /** 微软构建的 OpenJDK 下载页（备用来源，Windows 兼容性最佳） */
    private static final String MICROSOFT_PAGE = "https://learn.microsoft.com/java/openjdk/download";

    private final String versionId;
    private final int requiredMajor;
    /** 判定来源：版本 JSON / 继承版本 JSON / 版本号规则 / 默认值 */
    private final String source;
    /** 判定细节（用于提示文案，如 "版本 JSON javaVersion.majorVersion=17"） */
    private final String detail;

    private JavaRequirement(String versionId, int requiredMajor, String source, String detail) {
        this.versionId = versionId;
        this.requiredMajor = requiredMajor;
        this.source = source;
        this.detail = detail;
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析某个版本需要的 Java 主版本。
     *
     * @param versionId   版本 id（如 1.20.1-forge-47.2.0）
     * @param versionJson 版本 JSON 路径，可为 null（缺失时按版本号规则推断）
     */
    public static JavaRequirement resolve(String versionId, Path versionJson) {
        String id = versionId == null ? "" : versionId.trim();
        if (versionJson != null) {
            JavaRequirement declared = readDeclared(id, versionJson, 0);
            if (declared != null) {
                return declared;
            }
        }
        int major = ruleMajorFor(id);
        return new JavaRequirement(id, major, "版本号规则",
                "未在版本 JSON 中找到 javaVersion 声明，按版本号规则推断需 Java " + major);
    }

    /** 递归读取 javaVersion 声明，子版本缺失时沿 inheritsFrom 上溯。 */
    private static JavaRequirement readDeclared(String versionId, Path versionJson, int depth) {
        if (depth > MAX_INHERIT_DEPTH || versionJson == null || !Files.isRegularFile(versionJson)) {
            return null;
        }
        JsonObject root;
        try {
            String json = Files.readString(versionJson, StandardCharsets.UTF_8);
            root = new Gson().fromJson(json, JsonObject.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (root == null) {
            return null;
        }
        if (root.has("javaVersion") && root.get("javaVersion").isJsonObject()) {
            JsonObject javaVersion = root.getAsJsonObject("javaVersion");
            if (javaVersion.has("majorVersion") && !javaVersion.get("majorVersion").isJsonNull()) {
                try {
                    int major = javaVersion.get("majorVersion").getAsInt();
                    if (major > 0) {
                        return new JavaRequirement(versionId, major, "版本 JSON",
                                "版本 JSON 声明 javaVersion.majorVersion=" + major
                                        + (javaVersion.has("component")
                                        ? "（" + javaVersion.get("component").getAsString() + "）" : ""));
                    }
                } catch (RuntimeException ignored) {
                    // 字段非法时继续尝试继承链
                }
            }
        }
        if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull()) {
            String parent = root.get("inheritsFrom").getAsString();
            if (parent != null && !parent.isBlank()) {
                Path parentJson = versionJson.getParent() == null
                        ? null
                        : versionJson.getParent().getParent().resolve(parent).resolve(parent + ".json");
                JavaRequirement inherited = readDeclared(versionId, parentJson, depth + 1);
                if (inherited != null) {
                    return new JavaRequirement(versionId, inherited.requiredMajor, "继承版本 JSON",
                            inherited.detail + "（继承自 " + parent + "）");
                }
            }
        }
        return null;
    }

    /**
     * 按版本号规则推断所需 Java 主版本。
     * 1.16.5 及以下 → 8；1.17 ~ 1.20.4 → 17；1.20.5 及以上（含快照） → 21。
     */
    public static int ruleMajorFor(String versionId) {
        int[] parsed = parseReleaseNumber(versionId);
        if (parsed == null) {
            // 快照（23w45a / 24w14a 等）与无法识别的自定义版本统一按最新要求处理
            return DEFAULT_MAJOR;
        }
        int minor = parsed[0];
        int patch = parsed[1];
        if (minor <= 16) {
            return 8;
        }
        if (minor <= 19) {
            return 17;
        }
        if (minor == 20) {
            return patch >= 5 ? 21 : 17;
        }
        return 21;
    }

    /** 解析 1.x.y → {x, y}；非该形态返回 null。 */
    private static int[] parseReleaseNumber(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return null;
        }
        String text = versionId.trim().toLowerCase(Locale.ROOT);
        if (!text.startsWith("1.")) {
            return null;
        }
        int minor = -1;
        int patch = 0;
        int index = 2;
        StringBuilder digits = new StringBuilder();
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            digits.append(text.charAt(index++));
        }
        if (digits.length() == 0) {
            return null;
        }
        minor = Integer.parseInt(digits.toString());
        if (index < text.length() && text.charAt(index) == '.') {
            index++;
            StringBuilder patchDigits = new StringBuilder();
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                patchDigits.append(text.charAt(index++));
            }
            if (patchDigits.length() > 0) {
                patch = Integer.parseInt(patchDigits.toString());
            }
        }
        return new int[]{minor, patch};
    }

    // ------------------------------------------------------------------ 下载地址

    /** 主下载地址：Adoptium Temurin 对应主版本的 Windows x64 JDK 下载页。 */
    public String downloadUrl() {
        if (requiredMajor == 8 || requiredMajor == 17 || requiredMajor == 21) {
            return ADOPTIUM_PAGE + requiredMajor;
        }
        return "https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jdk";
    }

    /** 备用下载地址：微软构建的 OpenJDK（与 Windows 集成度最好）。 */
    public String backupDownloadUrl() {
        return MICROSOFT_PAGE;
    }

    /** Oracle JDK 下载页（第三备选，仅提示用）。 */
    public static String oracleDownloadUrl() {
        return "https://www.oracle.com/java/technologies/downloads/";
    }

    // ------------------------------------------------------------------ 取值

    public String versionId() {
        return versionId;
    }

    public int requiredMajor() {
        return requiredMajor;
    }

    /** 判定来源：版本 JSON / 继承版本 JSON / 版本号规则 */
    public String source() {
        return source;
    }

    /** 判定细节文案 */
    public String detail() {
        return detail;
    }

    /** 人类可读的要求描述，如 "Java 21" */
    public String displayName() {
        return "Java " + requiredMajor;
    }

    /** 版本号规则与下载地址一起用于界面提示 */
    public String requirementText() {
        return "当前版本 " + (versionId == null || versionId.isBlank() ? "（未选择版本）" : versionId)
                + " 需要 " + displayName();
    }
}
