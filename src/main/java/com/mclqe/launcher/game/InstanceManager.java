package com.mclqe.launcher.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 实例管理：扫描游戏目录下的实例、读写实例元数据、版本隔离开关、内容目录统计。
 *
 * <p>元数据文件固定为 {@code <versionDir>/mclqe-instance.json}，结构
 * {@code {name, isolated, createdAt}}；文件缺失视为「未自定义」，使用默认值。</p>
 */
public final class InstanceManager {

    /** 实例元数据文件名 */
    public static final String META_FILE = "mclqe-instance.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private InstanceManager() {
    }

    /** 实例内容分类：实例内容管理页的入口。 */
    public enum ContentKind {
        MODS("mods", "模组", "jar"),
        RESOURCEPACKS("resourcepacks", "资源包", "pack"),
        SHADERPACKS("shaderpacks", "光影包", "pack"),
        SAVES("saves", "存档", "dir"),
        SCREENSHOTS("screenshots", "截图", "image"),
        LOGS("logs", "日志", "log");

        private final String folder;
        private final String label;
        private final String counter;

        ContentKind(String folder, String label, String counter) {
            this.folder = folder;
            this.label = label;
            this.counter = counter;
        }

        /** 内容目录名 */
        public String folder() {
            return folder;
        }

        /** 中文标签 */
        public String label() {
            return label;
        }

        /** 统计方式：jar / pack / dir / image / log */
        public String counter() {
            return counter;
        }
    }

    /** 元数据磁盘结构 */
    public static final class Meta {
        private String name = "";
        private boolean isolated = true;
        private long createdAt = 0L;

        public String getName() {
            return name == null ? "" : name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name;
        }

        public boolean isIsolated() {
            return isolated;
        }

        public void setIsolated(boolean isolated) {
            this.isolated = isolated;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }

    // ------------------------------------------------------------------ 扫描

    /** 扫描游戏目录下的全部实例（按版本 id 排序）。 */
    public static List<Instance> list(Path gameDir, boolean defaultIsolation) {
        List<Instance> result = new ArrayList<>();
        if (gameDir == null) {
            return result;
        }
        for (LocalVersion local : GameDirManager.scanLocalVersions(gameDir)) {
            Meta meta = readMeta(local.dir());
            boolean isolated = meta == null ? defaultIsolation : meta.isIsolated();
            String name = meta == null ? "" : meta.getName();
            result.add(new Instance(gameDir.toString(), local.id(), name, local.resolvedType(),
                    local.inheritsFrom(), isolated, local.damaged(), local.missingJar(),
                    local.sizeBytes(), local.dir()));
        }
        return result;
    }

    /** 读取单个实例；版本目录不存在时返回 null。 */
    public static Instance get(Path gameDir, String versionId, boolean defaultIsolation) {
        if (gameDir == null || versionId == null || versionId.isBlank()) {
            return null;
        }
        for (Instance instance : list(gameDir, defaultIsolation)) {
            if (instance.id().equals(versionId)) {
                return instance;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 元数据读写

    /** 读取实例元数据；文件缺失或损坏时返回 null。 */
    public static Meta readMeta(Path versionDir) {
        if (versionDir == null) {
            return null;
        }
        Path file = versionDir.resolve(META_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Meta meta = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Meta.class);
            return meta == null ? null : meta;
        } catch (Exception e) {
            return null;
        }
    }

    /** 原子写入实例元数据。 */
    public static void writeMeta(Path versionDir, Meta meta) throws IOException {
        if (versionDir == null || meta == null) {
            return;
        }
        Files.createDirectories(versionDir);
        Path file = versionDir.resolve(META_FILE);
        Path tmp = versionDir.resolve(META_FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(meta), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 更新实例显示名（不改版本 id）。 */
    public static void rename(Path versionDir, String newName) throws IOException {
        Meta meta = readMeta(versionDir);
        if (meta == null) {
            meta = new Meta();
            meta.setCreatedAt(System.currentTimeMillis());
        }
        meta.setName(newName == null ? "" : newName.trim());
        writeMeta(versionDir, meta);
    }

    /** 设置版本隔离开关。 */
    public static void setIsolated(Path versionDir, boolean isolated, boolean defaultIsolation)
            throws IOException {
        Meta meta = readMeta(versionDir);
        if (meta == null) {
            meta = new Meta();
            meta.setIsolated(defaultIsolation);
            meta.setCreatedAt(System.currentTimeMillis());
        }
        meta.setIsolated(isolated);
        writeMeta(versionDir, meta);
    }

    /**
     * 实例实际生效的内容根目录：开启隔离时是 {@code versions/<id>}，否则是游戏根目录。
     *
     * <p>供启动器组装 JVM 参数 / 游戏参数前定位 mods、saves 等内容目录。</p>
     */
    public static Path contentRoot(Path gameDir, String versionId, boolean defaultIsolation) {
        Path root = gameDir == null ? Path.of(".") : gameDir;
        if (versionId == null || versionId.isBlank()) {
            return root;
        }
        Path versionDir = root.resolve("versions").resolve(versionId);
        Meta meta = readMeta(versionDir);
        boolean isolated = meta == null ? defaultIsolation : meta.isIsolated();
        return isolated ? versionDir : root;
    }

    // ------------------------------------------------------------------ 内容目录

    /** 确保实例的内容子目录存在（mods / resourcepacks / shaderpacks / saves / screenshots / logs）。 */
    public static List<Path> ensureContentDirs(Path contentRoot) throws IOException {
        List<Path> created = new ArrayList<>();
        if (contentRoot == null) {
            return created;
        }
        Files.createDirectories(contentRoot);
        created.add(contentRoot);
        for (ContentKind kind : ContentKind.values()) {
            Path dir = contentRoot.resolve(kind.folder());
            Files.createDirectories(dir);
            created.add(dir);
        }
        return created;
    }

    /** 统计内容目录内的条目数量（按分类使用不同规则）。 */
    public static int count(Path dir, ContentKind kind) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean dirEntry = Files.isDirectory(path);
                return switch (kind.counter()) {
                    case "jar" -> name.endsWith(".jar") || name.endsWith(".jar.disabled");
                    case "pack" -> dirEntry || name.endsWith(".zip");
                    case "image" -> name.endsWith(".png") || name.endsWith(".jpg")
                            || name.endsWith(".jpeg");
                    case "log" -> name.endsWith(".log") || name.endsWith(".txt")
                            || name.endsWith(".gz");
                    default -> true;
                };
            }).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 内容摘要文案，例如「12 个模组」。 */
    public static String describeCount(Path dir, ContentKind kind) {
        int count = count(dir, kind);
        String unit = switch (kind) {
            case MODS -> "个模组";
            case RESOURCEPACKS -> "个资源包";
            case SHADERPACKS -> "个光影包";
            case SAVES -> "个存档";
            case SCREENSHOTS -> "张截图";
            case LOGS -> "个日志文件";
        };
        return count + " " + unit;
    }
}
