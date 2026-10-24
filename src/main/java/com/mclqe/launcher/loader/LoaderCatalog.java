package com.mclqe.launcher.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclqe.launcher.net.HttpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载器版本目录：从各官方元数据接口拉取可安装的加载器版本与安装器产物地址。
 *
 * <p>端点均为 2026-10 实测可用（见设计文档 2.1）：
 * <ul>
 *   <li>Fabric：https://meta.fabricmc.net/v2/versions/loader/{mc} 与 /v2/versions/installer</li>
 *   <li>Quilt：https://meta.quiltmc.org/v3/versions/loader/{mc} 与 /v3/versions/installer</li>
 *   <li>Forge：https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json</li>
 *   <li>NeoForge：https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml</li>
 * </ul></p>
 */
public class LoaderCatalog {

    public static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    public static final String QUILT_META = "https://meta.quiltmc.org/v3";
    public static final String FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    public static final String NEOFORGE_METADATA =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    public static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge";

    private static final Pattern XML_VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final int NEOFORGE_LIMIT = 200;

    private final Consumer<String> logger;

    public LoaderCatalog(Consumer<String> logger) {
        this.logger = logger == null ? msg -> { } : logger;
    }

    /** 安装器产物信息。 */
    public record InstallerArtifact(String url, String version, String sha1) {

        public String fileName() {
            int slash = url.lastIndexOf('/');
            return slash < 0 ? url : url.substring(slash + 1);
        }
    }

    /**
     * 拉取指定加载器在指定 MC 版本下可安装的版本列表（按版本号降序）。
     *
     * @throws IOException 网络失败或该 MC 版本无对应加载器版本
     */
    public List<LoaderVersion> versions(LoaderType type, String mcVersion) throws IOException {
        List<LoaderVersion> result = switch (type) {
            case FABRIC -> fabricVersions(mcVersion);
            case QUILT -> quiltVersions(mcVersion);
            case FORGE -> forgeVersions(mcVersion);
            case NEOFORGE -> neoforgeVersions(mcVersion);
        };
        if (result.isEmpty()) {
            throw new IOException(type.displayName() + " 没有 MC " + mcVersion + " 的可用版本");
        }
        return result;
    }

    /** 解析安装器 jar 的下载地址（Fabric / Quilt 走元数据，Forge / NeoForge 走 maven 拼接）。 */
    public InstallerArtifact installer(LoaderType type, String mcVersion, String loaderVersion) throws IOException {
        switch (type) {
            case FABRIC -> {
                JsonArray array = JsonParser.parseString(HttpUtils.getString(FABRIC_META + "/versions/installer"))
                        .getAsJsonArray();
                // 元数据按发布时间倒序给出，取第一个 stable 的安装器
                JsonObject chosen = null;
                for (JsonElement element : array) {
                    JsonObject object = element.getAsJsonObject();
                    if (chosen == null) {
                        chosen = object;
                    }
                    if (object.has("stable") && object.get("stable").getAsBoolean()) {
                        chosen = object;
                        break;
                    }
                }
                if (chosen == null) {
                    throw new IOException("Fabric 元数据没有返回安装器信息");
                }
                String version = str(chosen, "version");
                String url = str(chosen, "url");
                if (url == null || url.isBlank()) {
                    url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + version
                            + "/fabric-installer-" + version + ".jar";
                }
                logger.accept("Fabric 安装器版本：" + version);
                return new InstallerArtifact(url, version, null);
            }
            case QUILT -> {
                JsonArray array = JsonParser.parseString(HttpUtils.getString(QUILT_META + "/versions/installer"))
                        .getAsJsonArray();
                if (array.isEmpty()) {
                    throw new IOException("Quilt 元数据没有返回安装器信息");
                }
                JsonObject chosen = array.get(0).getAsJsonObject();
                String version = str(chosen, "version");
                String url = str(chosen, "url");
                String sha1 = null;
                if (chosen.has("hashes") && chosen.get("hashes").isJsonObject()) {
                    sha1 = str(chosen.getAsJsonObject("hashes"), "sha1");
                }
                if (url == null || url.isBlank()) {
                    url = "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/" + version
                            + "/quilt-installer-" + version + ".jar";
                }
                logger.accept("Quilt 安装器版本：" + version);
                return new InstallerArtifact(url, version, sha1);
            }
            case FORGE -> {
                String url = FORGE_MAVEN + "/" + mcVersion + "-" + loaderVersion + "/forge-" + mcVersion + "-"
                        + loaderVersion + "-installer.jar";
                logger.accept("Forge 安装器：" + url);
                return new InstallerArtifact(url, loaderVersion, null);
            }
            case NEOFORGE -> {
                String url = NEOFORGE_MAVEN + "/" + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
                logger.accept("NeoForge 安装器：" + url);
                return new InstallerArtifact(url, loaderVersion, null);
            }
            default -> throw new IOException("未知加载器类型");
        }
    }

    // ------------------------------------------------------------------ 各加载器版本列表

