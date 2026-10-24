package com.mclqe.launcher.game;

import com.mclqe.launcher.auth.AuthAccount;
import com.mclqe.launcher.auth.OfflineAccount;
import com.mclqe.launcher.config.LauncherConfig;
import com.mclqe.launcher.model.AssetObjects;
import com.mclqe.launcher.model.DownloadInfo;
import com.mclqe.launcher.model.VersionMeta;
import com.mclqe.launcher.net.DownloadManager;
import com.mclqe.launcher.net.DownloadSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 启动编排：文件准备（客户端 jar / 依赖库 / 资源 / natives）→ 参数拼接 → 进程启动与日志转发。
 */
public class GameLauncher {

    /** 日志回调 */
    public interface LogSink {
        void log(String line);
    }

    /** 进程退出回调 */
    public interface ExitSink {
        void onExit(int exitCode);
    }

    private final GamePaths paths;
    private final Consumer<String> internalLogger;
    private final DownloadManager.ProgressListener progressListener;

    private final List<LogSink> logSinks = new CopyOnWriteArrayList<>();
    private final List<ExitSink> exitSinks = new CopyOnWriteArrayList<>();

    private volatile Process process;
    private volatile boolean preparing;

    public GameLauncher(GamePaths paths, Consumer<String> logger, DownloadManager.ProgressListener progressListener) {
        this.paths = paths;
        this.internalLogger = logger == null ? m -> {
        } : logger;
        this.progressListener = progressListener == null ? DownloadManager.ProgressListener.NOOP : progressListener;
    }

    public void addLogSink(LogSink sink) {
        logSinks.add(sink);
    }

