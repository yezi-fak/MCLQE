package com.mclqe.launcher.game;

import com.google.gson.Gson;
import com.mclqe.launcher.model.VersionMeta;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 游戏目录管理：本地已安装版本扫描、目录内容列举、安全删除（回收站优先）与标准目录结构创建。
 *
 * <p>所有删除都通过 {@link Desktop#moveToTrash} 移入系统回收站，不做永久删除；
 * 系统不支持回收站时直接拒绝执行并返回原因。</p>
 */
public final class GameDirManager {

    /** 标准 .minecraft 目录结构 */
    public static final List<String> STANDARD_DIRS =
            List.of("versions", "mods", "saves", "resourcepacks", "shaderpacks", "logs");

    /** Windows 保留设备名，不能用作目录名（借鉴 HMCL 对实例 id 做保留名校验的思路）。 */
    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final Gson GSON = new Gson();

    private GameDirManager() {
    }

    /** 删除结果（成功标记 + 面向用户的原因说明）。 */
    public record DeleteResult(boolean success, String message) {
    }

    // ------------------------------------------------------------------ 本地版本扫描

    /**
     * 扫描 {@code <gameDir>/versions} 下已安装的版本目录，解析各自的版本 JSON
     * （含 inheritsFrom 继承信息）。网络不可用时也能给出完整的本地版本列表。
     */
    public static List<LocalVersion> scanLocalVersions(Path gameDir) {
        List<LocalVersion> result = new ArrayList<>();
        if (gameDir == null) {
            return result;
        }
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(versionsDir)) {
            List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path dir : dirs) {
                result.add(readLocalVersion(dir));
            }
        } catch (IOException ignored) {
            // 目录不可读时返回已扫描到的结果
        }
        return result;
    }

    private static LocalVersion readLocalVersion(Path dir) {
        String id = dir.getFileName().toString();
        Path json = dir.resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            json = findSingleJson(dir);
        }

        boolean jsonValid = false;
        String type = null;
        String inheritsFrom = null;
        String mainClass = null;
        String error = null;

        if (json == null) {
            json = dir.resolve(id + ".json");
            error = "缺少版本 JSON（" + id + ".json）";
        } else {
            try {
                VersionMeta meta = GSON.fromJson(Files.readString(json), VersionMeta.class);
                if (meta == null) {
                    error = "版本 JSON 解析失败: " + json.getFileName();
                } else {
                    jsonValid = true;
                    type = meta.getType();
                    inheritsFrom = meta.getInheritsFrom();
                    mainClass = meta.getMainClass();
                }
            } catch (Exception e) {
                error = "版本 JSON 解析失败: " + e.getMessage();
            }
        }

        boolean hasJar = Files.isRegularFile(dir.resolve(id + ".jar"));
        return new LocalVersion(id, dir, json, hasJar, jsonValid, type, inheritsFrom, mainClass, error,
                folderSize(dir));
    }

    /** 目录内仅有唯一 JSON 时作为兜底（部分第三方版本 JSON 名与目录名不一致）。 */
    private static Path findSingleJson(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> jsons = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                    .limit(2)
                    .toList();
            return jsons.size() == 1 ? jsons.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 目录内容列举

    /**
     * 列出游戏目录内的一级子文件夹；{@code versions} 下的版本文件夹会被展开为独立条目，
     * 便于直接在同一个列表中删除。
     */
    public static List<GameDirEntry> listEntries(Path gameDir) {
        List<GameDirEntry> entries = new ArrayList<>();
        if (gameDir == null || !Files.isDirectory(gameDir)) {
            return entries;
        }
        try (Stream<Path> stream = Files.list(gameDir)) {
            List<Path> children = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path child : children) {
                String name = child.getFileName().toString();
                entries.add(entryFor(name, child, STANDARD_DIRS.contains(name), false, null));
                if ("versions".equals(name)) {
                    try (Stream<Path> versions = Files.list(child)) {
                        List<Path> versionDirs = versions
                                .filter(Files::isDirectory)
                                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                                .toList();
                        for (Path versionDir : versionDirs) {
                            String versionId = versionDir.getFileName().toString();
                            entries.add(entryFor("versions/" + versionId, versionDir, false, true, versionId));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 返回已收集的条目
        }
        return entries;
    }

    private static GameDirEntry entryFor(String name, Path path, boolean standard, boolean versionDir,
                                         String versionId) {
        long[] stat = treeStat(path);
        long modified = 0;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            // 保留 0，界面显示未知
        }
        return new GameDirEntry(name, path, standard, versionDir, versionId, stat[0], stat[1], modified);
    }

    /** 递归统计目录内的文件数与总字节数。 */
    private static long[] treeStat(Path dir) {
        long[] stat = {0L, 0L};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) {
                        stat[0]++;
                        stat[1] += Files.size(p);
                    }
                } catch (IOException ignored) {
                    // 单个文件不可访问时跳过
                }
            });
        } catch (IOException ignored) {
            // 目录不可遍历时返回已统计值
        }
        return stat;
    }

    /** 目录总大小（字节）。 */
    public static long folderSize(Path dir) {
        return treeStat(dir)[1];
    }

    // ------------------------------------------------------------------ 删除（回收站）

    /**
     * 把目标路径移入系统回收站。返回结果中包含面向用户的原因说明，
     * 覆盖「路径不存在 / 被占用 / 权限不足 / 系统不支持回收站」等情况。
     */
    public static DeleteResult deleteToTrash(Path target) {
        if (target == null) {
            return new DeleteResult(false, "路径为空，无法删除");
        }
        if (!Files.exists(target)) {
            return new DeleteResult(false, "路径不存在：" + target);
        }
        if (target.getParent() == null) {
            return new DeleteResult(false, "不允许删除磁盘根目录：" + target);
        }
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                return new DeleteResult(false, "当前系统不支持回收站，已取消删除：" + target);
            }
            boolean moved = Desktop.getDesktop().moveToTrash(target.toFile());
            if (moved) {
                return new DeleteResult(true, "已移入回收站：" + target);
            }
            return new DeleteResult(false, "移入回收站失败，路径可能正被占用或权限不足：" + target);
        } catch (RuntimeException e) {
            return new DeleteResult(false, "移入回收站失败：" + e.getMessage() + "（" + target + "）");
        }
    }

    /**
     * 删除已安装版本：移入回收站的不只是版本文件夹，还包括 versions 根下
     * 同名的 {@code <id>.json} / {@code <id>.jar} 等平铺文件。
     */
    public static DeleteResult deleteVersion(Path gameDir, String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return new DeleteResult(false, "未指定要删除的版本");
        }
        if (versionId.contains("/") || versionId.contains("\\") || versionId.contains("..")) {
            return new DeleteResult(false, "版本 ID 非法：" + versionId);
        }
        Path versionsDir = gameDir.resolve("versions");
        List<Path> targets = new ArrayList<>();
        Path versionDir = versionsDir.resolve(versionId);
        if (Files.exists(versionDir)) {
            targets.add(versionDir);
        }
        for (String suffix : List.of(".json", ".jar", ".txt")) {
            Path flat = versionsDir.resolve(versionId + suffix);
            if (Files.exists(flat)) {
                targets.add(flat);
            }
        }
        if (targets.isEmpty()) {
            return new DeleteResult(false, "本地未找到该版本的文件：" + versionId);
        }

        int moved = 0;
        List<String> failures = new ArrayList<>();
        for (Path target : targets) {
            DeleteResult result = deleteToTrash(target);
            if (result.success()) {
                moved++;
            } else {
                failures.add(result.message());
            }
        }
        if (failures.isEmpty()) {
            return new DeleteResult(true, "已删除版本 " + versionId + "，共 " + moved + " 项移入回收站");
        }
        if (moved == 0) {
            return new DeleteResult(false, "删除版本 " + versionId + " 失败：" + String.join("；", failures));
        }
        return new DeleteResult(false, "版本 " + versionId + " 部分删除成功（" + moved + " 项已移入回收站）："
                + String.join("；", failures));
    }

    // ------------------------------------------------------------------ 新建游戏目录

    /**
     * 创建标准 .minecraft 目录结构。
     *
     * @return 创建（或已存在）的标准目录列表
     */
    public static List<Path> createStandardStructure(Path raw) throws IOException {
        Path root = raw.toAbsolutePath().normalize();
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new IOException("目标路径已被同名文件占用：" + root);
        }
        Files.createDirectories(root);
        List<Path> dirs = new ArrayList<>();
        dirs.add(root);
        for (String name : STANDARD_DIRS) {
            Path dir = root.resolve(name);
            Files.createDirectories(dir);
            dirs.add(dir);
        }
        return dirs;
    }

    /**
     * 校验新建游戏目录的路径是否可用。
     *
     * @return 错误说明；合法时返回 {@code null}
     */
    public static String validateNewGameDir(Path path) {
        if (path == null) {
            return "请填写或选择游戏目录路径";
        }
        if (!path.isAbsolute()) {
            return "请填写绝对路径（例如 D:\\Minecraft）";
        }
        final Path target;
        try {
            // 统一规范化后再做所有判断：消除 . / .. 与冗余分隔符，
            // 避免 C:\Program Files\..\Games 这类合法路径被误拒、C:\Users\..\Windows\x 被放行
            target = path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return "路径无法解析：" + path;
        }
        Path root = target.getRoot();
        if (root != null && target.getNameCount() == 0) {
            return "不允许直接使用磁盘根目录：" + target;
        }
        // 逐段校验：非法字符、Windows 保留设备名、尾随点/空格，不能只查最后一段
        for (Path part : target) {
            String name = part.toString();
            if (name.contains("?") || name.contains("*") || name.contains(":")
                    || name.contains("<") || name.contains(">") || name.contains("|") || name.contains("\"")) {
                return "路径包含非法字符：" + name;
            }
            String segmentProblem = reservedSegmentProblem(name);
            if (segmentProblem != null) {
                return segmentProblem + "（问题出现在路径段：" + name + "）";
            }
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            return "目标路径已被同名文件占用：" + target;
        }
        // 从目标向上找最近的已存在项：必须是目录且可写；命中同名文件说明路径被占用
        for (Path current = target; current != null; current = current.getParent()) {
            if (!Files.exists(current)) {
                continue;
            }
            if (!Files.isDirectory(current)) {
                return "路径中的 " + current + " 已被同名文件占用，无法在其下创建目录";
            }
            if (!Files.isWritable(current)) {
                return "目录不可写（权限不足）：" + current;
            }
            break;
        }
        if (isUnderSystemCritical(target)) {
            return "不允许把游戏目录放在系统关键位置：" + target;
        }
        return null;
    }

    /** 系统关键位置（Windows / Program Files / ProgramData / 默认用户目录）禁止用作游戏目录。 */
    private static boolean isUnderSystemCritical(Path path) {
        for (Path base : systemCriticalBases()) {
            if (path.startsWith(base)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> systemCriticalBases() {
        List<Path> bases = new ArrayList<>();
        for (String key : new String[] {"SystemRoot", "windir", "ProgramFiles", "ProgramFiles(x86)", "ProgramData"}) {
            addBase(bases, System.getenv(key));
        }
        String systemDrive = System.getenv("SystemDrive");
        if (systemDrive != null && !systemDrive.isBlank()) {
            addBase(bases, systemDrive + "\\Users\\Default");
        }
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            addBase(bases, userProfile + "\\Default");
        }
        return bases;
    }

    /** 环境变量目录转成规范化 Path；用分量级比较，避免字符串前缀被 .. 绕过。 */
    private static void addBase(List<Path> bases, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            bases.add(Path.of(value).toAbsolutePath().normalize());
        } catch (Exception ignored) {
            // 环境变量异常时跳过该项，不影响其它判断
        }
    }

    /** 单个路径段的合法性：非空、非 . / ..、非 Windows 保留设备名、不以点或空格结尾。 */
    private static String reservedSegmentProblem(String segment) {
        String text = segment.trim();
        if (text.isEmpty() || text.equals(".") || text.equals("..")) {
            return "目录名不能为空，也不能使用 . 或 ..";
        }
        if (!text.equals(segment) || segment.endsWith(".")) {
            return "目录名不能以空格或点结尾（Windows 会忽略这些字符）：" + segment;
        }
        int dot = text.indexOf('.');
        String base = dot >= 0 ? text.substring(0, dot) : text;
        if (RESERVED_DEVICE_NAMES.contains(base.trim().toUpperCase(Locale.ROOT))) {
            return "目录名 " + segment + " 是 Windows 保留设备名，请更换（例如 D:\\Minecraft）";
        }
        return null;
    }
}
