package com.mclqe.launcher.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mclqe.launcher.net.HttpUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Modrinth 开放接口客户端（无 API Key）。
 *
 * <p>端点：{@code /search}、{@code /project/{id}}、{@code /project/{id}/version}、{@code /tag/game_version}。
 * 默认走官方 {@code api.modrinth.com}，失败后自动降级到中国镜像
 * {@code mod.mcimirror.top/modrinth}（可在界面关闭镜像）。搜索结果缓存 5 分钟，
 * 避免切换页面 / 重复搜索产生多余请求。</p>
 */
public class ModrinthClient {

    public static final String OFFICIAL_API = "https://api.modrinth.com/v2";
    public static final String MIRROR_API = "https://mod.mcimirror.top/modrinth/v2";

    /** 搜索缓存有效期。 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int CACHE_MAX = 30;

    private final Consumer<String> logger;
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    private volatile boolean mirrorEnabled;
    private volatile boolean mirrorFallbackUsed;

    public ModrinthClient(Consumer<String> logger) {
        this.logger = logger == null ? msg -> { } : logger;
    }

    public boolean isMirrorEnabled() {
        return mirrorEnabled;
    }

    public void setMirrorEnabled(boolean enabled) {
        this.mirrorEnabled = enabled;
        cache.clear();
    }

    // ------------------------------------------------------------------ 接口

    /** 搜索模组。{@code gameVersion} / {@code loader} 传 {@code null}、空串或 {@code all} 表示不过滤。 */
    public SearchResult search(String query, String gameVersion, String loader, String sort, int offset, int limit)
            throws IOException {
        return search(query, gameVersion, loader, sort, offset, limit, null);
    }

