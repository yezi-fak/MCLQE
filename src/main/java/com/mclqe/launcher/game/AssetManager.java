package com.mclqe.launcher.game;

import com.google.gson.Gson;
import com.mclqe.launcher.model.AssetIndex;
import com.mclqe.launcher.model.AssetObjects;
import com.mclqe.launcher.net.DownloadManager;
import com.mclqe.launcher.net.DownloadSource;
import com.mclqe.launcher.net.HttpUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 资源（assets）处理：索引下载 + 散列对象下载任务生成。
 * 官方资源对象文件名即其 SHA1，因此「文件存在」等价于「校验通过」。
 */
public class AssetManager {

    /** 官方资源对象 CDN 前缀 */
    public static final String RESOURCES_BASE = DownloadSource.OFFICIAL_ASSETS_BASE;

    private static final Gson GSON = new Gson();

    private final GamePaths paths;
    private final Consumer<String> logger;

    public AssetManager(GamePaths paths, Consumer<String> logger) {
        this.paths = paths;
        this.logger = logger == null ? m -> {
        } : logger;
    }

    /** 下载（或复用）资源索引文件。 */
    public void ensureIndex(AssetIndex index) throws IOException {
        if (index == null || index.getUrl() == null) {
            throw new IOException("版本 JSON 缺少 assetIndex 信息");
        }
        Path file = paths.assetIndexFile(index.getId());
        logger.accept("正在获取资源索引: " + index.getId());
        HttpUtils.download(index.getUrl(), file, index.getSha1(), index.getSize());
    }

    public AssetObjects readIndex(AssetIndex index) throws IOException {
        Path file = paths.assetIndexFile(index.getId());
        AssetObjects objects = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AssetObjects.class);
        if (objects == null || objects.getObjects() == null) {
            throw new IOException("资源索引解析失败: " + file);
        }
        return objects;
    }

    /** 生成缺失资源对象的下载任务（已存在的直接跳过）。 */
    public List<DownloadManager.Task> buildDownloadTasks(AssetObjects index) {
        List<DownloadManager.Task> tasks = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, AssetObjects.ObjectEntry> entry : index.getObjects().entrySet()) {
            AssetObjects.ObjectEntry obj = entry.getValue();
            String hash = obj.getHash();
            if (hash == null || hash.length() < 2) {
                continue;
            }
            Path target = paths.assetObject(hash);
            if (Files.exists(target)) {
                skipped++;
                continue;
            }
            // 先生成官方 CDN 地址，再按当前下载源重写（rawUrl 保留官方地址用于兜底）
            String official = DownloadSource.OFFICIAL_ASSETS_BASE + hash.substring(0, 2) + "/" + hash;
            String url = DownloadSource.rewrite(official);
            tasks.add(new DownloadManager.Task(url, official, target, hash, obj.getSize()));
        }
        if (skipped > 0) {
            logger.accept("资源文件已存在 " + skipped + " 个，本次需下载 " + tasks.size() + " 个");
        }
        return tasks;
    }
}
