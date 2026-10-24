package com.mclqe.launcher.game;

import java.nio.file.Path;

/**
 * 游戏目录结构（与官方 .minecraft 布局一致）。
 */
public final class GamePaths {

    /** 版本清单文件名 */
    public static final String MANIFEST_FILE = "version_manifest_v2.json";

    private final Path root;

    public GamePaths(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path versionsDir() {
        return root.resolve("versions");
    }

    public Path librariesDir() {
        return root.resolve("libraries");
    }

    public Path assetsDir() {
        return root.resolve("assets");
    }

    public Path indexesDir() {
        return assetsDir().resolve("indexes");
    }

    public Path objectsDir() {
        return assetsDir().resolve("objects");
    }

    public Path manifestFile() {
        return versionsDir().resolve(MANIFEST_FILE);
    }

    public Path versionDir(String versionId) {
        return versionsDir().resolve(versionId);
    }

    public Path versionJson(String versionId) {
        return versionDir(versionId).resolve(versionId + ".json");
    }

    /** 客户端主 jar（versions/&lt;id&gt;/&lt;id&gt;.jar） */
    public Path clientJar(String versionId) {
        return versionDir(versionId).resolve(versionId + ".jar");
    }

    /** natives 解压目录（versions/&lt;id&gt;/natives） */
    public Path nativesDir(String versionId) {
        return versionDir(versionId).resolve("natives");
    }

    public Path assetIndexFile(String indexId) {
        return indexesDir().resolve(indexId + ".json");
    }

    /** objects 下的散列文件：assets/objects/&lt;前两位&gt;/&lt;hash&gt; */
    public Path assetObject(String hash) {
        return objectsDir().resolve(hash.substring(0, 2)).resolve(hash);
    }

    /**
     * 默认游戏目录（启动器所在目录下的 MCLQE，程序目录不可写时回退 %USERPROFILE%\MCLQE）。
     *
     * @see com.mclqe.launcher.config.LauncherPaths#defaultGameDir()
     */
    public static Path defaultGameDir() {
        return com.mclqe.launcher.config.LauncherPaths.defaultGameDir();
    }
}
