package com.mclqe.launcher.config;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 启动器自身位置与默认游戏目录的解析。
 *
 * <p>默认游戏目录规则：<b>固定在启动器程序所在盘符的 {@code MCLQE} 目录下</b>
 * （即 {@code <盘符>:\MCLQE\MCLQE}）。无论 exe 摆在盘符下的哪一层目录，默认值都不变，
 * 便于多份打包产物共用同一份游戏数据。</p>
 *
 * <p>若该位置不可写，回退到 {@code %USERPROFILE%\MCLQE}，不再回退到 {@code %APPDATA%\.minecraft}。</p>
 */
public final class LauncherPaths {

    /** 默认基准目录的文件夹名（位于启动器程序所在盘符根下，如 {@code D:\MCLQE}）。 */
    public static final String BASE_DIR_NAME = "MCLQE";

    /** 默认游戏目录的文件夹名（位于基准目录下，如 {@code D:\MCLQE\MCLQE}）。 */
    public static final String GAME_DIR_NAME = "MCLQE";

    /** 基准目录不可写时的回退文件夹名（位于用户主目录下）。 */
    public static final String FALLBACK_GAME_DIR_NAME = "MCLQE";

    private LauncherPaths() {
    }

    /**
     * 启动器程序所在目录。
     *
     * <p>识别规则：</p>
     * <ul>
     *   <li>jpackage 打包态：{@code <exe 目录>\app\xxx.jar} → 取 {@code app} 的上一级（exe 所在目录）</li>
     *   <li>开发态 jar：{@code <工程根>\target\xxx.jar} → 取 {@code target} 的上一级（工程根）</li>
     *   <li>开发态 classes：{@code <工程根>\target\classes} → 取上两级（工程根）</li>
     *   <li>其他情况：代码源所在目录；无法定位时回退到 {@code user.dir}</li>
     * </ul>
     */
    public static Path installDir() {
        Path fromCodeSource = installDirFromCodeSource();
        if (fromCodeSource != null) {
            return fromCodeSource;
        }
        String userDir = System.getProperty("user.dir", "");
        if (!userDir.isBlank()) {
            try {
                return Path.of(userDir).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
                // 继续走最后的兜底
            }
        }
        return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    private static Path installDirFromCodeSource() {
        try {
            var source = LauncherPaths.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            Path parent = location.getParent();
            if (Files.isRegularFile(location)) {
                if (parent == null) {
                    return null;
                }
                String parentName = lowerName(parent);
                // jpackage 布局：<exe 目录>\app\<jar>
                if ("app".equals(parentName) && parent.getParent() != null) {
                    return parent.getParent();
                }
                // 开发态：target\<jar> 归位到工程根，避免默认目录落在 target 里
                if ("target".equals(parentName) && parent.getParent() != null) {
                    return parent.getParent();
                }
                return parent;
            }
            if (Files.isDirectory(location)) {
                // 开发态：<工程根>\target\classes
                if ("classes".equals(lowerName(location)) && parent != null && parent.getParent() != null) {
                    return parent.getParent();
                }
                return location;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // 定位失败时由调用方回退 user.dir
        }
        return null;
    }

    private static String lowerName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 首次启动时使用的默认游戏目录：{@code <盘符>:\MCLQE\MCLQE}。
     * 该位置不可写时回退到 {@code <用户主目录>\MCLQE}。
     */
    public static Path defaultGameDir() {
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        return resolveDefaultGameDir(installDir(), home);
    }

    /**
     * 默认游戏目录的解析核心（供自验用）。
     *
     * @param installDir 启动器程序所在目录
     * @param userHome   用户主目录（回退位置）
     * @return 默认游戏目录
     */
    static Path resolveDefaultGameDir(Path installDir, Path userHome) {
        Path fallback = userHome.resolve(FALLBACK_GAME_DIR_NAME).toAbsolutePath().normalize();
        Path base = fixedBaseDir(installDir);
        if (base == null) {
            return fallback;
        }
        Path candidate = base.resolve(GAME_DIR_NAME);
        // 基准目录已存在则要求它可写；尚未创建则退一步检查盘符根可写
        Path anchor = Files.isDirectory(base) ? base : base.getRoot();
        return isUsable(anchor, candidate) ? candidate : fallback;
    }

    /**
     * 默认游戏目录的固定基准：启动器程序所在盘符根下的 {@code MCLQE} 文件夹。
     *
     * <p>例如 exe 位于 {@code D:\MCLQE\dist\MCLQE\} 时，基准为 {@code D:\MCLQE}，
     * 默认游戏目录即 {@code D:\MCLQE\MCLQE}；exe 挪到 {@code D:\}任意目录 也不变。</p>
     *
     * @param installDir 启动器程序所在目录
     * @return 基准目录；无法定位时返回 {@code null}
     */
    static Path fixedBaseDir(Path installDir) {
        if (installDir == null) {
            return null;
        }
        Path abs = installDir.toAbsolutePath().normalize();
        Path root = abs.getRoot();
        if (root == null) {
            return abs;
        }
        return root.resolve(BASE_DIR_NAME).toAbsolutePath().normalize();
    }

    /** 候选目录是否可用：已存在时必须是可写目录；不存在时要求程序目录可写。 */
    private static boolean isUsable(Path base, Path candidate) {
        if (base == null) {
            return false;
        }
        try {
            if (Files.exists(candidate)) {
                return Files.isDirectory(candidate) && Files.isWritable(candidate);
            }
            return Files.isDirectory(base) && Files.isWritable(base);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 默认游戏目录当前是否落在用户主目录回退位置。 */
    public static boolean usingFallbackGameDir() {
        Path dir = defaultGameDir();
        Path base = fixedBaseDir(installDir());
        return base == null || !dir.equals(base.resolve(GAME_DIR_NAME));
    }

    /** 设置页 / 目录管理页展示的默认路径说明文案。 */
    public static String defaultGameDirHint() {
        Path dir = defaultGameDir();
        if (usingFallbackGameDir()) {
            return "默认：" + dir + "（盘符下的 MCLQE 目录不可写，已回退到用户目录）";
        }
        return "默认：" + dir + "（固定在启动器所在盘符的 MCLQE 目录下）";
    }
}
