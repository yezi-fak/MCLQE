package com.mclqe.launcher.game;

import com.google.gson.Gson;
import com.mclqe.launcher.model.DownloadInfo;
import com.mclqe.launcher.model.Library;
import com.mclqe.launcher.model.VersionManifest;
import com.mclqe.launcher.model.VersionMeta;
import com.mclqe.launcher.net.HttpUtils;
import com.mclqe.launcher.net.DownloadSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 版本清单与版本 JSON 的获取、缓存与继承合并。
 */
public class VersionManager {

    /** 官方版本清单地址（v2，未重写） */
    public static final String OFFICIAL_MANIFEST_URL = DownloadSource.OFFICIAL_MANIFEST_URL;

    /** 当前下载源对应的版本清单地址（随设置即时变化） */
    public static String manifestUrl() {
        return DownloadSource.active().manifestUrl();
    }

    private static final Gson GSON = new Gson();

    private final GamePaths paths;
    private final Consumer<String> logger;
    private final Map<String, VersionMeta> metaCache = new HashMap<>();
    private volatile VersionManifest manifest;

    public VersionManager(GamePaths paths, Consumer<String> logger) {
        this.paths = paths;
        this.logger = logger == null ? m -> {
        } : logger;
    }

    // ------------------------------------------------------------------ 版本清单

    /** 获取版本清单（联网，或读取本地缓存）。 */
    public VersionManifest getManifest(boolean forceDownload) throws IOException {
        if (!forceDownload && manifest != null) {
            return manifest;
        }
        Path file = paths.manifestFile();
        if (forceDownload || !Files.exists(file)) {
            String url = manifestUrl();
            logger.accept("正在拉取版本清单: " + url + "（下载源: " + DownloadSource.selected().displayName() + "）");
            HttpUtils.download(url, file, null, 0);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        VersionManifest m = GSON.fromJson(json, VersionManifest.class);
        if (m == null || m.getVersions() == null || m.getVersions().isEmpty()) {
            throw new IOException("版本清单解析失败或为空: " + file);
        }
        manifest = m;
        return m;
    }

    /** 仅读取本地缓存的清单，不存在时返回 null（不联网）。 */
    public VersionManifest getLocalManifest() {
        if (manifest != null) {
            return manifest;
        }
        Path file = paths.manifestFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            VersionManifest m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), VersionManifest.class);
            manifest = m;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 本地已安装版本

    /** 扫描本地游戏目录 versions 下已安装的版本（不联网）。 */
    public List<LocalVersion> listLocalVersions() {
        return GameDirManager.scanLocalVersions(paths.root());
    }

    // ------------------------------------------------------------------ 版本 JSON

    /**
     * 获取版本描述（自动处理 inheritsFrom 继承链合并）。
     */
    public VersionMeta getVersionMeta(String versionId, boolean forceDownload) throws IOException {
        if (!forceDownload) {
            VersionMeta cached = metaCache.get(versionId);
            if (cached != null) {
                return cached;
            }
        }

        List<VersionMeta> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = versionId;
        while (current != null && !current.isBlank()) {
            if (!visited.add(current)) {
                throw new IOException("版本继承链存在循环引用: " + versionId);
            }
            VersionMeta meta = loadRaw(current, forceDownload);
            chain.add(meta);
            current = meta.getInheritsFrom();
        }

        // chain = [child, parent, grandparent, ...]，由最底层父版本向上合并
        VersionMeta merged = chain.get(chain.size() - 1);
        for (int i = chain.size() - 2; i >= 0; i--) {
            merged = merge(merged, chain.get(i));
        }
        merged.setId(versionId);
        metaCache.put(versionId, merged);
        return merged;
    }

    private VersionMeta loadRaw(String versionId, boolean forceDownload) throws IOException {
        Path jsonFile = paths.versionJson(versionId);
        if (forceDownload || !Files.exists(jsonFile)) {
            // 首选版本清单中登记的地址（镜像地址由 HttpUtils 按下载源重写）
            String url = null;
            try {
                url = findUrl(getManifest(false), versionId);
            } catch (IOException e) {
                logger.accept("版本清单不可用（" + e.getMessage() + "），尝试直接获取版本描述文件");
            }
            // 清单缺失时退回镜像的 /version/<id>/json 直链接口
            if (url == null || url.isBlank()) {
                url = DownloadSource.active().versionJsonUrl(versionId);
            }
            if (url == null || url.isBlank()) {
                throw new IOException("版本清单中不存在该版本，且当前下载源不支持直接获取: " + versionId);
            }
            logger.accept("正在获取版本描述文件: " + versionId);
            HttpUtils.download(url, jsonFile, null, 0);
        }
        VersionMeta meta = GSON.fromJson(Files.readString(jsonFile, StandardCharsets.UTF_8), VersionMeta.class);
        if (meta == null) {
            throw new IOException("版本 JSON 解析失败: " + jsonFile);
        }
        return meta;
    }

    private static String findUrl(VersionManifest manifest, String versionId) {
        if (manifest == null || manifest.getVersions() == null) {
            return null;
        }
        for (VersionManifest.VersionEntry entry : manifest.getVersions()) {
            if (versionId.equals(entry.getId())) {
                return entry.getUrl();
            }
        }
        return null;
    }

    /** 子版本字段优先覆盖父版本；libraries 按 group:artifact 去重合并。 */
    private VersionMeta merge(VersionMeta parent, VersionMeta child) {
        if (parent == null) {
            return child;
        }
        if (child == null) {
            return parent;
        }
        VersionMeta r = new VersionMeta();
        r.setId(firstNonBlank(child.getId(), parent.getId()));
        r.setType(firstNonBlank(child.getType(), parent.getType()));
        r.setMainClass(firstNonBlank(child.getMainClass(), parent.getMainClass()));
        r.setAssets(firstNonBlank(child.getAssets(), parent.getAssets()));
        r.setJar(firstNonBlank(child.getJar(), parent.getJar()));
        r.setMinecraftArguments(firstNonBlank(child.getMinecraftArguments(), parent.getMinecraftArguments()));
        r.setAssetIndex(child.getAssetIndex() != null ? child.getAssetIndex() : parent.getAssetIndex());
        r.setArguments(child.getArguments() != null ? child.getArguments() : parent.getArguments());
        r.setJavaVersion(child.getJavaVersion() != null ? child.getJavaVersion() : parent.getJavaVersion());

        Map<String, DownloadInfo> downloads = new LinkedHashMap<>();
        if (parent.getDownloads() != null) {
            downloads.putAll(parent.getDownloads());
        }
        if (child.getDownloads() != null) {
            downloads.putAll(child.getDownloads());
        }
        r.setDownloads(downloads);

        Map<String, Library> libs = new LinkedHashMap<>();
        if (parent.getLibraries() != null) {
            for (Library lib : parent.getLibraries()) {
                libs.put(dedupKey(lib), lib);
            }
        }
        if (child.getLibraries() != null) {
            for (Library lib : child.getLibraries()) {
                libs.put(dedupKey(lib), lib);
            }
        }
        r.setLibraries(new ArrayList<>(libs.values()));
        return r;
    }

    private static String dedupKey(Library lib) {
        return lib.getGroupId() + ":" + lib.getArtifactId();
    }

    private static String firstNonBlank(String a, String b) {
        return (a == null || a.isBlank()) ? b : a;
    }
}
