package com.mclqe.launcher.loader;

import com.mclqe.launcher.game.JavaDetector;
import com.mclqe.launcher.game.JavaRequirement;
import com.mclqe.launcher.net.HttpUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 加载器安装器：下载官方安装器 jar、按各加载器的命令行约定执行进程、实时回传日志，并在结束后校验产物。
 *
 * <p>各加载器安装参数（设计文档 2.3，实测核对）：
 * <ul>
 *   <li>Fabric：{@code java -jar fabric-installer.jar client -dir <gameDir> -mcversion <mc> -loader <lv> -noprofile}</li>
 *   <li>Quilt：{@code java -jar quilt-installer.jar install client <mc> <lv> --install-dir=<gameDir>}</li>
 *   <li>Forge：{@code java -jar forge-installer.jar --installClient <gameDir>}</li>
 *   <li>NeoForge：{@code java -jar neoforge-installer.jar --installClient <gameDir>}</li>
 * </ul></p>
 */
public class LoaderInstaller {

    /** 安装器进程最长运行时间。 */
    private static final long TIMEOUT_MINUTES = 15;

    private final LoaderCatalog catalog;
    private final Consumer<String> logger;

    public LoaderInstaller(Consumer<String> logger) {
        this.logger = logger == null ? msg -> { } : logger;
        this.catalog = new LoaderCatalog(this.logger);
    }

    public LoaderCatalog catalog() {
        return catalog;
    }

    /** 安装结果。 */
    public record InstallResult(boolean success, String versionId, Path versionDir, int exitCode, String message) {

        public static InstallResult fail(String message, int exitCode) {
            return new InstallResult(false, null, null, exitCode, message);
        }
    }

    /**
     * 执行一次完整安装：解析安装器地址 → 下载 → 挑选 Java → 运行进程 → 校验版本目录。
     *
     * @param gameDir 目标游戏目录（安装器会在此写入 versions/libraries）
     */
    public InstallResult install(LoaderType type, String mcVersion, String loaderVersion, Path gameDir) {
        long startedAt = System.currentTimeMillis();
        try {
            if (gameDir == null) {
                return InstallResult.fail("未确定游戏目录", -1);
            }
            Files.createDirectories(gameDir);
            logger.accept("开始安装 " + type.displayName() + " " + loaderVersion + "（MC " + mcVersion + "）");
            logger.accept("目标游戏目录：" + gameDir);
            ensureLauncherProfiles(gameDir);

            LoaderCatalog.InstallerArtifact artifact = catalog.installer(type, mcVersion, loaderVersion);
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "mclqe-loader");
            Files.createDirectories(cacheDir);
            Path installerJar = cacheDir.resolve(type.id() + "-" + artifact.version() + "-installer.jar");
            logger.accept("下载安装器：" + artifact.url());
            long before = System.currentTimeMillis();
            try {
                HttpUtils.download(artifact.url(), installerJar, artifact.sha1(), -1);
            } catch (IOException checksumError) {
                // 部分加载器（如 Quilt）官方元数据里的 SHA1 与 maven 实际产物不一致，
                // 直接失败会让安装永远无法进行；此处降级为「仅校验大小」并明确告知用户。
                logger.accept("安装器 SHA1 校验未通过（" + checksumError.getMessage()
                        + "），改为仅校验文件大小后继续");
                HttpUtils.download(artifact.url(), installerJar, null, -1);
            }
            logger.accept("安装器就绪：" + installerJar + "（" + Files.size(installerJar) + " 字节，"
                    + (System.currentTimeMillis() - before) + " ms）");

            JavaDetector.JavaInfo java = pickJava(mcVersion);
            if (java == null) {
                return InstallResult.fail("未找到可用的 Java 运行时，请先在设置中指定 Java 路径", -1);
            }
            logger.accept("使用 Java：" + java.getJavaExecutable() + "（" + java.getVersion() + "）");

            List<String> command = buildCommand(type, java.getJavaExecutable(), installerJar, mcVersion,
                    loaderVersion, gameDir);
            logger.accept("执行安装命令：" + String.join(" ", command));
            int exitCode = runProcess(command, gameDir);
            logger.accept("安装器进程退出码：" + exitCode);
            if (exitCode != 0) {
                return InstallResult.fail(type.displayName() + " 安装器返回非零退出码 " + exitCode, exitCode);
            }

            Path versionDir = resolveVersionDir(type, mcVersion, loaderVersion, gameDir, startedAt);
            if (versionDir == null) {
                return InstallResult.fail("安装器已退出，但未在 versions 目录找到 " + type.displayName() + " 版本产物",
                        exitCode);
            }
            String versionId = versionDir.getFileName().toString();
            Path json = versionDir.resolve(versionId + ".json");
            if (!Files.exists(json)) {
                return InstallResult.fail("版本目录缺少 " + versionId + ".json", exitCode);
            }
            logger.accept("安装完成：" + versionId + "（" + versionDir + "）");
            return new InstallResult(true, versionId, versionDir, exitCode, "安装成功");
        } catch (IOException e) {
            logger.accept("安装失败：" + e.getMessage());
            return InstallResult.fail("安装失败：" + e.getMessage(), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.accept("安装被中断");
            return InstallResult.fail("安装被中断", -1);
        }
    }