    public void addExitSink(ExitSink sink) {
        exitSinks.add(sink);
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public boolean isPreparing() {
        return preparing;
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 完整启动流程（阻塞直到 Minecraft 进程被创建；进程随后在后台运行）。
     * 必须在后台线程调用，方法内部会做网络与磁盘 IO。
     */
    public synchronized void launch(LauncherConfig config) throws Exception {
        launch(config, null);
    }

    /**
     * 完整启动流程（阻塞直到 Minecraft 进程被创建；进程随后在后台运行）。
     * 必须在后台线程调用，方法内部会做网络与磁盘 IO。
     *
     * @param account 当前账户（离线或 Microsoft 正版）；为 null 时按 config 里的离线信息生成
     */
    public synchronized void launch(LauncherConfig config, AuthAccount account) throws Exception {
        if (isRunning()) {
            log("游戏已在运行中，忽略本次启动请求");
            return;
        }
        AuthAccount effectiveAccount = account != null
                ? account
                : OfflineAccount.of(config.getUsername(), config.getUuid());
        preparing = true;
        try {
            Path gameDir = resolveGameDir(config);
            // 版本隔离：实例开启隔离后，游戏工作目录（mods / saves / 资源包等）落在 versions/<id> 内
            Path contentRoot = InstanceManager.contentRoot(gameDir, config.getVersionId(),
                    config.isDefaultVersionIsolation());
            Files.createDirectories(contentRoot);
            Files.createDirectories(gameDir);
            if (!contentRoot.equals(gameDir)) {
                log("版本隔离已开启，内容目录: " + contentRoot);
            }

            Path javaExecutable = resolveJava(config);
            log("使用 Java: " + javaExecutable);

            VersionManager versionManager = new VersionManager(paths, internalLogger);
            VersionMeta meta = versionManager.getVersionMeta(config.getVersionId(), false);
            log("已解析版本: " + meta.getId());

            // ---------------- 1. 收集下载任务 ----------------
            List<DownloadManager.Task> tasks = new ArrayList<>();

            String jarId = (meta.getJar() != null && !meta.getJar().isBlank()) ? meta.getJar() : config.getVersionId();
            Path clientJar = paths.clientJar(jarId);
            DownloadInfo client = meta.getClientDownload();
            String clientUrl = (client == null) ? null : client.getUrl();
            if (clientUrl == null || clientUrl.isBlank()) {
                // 版本 JSON 未登记 client 地址时，仅自定义源保留 BMCLAPI 式直链接口
                // （官方源与 bsmirror 均不提供 /version/<id>/client，实测 404）
                clientUrl = DownloadSource.active().clientJarUrl(jarId);
            }
            if (clientUrl != null && !clientUrl.isBlank()) {
                // 本地缓存版本 JSON 内的官方地址在此按下载源重写（rawUrl 保留原址用于兜底）
                tasks.add(new DownloadManager.Task(DownloadSource.rewrite(clientUrl), clientUrl, clientJar,
                        client == null ? null : client.getSha1(),
                        client == null ? 0 : client.getSize()));
            } else if (!Files.isRegularFile(clientJar)) {
                throw new IOException("版本 JSON 缺少 client 下载信息，且本地不存在 " + clientJar);
            }

            LibraryManager libraryManager = new LibraryManager(paths, internalLogger);
            tasks.addAll(libraryManager.buildDownloadTasks(meta.getLibraries()));

            AssetManager assetManager = new AssetManager(paths, internalLogger);
            String assetIndexName = meta.getAssets();
            if (meta.getAssetIndex() != null) {
                assetManager.ensureIndex(meta.getAssetIndex());
                AssetObjects assetIndex = assetManager.readIndex(meta.getAssetIndex());
                tasks.addAll(assetManager.buildDownloadTasks(assetIndex));
                if (assetIndexName == null || assetIndexName.isBlank()) {
                    assetIndexName = meta.getAssetIndex().getId();
                }
            } else if (assetIndexName == null) {
                assetIndexName = "legacy";
            }

            log("待处理文件共 " + tasks.size() + " 个（已存在的将自动跳过）");
            int threads = config.getDownloadThreads();
            log("下载线程数: " + threads + "（可在「设置 - 下载线程数」中调整）");
            DownloadManager downloadManager = new DownloadManager(
                    threads, internalLogger, progressListener);
            if (!downloadManager.downloadAll(tasks)) {
                throw new IOException("部分文件下载失败，请检查网络后重试");
            }

            // ---------------- 2. 解压 natives ----------------
            Path nativesDir = paths.nativesDir(config.getVersionId());
            Files.createDirectories(nativesDir);
            int extracted = 0;
            for (LibraryManager.NativeEntry entry : libraryManager.collectNatives(meta.getLibraries())) {
                extracted += NativeExtractor.extract(entry.zip, nativesDir, entry.exclude);
            }
            log("natives 解压完成，共 " + extracted + " 个文件 -> " + nativesDir);

            // ---------------- 3. 组装 classpath ----------------
            List<Path> classpathEntries = new ArrayList<>(libraryManager.buildClasspath(meta.getLibraries()));
            classpathEntries.add(clientJar);
            String classpath = classpathEntries.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));

            // ---------------- 4. 构建启动上下文 ----------------
            LaunchProfile profile = new LaunchProfile();
            profile.setVersionId(config.getVersionId());
            profile.setVersionType(meta.getType() == null ? "release" : meta.getType());
            profile.setJavaExecutable(javaExecutable);
            profile.setGameDir(contentRoot);
            profile.setAssetsDir(paths.assetsDir());
            profile.setLibrariesDir(paths.librariesDir());
            profile.setNativesDir(nativesDir);
            profile.setClientJar(clientJar);
            profile.setClasspath(classpath);
            profile.setAssetIndexName(assetIndexName);
            profile.setMaxMemoryMb(config.getMaxMemoryMb());
            profile.setMinMemoryMb(config.getMinMemoryMb());
            profile.setExtraJvmArgs(config.getJvmArgs());
            profile.setWindowWidth(config.getWindowWidth());
            profile.setWindowHeight(config.getWindowHeight());
            profile.setAccount(effectiveAccount);
            log("使用账户: " + effectiveAccount.getUsername() + "（" + effectiveAccount.getUserType()
                    + (effectiveAccount.getUuid().isBlank() ? "" : " / " + effectiveAccount.getUuid()) + "）");

            PlatformRules.setFeature("has_custom_resolution",
                    config.getWindowWidth() > 0 && config.getWindowHeight() > 0);

            List<String> command = CommandBuilder.build(profile, meta);
            log("---------- 启动命令 ----------");
            log(maskSecrets(command));
            log("------------------------------");

            // ---------------- 5. 启动进程 ----------------
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(contentRoot.toFile());
            processBuilder.redirectErrorStream(true);

            Process p = processBuilder.start();
            this.process = p;
            log("Minecraft 已启动，PID = " + p.pid());

            Thread stdoutThread = new Thread(() -> pumpOutput(p), "mcqe-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            Thread waitThread = new Thread(() -> {
                int code;
                try {
                    code = p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    code = -1;
                }
                log("游戏进程已退出，退出码 = " + code);
                for (ExitSink sink : exitSinks) {
                    try {
                        sink.onExit(code);
                    } catch (Exception ignored) {
                        // 回调异常不影响主流程
                    }
                }
            }, "mcqe-wait");
            waitThread.setDaemon(true);
            waitThread.start();

        } finally {
            preparing = false;
        }
    }

    /** 强制结束游戏进程。 */
    public synchronized void stop() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            log("当前没有正在运行的游戏进程");
            return;
        }
        log("正在结束游戏进程 ...");
        p.destroy();
        try {
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log("进程未在 5 秒内退出，执行强制结束");
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private void pumpOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log("[游戏] " + line);
            }
        } catch (IOException ignored) {
            // 进程结束时流关闭，属正常情况
        }
    }

    private void log(String line) {
        internalLogger.accept(line);
        for (LogSink sink : logSinks) {
            try {
                sink.log(line);
            } catch (Exception ignored) {
                // 单个 sink 异常不影响其他 sink
            }
        }
    }

    /**
     * 隐藏启动命令中的登录令牌（--accessToken 的值），避免正版令牌出现在界面日志里。
     */
    private static String maskSecrets(List<String> command) {
        StringBuilder sb = new StringBuilder();
        boolean maskNext = false;
        for (String arg : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (maskNext && arg != null && !arg.startsWith("--")) {
                sb.append("***");
                maskNext = false;
                continue;
            }
            sb.append(arg);
            if ("--accessToken".equals(arg)) {
                maskNext = true;
            }
        }
        return sb.toString();
    }

    private Path resolveGameDir(LauncherConfig config) {
        Path dir = config.gameDirOrNull();
        return dir == null ? paths.root() : dir;
    }

    /**
     * Java 选择策略（同机多版本共存）：
     * 1) 用户显式配置的 javaPath 且其主版本满足该实例要求 → 直接使用（保留手动优先级）；
     * 2) 否则枚举本机全部 Java 候选（注册表 / JAVA_HOME / PATH / 常见目录 / 内置 runtime 等），
     *    按版本 JSON 要求的主版本择优（同主版本取版本号最高），用于实际启动；
     * 3) 无精确匹配时退化为最接近的候选（优先较低版本，避免旧版本游戏被高版本 Java 启动崩溃）。
     */
    private Path resolveJava(LauncherConfig config) throws IOException {
        int required = requiredJavaMajor(config.getVersionId());

        List<JavaDetector.JavaInfo> candidates = JavaDetector.detectAll();
        for (JavaDetector.JavaInfo info : candidates) {
            log("检测到 " + info);
        }

        JavaDetector.JavaInfo manual = null;
        if (config.getJavaPath() != null && !config.getJavaPath().isBlank()) {
            Path configured = Path.of(config.getJavaPath());
            if (Files.isRegularFile(configured)) {
                manual = JavaDetector.probeExecutable(configured);
                if (manual == null) {
                    log("手动指定的 Java 无法识别版本，按配置使用: " + configured);
                    return configured;
                }
                if (manual.getMajorVersion() == required && JavaDetector.isArchCompatible(manual)) {
                    log("使用手动指定的 Java " + manual.getVersion() + "（满足 Java " + required + "）");
                    return configured;
                }
                log("手动指定的 Java " + manual.getVersion() + " 与实例要求 Java " + required
                        + " 不符，改从 " + candidates.size() + " 个候选中择优");
            } else if (!config.isAutoDetectJava()) {
                throw new IOException("配置的 Java 路径无效: " + configured);
            } else {
                log("配置的 Java 路径无效，回退到自动检测: " + configured);
            }
        }

        JavaDetector.JavaInfo best = JavaDetector.pickBest(candidates, required);
        if (best != null) {
            if (manual != null && !manual.getJavaExecutable().toAbsolutePath().normalize()
                    .equals(best.getJavaExecutable().toAbsolutePath().normalize())) {
                log("已自动改用 Java " + best.getVersion() + "（" + best.getHome() + "，来源：" + best.getOrigin() + "）");
            }
            return best.getJavaExecutable();
        }

        JavaDetector.JavaInfo fallback = JavaDetector.pickFallback(candidates, required);
        if (fallback != null) {
            log("未找到 Java " + required + "，退化为最接近的 Java " + fallback.getVersion()
                    + "（" + fallback.getHome() + "，来源：" + fallback.getOrigin() + "）");
            return fallback.getJavaExecutable();
        }
        if (manual != null) {
            log("未检测到其它 Java，沿用手动指定的 Java " + manual.getVersion());
            return manual.getJavaExecutable();
        }
        throw new IOException("未检测到可用的 Java 运行时，请在界面中手动指定 java.exe");
    }

    /** 解析实例所需的 Java 主版本（版本 JSON 缺失时按版本号规则推断）。 */
    private int requiredJavaMajor(String versionId) {
        Path versionJson = null;
        try {
            if (versionId != null && !versionId.isBlank()) {
                versionJson = paths.versionJson(versionId.trim());
            }
        } catch (RuntimeException ignored) {
            versionJson = null;
        }
        try {
            return JavaRequirement.resolve(versionId, versionJson).requiredMajor();
        } catch (RuntimeException e) {
            return JavaRequirement.DEFAULT_MAJOR;
        }
    }
}
