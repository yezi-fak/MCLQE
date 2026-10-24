package com.mclqe.launcher.game;

import com.mclqe.launcher.model.DownloadInfo;
import com.mclqe.launcher.model.Library;
import com.mclqe.launcher.net.DownloadManager;
import com.mclqe.launcher.net.DownloadSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 依赖库处理：rules 平台过滤、下载任务生成、classpath 组装、natives 定位。
 */
public class LibraryManager {

    private final GamePaths paths;
    private final Consumer<String> logger;

    public LibraryManager(GamePaths paths, Consumer<String> logger) {
        this.paths = paths;
        this.logger = logger == null ? m -> {
        } : logger;
    }

    /** 按当前平台 rules 过滤依赖库。 */
    public List<Library> filterLibraries(List<Library> libraries) {
        List<Library> result = new ArrayList<>();
        if (libraries == null) {
            return result;
        }
        for (Library lib : libraries) {
            if (PlatformRules.isAllowed(lib.getRules())) {
                result.add(lib);
            }
        }
        return result;
    }

    /** 生成依赖库下载任务（普通 artifact + 当前平台 natives classifier）。 */
    public List<DownloadManager.Task> buildDownloadTasks(List<Library> libraries) {
        List<DownloadManager.Task> tasks = new ArrayList<>();
        for (Library lib : filterLibraries(libraries)) {
            DownloadInfo artifact = lib.getDownloads() == null ? null : lib.getDownloads().getArtifact();

            if (artifact != null && artifact.getUrl() != null) {
                // 版本 JSON 里登记的是官方地址，生成任务时即按下载源重写（rawUrl 保留官方原址用于兜底）
                tasks.add(new DownloadManager.Task(DownloadSource.rewrite(artifact.getUrl()), artifact.getUrl(),
                        resolveArtifactPath(lib), artifact.getSha1(), artifact.getSize()));
            } else if (lib.getUrl() != null && !lib.getUrl().isBlank()) {
                // 旧式 library：仅有 url + name 坐标
                String base = lib.getUrl().endsWith("/") ? lib.getUrl() : lib.getUrl() + "/";
                String url = base + lib.getDirectoryPath() + "/" + lib.getArtifactFileName();
                Path target = paths.librariesDir()
                        .resolve(lib.getDirectoryPath())
                        .resolve(lib.getArtifactFileName());
                tasks.add(new DownloadManager.Task(DownloadSource.rewrite(url), url, target, null, 0));
            }

            String classifier = PlatformRules.nativesClassifier(lib.getNatives());
            if (classifier != null) {
                DownloadInfo nativeInfo = (lib.getDownloads() == null || lib.getDownloads().getClassifiers() == null)
                        ? null
                        : lib.getDownloads().getClassifiers().get(classifier);
                if (nativeInfo != null && nativeInfo.getUrl() != null) {
                    tasks.add(new DownloadManager.Task(DownloadSource.rewrite(nativeInfo.getUrl()), nativeInfo.getUrl(),
                            resolveClassifierPath(lib, classifier),
                            nativeInfo.getSha1(), nativeInfo.getSize()));
                }
            }
        }
        return tasks;
    }

    /** 组装 classpath（不含 natives classifier jar）。 */
    public List<Path> buildClasspath(List<Library> libraries) {
        List<Path> classpath = new ArrayList<>();
        for (Library lib : filterLibraries(libraries)) {
            DownloadInfo artifact = lib.getDownloads() == null ? null : lib.getDownloads().getArtifact();
            if (artifact == null && (lib.getUrl() == null || lib.getUrl().isBlank())) {
                continue;
            }
            classpath.add(resolveArtifactPath(lib));
        }
        return classpath;
    }

    /** 收集需要解压到 natives 目录的 jar。 */
    public List<NativeEntry> collectNatives(List<Library> libraries) {
        List<NativeEntry> entries = new ArrayList<>();
        for (Library lib : filterLibraries(libraries)) {
            String classifier = PlatformRules.nativesClassifier(lib.getNatives());
            if (classifier == null) {
                continue;
            }
            List<String> exclude = lib.getExtract() == null ? null : lib.getExtract().getExclude();
            entries.add(new NativeEntry(resolveClassifierPath(lib, classifier), exclude));
        }
        return entries;
    }

    // ------------------------------------------------------------------ 路径解析

    public Path resolveArtifactPath(Library lib) {
        DownloadInfo artifact = lib.getDownloads() == null ? null : lib.getDownloads().getArtifact();
        if (artifact != null && artifact.getPath() != null && !artifact.getPath().isBlank()) {
            return paths.librariesDir().resolve(artifact.getPath().replace('\\', '/'));
        }
        return paths.librariesDir()
                .resolve(lib.getDirectoryPath())
                .resolve(lib.getArtifactFileName());
    }

    public Path resolveClassifierPath(Library lib, String classifier) {
        DownloadInfo info = (lib.getDownloads() == null || lib.getDownloads().getClassifiers() == null)
                ? null
                : lib.getDownloads().getClassifiers().get(classifier);
        if (info != null && info.getPath() != null && !info.getPath().isBlank()) {
            return paths.librariesDir().resolve(info.getPath().replace('\\', '/'));
        }
        String fileName = lib.getArtifactId() + "-" + lib.getVersion() + "-" + classifier + ".jar";
        return paths.librariesDir().resolve(lib.getDirectoryPath()).resolve(fileName);
    }

    /** natives jar 与其解压排除规则 */
    public static class NativeEntry {
        public final Path zip;
        public final List<String> exclude;

        public NativeEntry(Path zip, List<String> exclude) {
            this.zip = zip;
            this.exclude = exclude;
        }
    }
}