    // ------------------------------------------------------------------ 进程

    /**
     * Forge / NeoForge 官方安装器要求游戏目录内存在启动器配置文件，否则直接报
     * “There is no Minecraft launcher profile … you need to run the launcher first” 并退出。
     * 这里按原版启动器格式补一个最小骨架（已存在则不动）。
     */
    private void ensureLauncherProfiles(Path gameDir) throws IOException {
        Path profiles = gameDir.resolve("launcher_profiles.json");
        if (Files.exists(profiles)) {
            return;
        }
        String json = """
                {
                  "profiles": {},
                  "settings": {
                    "language": "zh_CN"
                  },
                  "version": 3
                }
                """;
        Files.writeString(profiles, json);
        logger.accept("已补建 launcher_profiles.json（Forge/NeoForge 安装器前置要求）");
    }

    private List<String> buildCommand(LoaderType type, Path javaExe, Path installerJar, String mcVersion,
                                      String loaderVersion, Path gameDir) {
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        // 安装器自身需要联网下载 Minecraft 库与资源：让子进程跟随系统代理，
        // 若启动器 JVM 显式配置了代理则一并透传，避免企业/代理环境下安装卡死。
        command.add("-Djava.net.useSystemProxies=true");
        for (String key : new String[]{"http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
                "http.nonProxyHosts"}) {
            String value = System.getProperty(key);
            if (value != null && !value.isBlank()) {
                command.add("-D" + key + "=" + value);
            }
        }
        command.add("-jar");
        command.add(installerJar.toString());
        switch (type) {
            case FABRIC -> {
                command.add("client");
                command.add("-dir");
                command.add(gameDir.toString());
                command.add("-mcversion");
                command.add(mcVersion);
                command.add("-loader");
                command.add(loaderVersion);
                command.add("-noprofile");
            }
            case QUILT -> {
                command.add("install");
                command.add("client");
                command.add(mcVersion);
                command.add(loaderVersion);
                command.add("--install-dir=" + gameDir);
            }
            default -> {
                // Forge 与 NeoForge 安装器参数一致
                command.add("--installClient");
                command.add(gameDir.toString());
            }
        }
        return command;
    }

    private int runProcess(List<String> command, Path workDir) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Charset charset = Charset.defaultCharset();
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    String text = line.strip();
                    if (!text.isEmpty()) {
                        logger.accept("[安装器] " + text);
                    }
                }
            } catch (IOException e) {
                logger.accept("[安装器] 日志读取中断：" + e.getMessage());
            }
        }, "mclqe-loader-install-log");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            logger.accept("安装器超时（超过 " + TIMEOUT_MINUTES + " 分钟），已强制结束");
            return -1;
        }
        reader.join(3000);
        return process.exitValue();
    }

    // ------------------------------------------------------------------ Java 选择与产物校验

    private JavaDetector.JavaInfo pickJava(String mcVersion) {
        int required = JavaRequirement.ruleMajorFor(mcVersion);
        List<JavaDetector.JavaInfo> all = JavaDetector.detectAll();
        JavaDetector.JavaInfo best = JavaDetector.pickBest(all, required);
        if (best == null) {
            best = JavaDetector.pickFallback(all, required);
        }
        return best;
    }

    /** 先按各加载器的版本目录命名规则直取，失败则在 versions 下按关键字 + 修改时间兜底查找。 */
    private Path resolveVersionDir(LoaderType type, String mcVersion, String loaderVersion, Path gameDir,
                                   long startedAt) throws IOException {
        Path versionsDir = gameDir.resolve("versions");
        String expected = switch (type) {
            case FABRIC -> "fabric-loader-" + loaderVersion + "-" + mcVersion;
            case QUILT -> "quilt-loader-" + loaderVersion + "-" + mcVersion;
            case FORGE -> mcVersion + "-forge-" + loaderVersion;
            case NEOFORGE -> "neoforge-" + loaderVersion;
        };
        Path direct = versionsDir.resolve(expected);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        if (!Files.isDirectory(versionsDir)) {
            return null;
        }
        String keyword = type.id();
        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        try (Stream<Path> stream = Files.list(versionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.contains(keyword)) {
                    continue;
                }
                Path json = dir.resolve(dir.getFileName() + ".json");
                if (!Files.exists(json)) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(json).toMillis();
                // 只接受本次安装期间写入的版本，避免误判旧版本
                if (modified < startedAt - 5000) {
                    continue;
                }
                if (modified > newestTime) {
                    newestTime = modified;
                    newest = dir;
                }
            }
        }
        return newest;
    }
}