    /** 搜索模组，附带分类（列表内为「或」关系，最多 8 个）。 */
    public SearchResult search(String query, String gameVersion, String loader, String sort, int offset, int limit,
            List<String> categories) throws IOException {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeOffset = Math.max(0, offset);
        String key = "search|" + nz(query) + "|" + nz(gameVersion) + "|" + nz(loader) + "|" + nz(sort)
                + "|" + safeOffset + "|" + safeLimit + "|" + (categories == null ? "" : String.join(",", categories));
        String cached = cachedBody(key);
        String body = cached != null ? cached : get("/search?limit=" + safeLimit + "&offset=" + safeOffset
                + "&index=" + index(sort)
                + (nz(query).isBlank() ? "" : "&query=" + enc(query))
                + "&facets=" + enc(facets(gameVersion, loader, categories)));
        if (cached == null) {
            remember(key, body);
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        List<ModHit> hits = new ArrayList<>();
        JsonArray arr = root.has("hits") && root.get("hits").isJsonArray() ? root.getAsJsonArray("hits") : new JsonArray();
        for (JsonElement element : arr) {
            if (element.isJsonObject()) {
                hits.add(parseHit(element.getAsJsonObject()));
            }
        }
        return new SearchResult(hits, asInt(root.get("total_hits")), safeOffset, safeLimit);
    }

    /** 项目详情。 */
    public ModProject project(String idOrSlug) throws IOException {
        String key = "project|" + nz(idOrSlug);
        String cached = cachedBody(key);
        String body = cached != null ? cached : get("/project/" + enc(idOrSlug));
        if (cached == null) {
            remember(key, body);
        }
        return parseProject(JsonParser.parseString(body).getAsJsonObject());
    }

    /**
     * 版本列表（接口按发布时间倒序返回）。
     *
     * @param gameVersion 目标 MC 版本，null/空/all 表示不过滤
     * @param loader      加载器，null/空/all 表示不过滤
     */
    public List<ModVersion> versions(String idOrSlug, String gameVersion, String loader) throws IOException {
        StringBuilder path = new StringBuilder("/project/").append(enc(idOrSlug)).append("/version");
        boolean first = true;
        if (filtering(gameVersion)) {
            path.append(first ? '?' : '&').append("game_versions=").append(enc(jsonArray(gameVersion)));
            first = false;
        }
        if (filtering(loader)) {
            path.append(first ? '?' : '&').append("loaders=").append(enc(jsonArray(loader)));
        }
        String key = "versions|" + nz(idOrSlug) + "|" + nz(gameVersion) + "|" + nz(loader);
        String cached = cachedBody(key);
        String body = cached != null ? cached : get(path.toString());
        if (cached == null) {
            remember(key, body);
        }
        JsonElement parsed = JsonParser.parseString(body);
        List<ModVersion> list = new ArrayList<>();
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    list.add(parseVersion(element.getAsJsonObject()));
                }
            }
        }
        return list;
    }

    /** 全部正式版 MC 版本（新→旧）。 */
    public List<String> gameVersions() throws IOException {
        String key = "tag|game_version";
        String cached = cachedBody(key);
        String body = cached != null ? cached : get("/tag/game_version");
        if (cached == null) {
            remember(key, body);
        }
        List<String> list = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(body);
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String type = str(obj, "version_type");
                if (type.isEmpty() || "release".equalsIgnoreCase(type)) {
                    String version = str(obj, "version");
                    if (!version.isEmpty()) {
                        list.add(version);
                    }
                }
            }
        }
        return list;
    }

    /** 模组分类（{@code /tag/category}，已剔除加载器与分组标题）。 */
    public List<String> categories() throws IOException {
        String key = "tag|category";
        String cached = cachedBody(key);
        String body = cached != null ? cached : get("/tag/category");
        if (cached == null) {
            remember(key, body);
        }
        List<String> list = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(body);
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("header") && obj.get("header").isJsonPrimitive() && obj.get("header").getAsBoolean()) {
                    continue;
                }
                String type = str(obj, "project_type");
                if (!type.isEmpty() && !"mod".equalsIgnoreCase(type)) {
                    continue;
                }
                String name = str(obj, "name");
                if (name.isEmpty() || LOADER_TAGS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                list.add(name);
            }
        }
        return list;
    }

    private static final java.util.Set<String> LOADER_TAGS = java.util.Set.of(
            "fabric", "forge", "neoforge", "quilt", "rift", "liteloader", "modloader",
            "bukkit", "paper", "purpur", "spigot", "sponge", "bungeecord", "waterfall", "velocity", "folia");

    /** 清空搜索 / 项目缓存（切换镜像或强制刷新时使用）。 */
    public void clearCache() {
        cache.clear();
    }

    // ------------------------------------------------------------------ HTTP

    private String get(String path) throws IOException {
        IOException last = null;
        for (String base : bases()) {
            try {
                String body = HttpUtils.getString(base + path);
                if (MIRROR_API.equals(base)) {
                    mirrorFallbackUsed = true;
                }
                return body;
            } catch (IOException e) {
                last = e;
                logger.accept("Modrinth 请求失败（" + host(base) + "）：" + shortMessage(e) + "，尝试备用地址");
            }
        }
        mirrorFallbackUsed = false;
        throw new IOException("Modrinth 接口不可用：官方与镜像均请求失败", last);
    }

    private List<String> bases() {
        if (mirrorEnabled) {
            return List.of(MIRROR_API, OFFICIAL_API);
        }
        return List.of(OFFICIAL_API, MIRROR_API);
    }

    private static String host(String base) {
        return base.contains("mcimirror") ? "中国镜像" : "官方";
    }

    /** 是否已使用过镜像兜底。 */
    public boolean mirrorFallbackUsed() {
        return mirrorFallbackUsed;
    }

    private String cachedBody(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.time > CACHE_TTL_MS) {
            cache.remove(key);
            return null;
        }
        return entry.body;
    }

    private void remember(String key, String body) {
        if (body != null && !body.isBlank()) {
            cache.put(key, new CacheEntry(body, System.currentTimeMillis()));
        }
    }

    private record CacheEntry(String body, long time) {
    }

    // ------------------------------------------------------------------ 解析

    private ModHit parseHit(JsonObject obj) {
        return new ModHit(
                str(obj, "project_id"),
                str(obj, "slug"),
                str(obj, "title"),
                str(obj, "description"),
                str(obj, "author"),
                str(obj, "icon_url"),
                asLong(obj.get("downloads")),
                asLong(obj.get("follows")),
                strList(obj.get("categories")),
                strList(obj.get("versions")),
                str(obj, "date_modified"),
                str(obj, "latest_version"));
    }

    private ModProject parseProject(JsonObject obj) {
        return new ModProject(
                str(obj, "id"),
                str(obj, "slug"),
                str(obj, "title"),
                str(obj, "description"),
                str(obj, "body"),
                str(obj, "icon_url"),
                asLong(obj.get("downloads")),
                asLong(obj.get("follows")),
                strList(obj.get("categories")),
                strList(obj.get("game_versions")),
                str(obj, "project_type"));
    }

    private ModVersion parseVersion(JsonObject obj) {
        List<ModFile> files = new ArrayList<>();
        if (obj.has("files") && obj.get("files").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("files")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject file = element.getAsJsonObject();
                JsonObject hashes = file.has("hashes") && file.get("hashes").isJsonObject()
                        ? file.getAsJsonObject("hashes") : new JsonObject();
                files.add(new ModFile(
                        str(file, "url"),
                        str(file, "filename"),
                        asLong(file.get("size")),
                        file.has("primary") && file.get("primary").isJsonPrimitive() && file.get("primary").getAsBoolean(),
                        str(hashes, "sha1"),
                        str(hashes, "sha512")));
            }
        }
        List<ModDependency> dependencies = new ArrayList<>();
        if (obj.has("dependencies") && obj.get("dependencies").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("dependencies")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject dep = element.getAsJsonObject();
                dependencies.add(new ModDependency(
                        str(dep, "version_id"),
                        str(dep, "project_id"),
                        str(dep, "dependency_type")));
            }
        }
        return new ModVersion(
                str(obj, "id"),
                str(obj, "project_id"),
                str(obj, "name"),
                str(obj, "version_number"),
                str(obj, "version_type"),
                strList(obj.get("game_versions")),
                strList(obj.get("loaders")),
                str(obj, "date_published"),
                asLong(obj.get("downloads")),
                files,
                dependencies);
    }

    private static String str(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static long asLong(JsonElement element) {
        try {
            return element == null || element.isJsonNull() ? 0L : element.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static int asInt(JsonElement element) {
        try {
            return element == null || element.isJsonNull() ? 0 : element.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static List<String> strList(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive()) {
                list.add(item.getAsString());
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ 参数

    private static String facets(String gameVersion, String loader, List<String> categories) {
        List<List<String>> groups = new ArrayList<>();
        groups.add(List.of("project_type:mod"));
        if (filtering(loader)) {
            groups.add(List.of("categories:" + loader));
        }
        if (filtering(gameVersion)) {
            groups.add(List.of("versions:" + gameVersion));
        }
        if (categories != null && !categories.isEmpty()) {
            List<String> group = new ArrayList<>();
            for (String category : categories) {
                if (category != null && !category.isBlank()) {
                    group.add("categories:" + category.trim());
                }
            }
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            List<String> group = groups.get(i);
            for (int j = 0; j < group.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append('"').append(group.get(j)).append('"');
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private static String jsonArray(String value) {
        return "[\"" + value + "\"]";
    }

    private static boolean filtering(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        // 「全部 / any / all」表示不限制该维度，绝不能作为 facet 下发，否则会筛成 0 结果
        return !("all".equalsIgnoreCase(trimmed)
                || "any".equalsIgnoreCase(trimmed)
                || "全部".equals(trimmed));
    }

    private static String index(String sort) {
        if (sort == null) {
            return "relevance";
        }
        return switch (sort.toLowerCase()) {
            case "downloads" -> "downloads";
            case "newest" -> "newest";
            case "updated" -> "updated";
            case "follows" -> "follows";
            default -> "relevance";
        };
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
