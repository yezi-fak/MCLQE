package com.mclqe.launcher.mod;

import com.mclqe.launcher.net.HttpUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 模组安装器：把 Modrinth 版本文件落到实例 mods 目录，并解析一层必需前置。
 *
 * <p>安全约定：</p>
 * <ul>
 *   <li>下载先写 {@code <文件名>.part}，SHA1 / 大小校验通过后再改名落盘；失败删除残文件</li>
 *   <li>同名且 SHA1 相同 → 跳过，不产生网络请求</li>
 *   <li>同名但内容不同 → 自动改名 {@code 名称 (1).jar}，绝不覆盖已有文件</li>
 *   <li>只安装 {@code required} 依赖，且只向下解析一层，避免依赖环</li>
 * </ul>
 */
public class ModInstaller {

    /** 单次安装结果。 */
    public record Result(int installed, int skipped, int failed, List<String> files) {

        public String summary() {
            StringBuilder sb = new StringBuilder("安装完成：成功 ").append(installed)
                    .append(" 个 / 跳过 ").append(skipped).append(" 个");
            if (failed > 0) {
                sb.append(" / 失败 ").append(failed).append(" 个");
            }
            return sb.toString();
        }
    }

    /** 进度回调：已处理数 / 总数 / 当前文件名。 */
    public interface Progress {
        void onProgress(int done, int total, String current);
    }

    /** 单个文件的落盘结果。 */
    private record Outcome(Path path, boolean downloaded) {
    }

    private final ModrinthClient client;
    private final Consumer<String> logger;

    public ModInstaller(ModrinthClient client, Consumer<String> logger) {
        this.client = client;
        this.logger = logger == null ? msg -> { } : logger;
    }

    /**
     * 安装指定版本及其必需前置。
     *
     * @param modsDir     目标 mods 目录
     * @param version     用户在界面上选中的版本
     * @param gameVersion 目标实例 MC 版本（可为空，用于筛选前置）
     * @param loader      目标实例加载器（可为空，用于筛选前置）
     */
    public Result install(Path modsDir, ModVersion version, String gameVersion, String loader, Progress progress)
            throws IOException {
        if (modsDir == null) {
            throw new IOException("目标 mods 目录不可用");
        }
        if (version == null) {
            throw new IOException("未选择要安装的版本");
        }
        Files.createDirectories(modsDir);

        List<ModVersion> plan = new ArrayList<>();
        Set<String> plannedProjects = new LinkedHashSet<>();
        plan.add(version);
        plannedProjects.add(normalize(version.projectId()));

        for (ModDependency dependency : version.requiredDependencies()) {
            String projectId = normalize(dependency.projectId());
            if (projectId.isEmpty() || plannedProjects.contains(projectId)) {
                continue;
            }
            ModVersion resolved = resolveDependency(projectId, gameVersion, loader);
            if (resolved == null) {
                logger.accept("未能找到前置 " + projectId + " 的匹配版本，已跳过");
                continue;
            }
            plan.add(resolved);
            plannedProjects.add(projectId);
        }

        int installed = 0;
        int skipped = 0;
        int failed = 0;
        List<String> files = new ArrayList<>();
        int total = plan.size();
        int done = 0;

        for (ModVersion item : plan) {
            ModFile file = item.primaryFile();
            if (file == null) {
                logger.accept("版本 " + item.displayName() + " 没有可下载文件，已跳过");
                failed++;
                done++;
                continue;
            }
            if (progress != null) {
                progress.onProgress(done, total, file.filename());
            }
            try {
                Outcome outcome = installOne(modsDir, item, file);
                files.add(outcome.path().toString());
                if (outcome.downloaded()) {
                    installed++;
                } else {
                    skipped++;
                }
            } catch (IOException e) {
                failed++;
                logger.accept("安装失败：" + file.filename() + " -> " + e.getMessage());
            }
            done++;
            if (progress != null) {
                progress.onProgress(done, total, file.filename());
            }
        }
        return new Result(installed, skipped, failed, files);
    }

    /** 安装单个版本文件；已存在且校验一致时直接返回（不下载）。 */
    private Outcome installOne(Path modsDir, ModVersion version, ModFile file) throws IOException {
        String filename = sanitize(file.filename(), version);
        Path target = modsDir.resolve(filename);

        if (Files.exists(target)) {
            String existingSha1 = file.sha1();
            if (existingSha1 != null && !existingSha1.isBlank()
                    && existingSha1.equalsIgnoreCase(HttpUtils.sha1(target))) {
                logger.accept("已存在且校验一致，跳过：" + filename);
                return new Outcome(target, false);
            }
            Path renamed = uniqueTarget(target);
            logger.accept("同名文件已存在且内容不同，改名安装：" + renamed.getFileName() + "（原文件保留）");
            target = renamed;
        }

        Path part = target.resolveSibling(target.getFileName().toString() + ".part");
        try {
            logger.accept("开始下载：" + file.filename() + "（" + sizeText(file.size()) + "）");
            HttpUtils.download(file.url(), part, file.sha1(), file.size());
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            logger.accept("已安装：" + target.getFileName() + "（SHA1 校验通过）");
            return new Outcome(target, true);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // 残文件清理失败不影响主流程
            }
            throw new IOException("下载校验失败：" + file.filename() + "，" + e.getMessage(), e);
        }
    }

    /** 取该前置在目标 mc / 加载器下的最新版本（优先正式版）。 */
    private ModVersion resolveDependency(String projectId, String gameVersion, String loader) {
        try {
            List<ModVersion> list = client.versions(projectId, gameVersion, loader);
            if (list.isEmpty()) {
                return null;
            }
            for (ModVersion v : list) {
                if (v.release() && v.primaryFile() != null) {
                    return v;
                }
            }
            for (ModVersion v : list) {
                if (v.primaryFile() != null) {
                    return v;
                }
            }
            return null;
        } catch (IOException e) {
            logger.accept("查询前置 " + projectId + " 版本失败：" + e.getMessage());
            return null;
        }
    }

    /** 去掉路径分隔符等非法字符，避免越出 mods 目录。 */
    static String sanitize(String filename, ModVersion version) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty()) {
            name = (version.versionNumber() == null ? version.id() : version.versionNumber()) + ".jar";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name + ".jar";
        }
        return name;
    }

    /** {@code sodium.jar} → {@code sodium (1).jar} 形式的重名避让。 */
    private static Path uniqueTarget(Path target) {
        String name = target.getFileName().toString();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        Path parent = target.getParent();
        for (int i = 1; i < 100; i++) {
            Path candidate = parent.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return parent.resolve(base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    private static String sizeText(long bytes) {
        if (bytes <= 0) {
            return "大小未知";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