    private List<LoaderVersion> fabricVersions(String mcVersion) throws IOException {
        JsonArray array = JsonParser.parseString(
                HttpUtils.getString(FABRIC_META + "/versions/loader/" + mcVersion)).getAsJsonArray();
        Map<String, LoaderVersion> versions = new LinkedHashMap<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            // /versions/loader/{mc} 返回的是「加载器 + 游戏版本」组合条目，真正的加载器信息在 loader 字段内
            JsonObject loader = object.has("loader") && object.get("loader").isJsonObject()
                    ? object.getAsJsonObject("loader") : object;
            String version = str(loader, "version");
            if (version == null) {
                continue;
            }
            boolean stable = loader.has("stable") && loader.get("stable").getAsBoolean();
            versions.putIfAbsent(version, new LoaderVersion(version, stable ? "稳定" : "测试", stable));
        }
        return sortDesc(new ArrayList<>(versions.values()));
    }

    private List<LoaderVersion> quiltVersions(String mcVersion) throws IOException {
        JsonArray array = JsonParser.parseString(
                HttpUtils.getString(QUILT_META + "/versions/loader/" + mcVersion)).getAsJsonArray();
        Map<String, LoaderVersion> versions = new LinkedHashMap<>();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            JsonObject loader = object.has("loader") && object.get("loader").isJsonObject()
                    ? object.getAsJsonObject("loader") : object;
            String version = str(loader, "version");
            if (version == null) {
                continue;
            }
            // Quilt 元数据没有 stable 字段，版本号带 -beta / -pre 视为测试版
            boolean stable = !version.contains("-");
            versions.putIfAbsent(version, new LoaderVersion(version, stable ? "稳定" : "测试", stable));
        }
        return sortDesc(new ArrayList<>(versions.values()));
    }

    private List<LoaderVersion> forgeVersions(String mcVersion) throws IOException {
        JsonObject root = JsonParser.parseString(HttpUtils.getString(FORGE_PROMOS)).getAsJsonObject();
        JsonObject promos = root.has("promos") && root.get("promos").isJsonObject()
                ? root.getAsJsonObject("promos") : new JsonObject();
        String recommended = promos.has(mcVersion + "-recommended")
                ? promos.get(mcVersion + "-recommended").getAsString() : null;
        String latest = promos.has(mcVersion + "-latest")
                ? promos.get(mcVersion + "-latest").getAsString() : null;
        Map<String, LoaderVersion> versions = new LinkedHashMap<>();
        if (recommended != null) {
            versions.put(recommended, new LoaderVersion(recommended, "推荐", true));
        }
        if (latest != null) {
            versions.putIfAbsent(latest, new LoaderVersion(latest, "最新", true));
        }
        if (versions.isEmpty()) {
            throw new IOException("Forge 没有 MC " + mcVersion + " 的官方推荐/最新版本（该版本可能只提供测试版）");
        }
        return sortDesc(new ArrayList<>(versions.values()));
    }

    private List<LoaderVersion> neoforgeVersions(String mcVersion) throws IOException {
        String prefix = neoforgePrefix(mcVersion);
        if (prefix == null) {
            throw new IOException("NeoForge 不支持 MC " + mcVersion);
        }
        String xml = HttpUtils.getString(NEOFORGE_METADATA);
        Matcher matcher = XML_VERSION.matcher(xml);
        List<LoaderVersion> matched = new ArrayList<>();
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (!version.startsWith(prefix + ".")) {
                continue;
            }
            boolean stable = !version.contains("-");
            matched.add(new LoaderVersion(version, stable ? "稳定" : "测试", stable));
        }
        if (matched.isEmpty()) {
            throw new IOException("NeoForge 没有 MC " + mcVersion + " 的可用版本");
        }
        List<LoaderVersion> sorted = sortDesc(matched);
        return sorted.size() > NEOFORGE_LIMIT ? sorted.subList(0, NEOFORGE_LIMIT) : sorted;
    }

    /**
     * NeoForge 版本号与 MC 版本的映射：版本首段=MC 次版本号，第二段=MC 修订号。
     * 例：1.21.1 → 21.1，1.21.10 → 21.10，1.20.2 → 20.2；1.20.1 及更早无 NeoForge。
     */
    static String neoforgePrefix(String mcVersion) {
        if (mcVersion == null || !mcVersion.startsWith("1.")) {
            return null;
        }
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String minor = parts[1];
        String patch = parts.length >= 3 ? parts[2] : "0";
        if (LoaderType.compareMc(mcVersion, "1.20.2") < 0) {
            return null;
        }
        return parseIntSafe(minor) + "." + parseIntSafe(patch);
    }

    // ------------------------------------------------------------------ 工具

    private static List<LoaderVersion> sortDesc(List<LoaderVersion> versions) {
        versions.sort(Comparator.comparing(LoaderVersion::version, LoaderCatalog::compareVersion).reversed());
        return versions;
    }

    /** 版本号逐段比较（数字段按数值、非数字段按字典序；正式版高于同号预发布版）。 */
    static int compareVersion(String left, String right) {
        String[] a = left.split("[.\\-+]");
        String[] b = right.split("[.\\-+]");
        int size = Math.max(a.length, b.length);
        for (int i = 0; i < size; i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            // 一方已结束、另一方剩余段为预发布后缀（beta/pre/rc 等）时，正式版更大
            if (x.isEmpty() != y.isEmpty()) {
                String rest = x.isEmpty() ? y : x;
                if (!rest.matches("\\d+")) {
                    return x.isEmpty() ? 1 : -1;
                }
            }
            boolean xn = x.matches("\\d+");
            boolean yn = y.matches("\\d+");
            int cmp;
            if (xn && yn) {
                cmp = Integer.compare(parseIntSafe(x), parseIntSafe(y));
            } else {
                cmp = x.compareTo(y);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
