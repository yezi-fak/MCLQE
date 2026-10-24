package com.mclqe.launcher.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机 Java 运行时检测（支持同机多版本共存）。
 *
 * <p>枚举来源（全部合并后按「可执行文件绝对路径 + 版本」去重）：
 * <ol>
 *   <li>环境变量 {@code JAVA_HOME} / {@code JDK_HOME}；</li>
 *   <li>当前运行的 JVM（{@code java.home}，jre 目录上溯到 jdk）；</li>
 *   <li>{@code PATH} 中的<b>每一个</b> java 可执行文件；</li>
 *   <li>Windows 注册表（JavaSoft / Eclipse Adoptium / Zulu / Liberica / Corretto / Semeru / SapMachine 等，含 WOW6432Node 与 HKCU）；</li>
 *   <li>常见安装目录（Program Files 下各发行版、用户目录 {@code .jdks} / {@code .gradle\\jdks} 等）；</li>
 *   <li>启动器内置 / 随包的 runtime（jpackage app 目录、jar 同级 runtime、{@code MCLQE_HOME}）；</li>
 *   <li>官方启动器 {@code .minecraft\\runtime} 下的 java-runtime-* 目录。</li>
 * </ol>
 * 优先读取 &lt;home&gt;/release 文件，避免启动子进程，速度更快。</p>
 */
public final class JavaDetector {

    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"");
    private static final Pattern REG_VALUE_PATTERN =
            Pattern.compile("^\\s+(\\S+)\\s+REG_(?:SZ|EXPAND_SZ)\\s+(.+)$");
    private static final Pattern ENV_TOKEN_PATTERN = Pattern.compile("%([^%]+)%");

    /** 注册表探测根（会分别在 HKLM、HKCU 以及 WOW6432Node 下尝试） */
    private static final String[] REG_KEYS = {
            "SOFTWARE\\JavaSoft",
            "SOFTWARE\\JavaSoft\\JDK",
            "SOFTWARE\\JavaSoft\\JRE",
            "SOFTWARE\\JavaSoft\\Java Development Kit",
            "SOFTWARE\\JavaSoft\\Java Runtime Environment",
            "SOFTWARE\\Eclipse Adoptium",
            "SOFTWARE\\Microsoft\\JDK",
            "SOFTWARE\\Azul Systems\\Zulu",
            "SOFTWARE\\BellSoft",
            "SOFTWARE\\Amazon Corretto",
            "SOFTWARE\\Semeru",
            "SOFTWARE\\SapMachine"
    };

    private JavaDetector() {
    }

    /** 单个 Java 运行时信息 */
    public static class JavaInfo {
        private final Path home;
        private final Path javaExecutable;
        private final String version;
        private final int majorVersion;
        /** 运行时架构（x86_64 / x86 / aarch64）；未知时为空串 */
        private final String arch;
        /** 发现来源（JAVA_HOME / PATH / 注册表 / 内置 runtime ...），便于日志排查 */
        private final String origin;

        JavaInfo(Path home, Path javaExecutable, String version, int majorVersion) {
            this(home, javaExecutable, version, majorVersion, "", "");
        }

        JavaInfo(Path home, Path javaExecutable, String version, int majorVersion, String arch) {
            this(home, javaExecutable, version, majorVersion, arch, "");
        }

        JavaInfo(Path home, Path javaExecutable, String version, int majorVersion, String arch, String origin) {
            this.home = home;
            this.javaExecutable = javaExecutable;
            this.version = version;
            this.majorVersion = majorVersion;
            this.arch = arch == null ? "" : arch;
            this.origin = origin == null ? "" : origin;
        }

        public Path getHome() {
            return home;
        }

        public Path getJavaExecutable() {
            return javaExecutable;
        }

        public String getVersion() {
            return version;
        }

        public int getMajorVersion() {
            return majorVersion;
        }

        /** 运行时架构（release 文件 OS_ARCH 字段）；未知返空串 */
        public String getArch() {
            return arch;
        }

        /** 发现来源；未知返空串 */
        public String getOrigin() {
            return origin;
        }

        @Override
        public String toString() {
            StringJoiner joiner = new StringJoiner("  -  ");
            joiner.add("Java " + version + (arch.isEmpty() ? "" : " (" + arch + ")"));
            joiner.add(home == null ? "" : home.toString());
            if (!origin.isEmpty()) {
                joiner.add("来源: " + origin);
            }
            return joiner.toString();
        }
    }

    // ------------------------------------------------------------------ 批量检测

    /** 枚举本机全部 Java 运行时，按主版本降序返回（不再只返回第一个命中的 java）。 */
    public static List<JavaInfo> detectAll() {
        Map<String, JavaInfo> unique = new LinkedHashMap<>();

        // 1) 环境变量
        addEnvHome(unique, "JAVA_HOME");
        addEnvHome(unique, "JDK_HOME");
        addEnvHome(unique, "MCLQE_JAVA_HOME");

        // 2) 当前运行的 JVM
        addCurrentJvm(unique);

        // 3) PATH 中的每一个 java
        for (Path exe : findAllOnPath()) {
            addExecutable(unique, exe, "PATH");
        }

        // 4) Windows 注册表
        if (isWindows()) {
            scanRegistry(unique);
        }

        // 5) 常见安装目录
        for (Path root : commonInstallRoots()) {
            scanHomeDir(unique, root, 2, "常见安装目录");
        }

        // 6) 启动器内置 / 随包 runtime
        scanBundledRuntimes(unique);

        // 7) 官方启动器 runtime
        scanMinecraftRuntimes(unique);

        List<JavaInfo> result = new ArrayList<>(unique.values());
        result.sort((a, b) -> {
            int byMajor = Integer.compare(b.getMajorVersion(), a.getMajorVersion());
            if (byMajor != 0) {
                return byMajor;
            }
            int byVersion = compareVersion(b.getVersion(), a.getVersion());
            if (byVersion != 0) {
                return byVersion;
            }
            return String.valueOf(a.getHome()).compareToIgnoreCase(String.valueOf(b.getHome()));
        });
        return result;
    }

    private static void addEnvHome(Map<String, JavaInfo> unique, String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return;
        }
        Path home;
        try {
            home = Paths.get(value.trim().replace("\"", ""));
        } catch (RuntimeException e) {
            return;
        }
        addCandidate(unique, home, name + " 环境变量");
    }

    private static void addCurrentJvm(Map<String, JavaInfo> unique) {
        String thisHome = System.getProperty("java.home");
        if (thisHome == null || thisHome.isBlank()) {
            return;
        }
        Path p = Paths.get(thisHome);
        // 运行在 jre 目录时上溯到 jdk
        if (p.getFileName() != null && "jre".equalsIgnoreCase(p.getFileName().toString())) {
            Path parent = p.getParent();
            if (parent != null) {
                addCandidate(unique, parent, "当前运行的 JVM");
            }
        }
        addCandidate(unique, p, "当前运行的 JVM");
    }

    /** Program Files 与用户目录下各常见发行版安装根。 */
    private static List<Path> commonInstallRoots() {
        List<Path> roots = new ArrayList<>();
        String[] programFiles = {"C:/Program Files", "C:/Program Files (x86)"};
        String[] vendors = {
                "Java",
                "Eclipse Adoptium",
                "Eclipse Foundation",
                "Temurin",
                "Microsoft",
                "Zulu",
                "Azul",
                "BellSoft",
                "BellSoft/Java",
                "Amazon Corretto",
                "Semeru",
                "IBM",
                "GraalVM",
                "SapMachine",
                "RedHat",
                "JetBrains",
                "Common Files/Oracle/Java",
                "Android/Android Studio/jbr",
                "Android/Android Studio/jre"
        };
        for (String pf : programFiles) {
            for (String vendor : vendors) {
                roots.add(Paths.get(pf, vendor));
            }
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            roots.add(Paths.get(localAppData, "Programs", "Eclipse Adoptium"));
            roots.add(Paths.get(localAppData, "Programs", "Microsoft"));
            roots.add(Paths.get(localAppData, "Programs", "Zulu"));
            roots.add(Paths.get(localAppData, "Programs", "IntelliJ IDEA"));
            roots.add(Paths.get(localAppData, "JetBrains", "Toolbox", "apps"));
        }

        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            roots.add(Paths.get(userProfile, ".jdks"));
            roots.add(Paths.get(userProfile, ".gradle", "jdks"));
            roots.add(Paths.get(userProfile, ".sdkman", "candidates", "java"));
            roots.add(Paths.get(userProfile, "scoop", "apps", "openjdk"));
            roots.add(Paths.get(userProfile, "scoop", "apps", "temurin"));
        }
        return roots;
    }

    /**
     * 扫描一个安装根：命中 {@code <root>/bin/java} 即视为运行时；否则按剩余层级向下展开
     * （Program Files\Java\jdk-21.0.12 这种一层、Toolbox\apps\xxx\current 这种多层都能覆盖）。
     */
    private static void scanHomeDir(Map<String, JavaInfo> unique, Path root, int depth, String origin) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        if (Files.isRegularFile(javaExecutableOf(root))) {
            addCandidate(unique, root, origin);
            return;
        }
        if (depth <= 0) {
            return;
        }
        try (var stream = Files.list(root)) {
            List<Path> children = stream.filter(Files::isDirectory).limit(64).toList();
            for (Path child : children) {
                scanHomeDir(unique, child, depth - 1, origin);
            }
        } catch (IOException | RuntimeException ignored) {
            // 无权限访问的目录直接忽略
        }
    }

    private static void addCandidate(Map<String, JavaInfo> unique, Path home, String origin) {
        JavaInfo info = probe(home);
        if (info == null) {
            return;
        }
        putUnique(unique, new JavaInfo(info.getHome(), info.getJavaExecutable(), info.getVersion(),
                info.getMajorVersion(), info.getArch(), origin));
    }

    private static void addExecutable(Map<String, JavaInfo> unique, Path exe, String origin) {
        if (exe == null || !Files.isRegularFile(exe)) {
            return;
        }
        JavaInfo info = probeExecutable(exe);
        if (info == null) {
            return;
        }
        putUnique(unique, new JavaInfo(info.getHome(), info.getJavaExecutable(), info.getVersion(),
                info.getMajorVersion(), info.getArch(), origin));
    }

    /**
     * 去重键：可执行文件真实路径（解析符号链接 / 目录联接后，小写）+ 版本号。
     * 这样 Program Files\Java\latest\jdk-21 这类联接目录不会与真实安装目录重复登记。
     */
    private static void putUnique(Map<String, JavaInfo> unique, JavaInfo info) {
        Path exe = info.getJavaExecutable().toAbsolutePath().normalize();
        try {
            exe = exe.toRealPath();
        } catch (IOException | RuntimeException ignored) {
            // 无法解析时退回绝对路径
        }
        String key = exe.toString().toLowerCase(Locale.ROOT) + "|" + info.getVersion();
        unique.putIfAbsent(key, info);
    }

    // ------------------------------------------------------------------ 注册表

    private static void scanRegistry(Map<String, JavaInfo> unique) {
        List<String> queries = new ArrayList<>();
        for (String key : REG_KEYS) {
            queries.add("HKLM\\" + key);
            queries.add("HKCU\\" + key);
            queries.add("HKLM\\SOFTWARE\\WOW6432Node\\" + key);
        }
        for (String key : queries) {
            for (String value : regQueryValues(key)) {
                Path home;
                try {
                    home = Paths.get(value);
                } catch (RuntimeException e) {
                    continue;
                }
                addCandidate(unique, home, "注册表 " + key.replace("HKLM\\", "").replace("HKCU\\", "HKCU\\"));
            }
        }
    }

    /** 读取注册表键下所有字符串值（递归），返回可能为 Java 安装目录的值。 */
    private static List<String> regQueryValues(String key) {
        List<String> values = new ArrayList<>();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", key, "/s");
            pb.redirectErrorStream(true);
            process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return values;
            }
            for (String line : sb.toString().split("\\R")) {
                Matcher matcher = REG_VALUE_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String name = matcher.group(1);
                String value = matcher.group(2).trim().replace("\"", "");
                if (!looksLikeJavaHome(name, value)) {
                    continue;
                }
                values.add(expandEnv(value));
            }
        } catch (IOException | RuntimeException e) {
            // 键不存在 / reg 不可用时静默忽略
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return values;
    }

    private static boolean looksLikeJavaHome(String valueName, String value) {
        if (value == null || value.isBlank() || value.length() < 4) {
            return false;
        }
        // 常见值名：JavaHome / InstallationPath / Path / Home / JREHome / JavaHome64
        String lowerName = valueName.toLowerCase(Locale.ROOT);
        boolean nameOk = lowerName.contains("home") || lowerName.contains("path");
        if (!nameOk) {
            return false;
        }
        char first = value.charAt(0);
        if (!Character.isLetter(first)) {
            return false;
        }
        // 值必须是磁盘上真实存在的目录
        try {
            return Files.isDirectory(Paths.get(value));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String expandEnv(String value) {
        Matcher matcher = ENV_TOKEN_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String env = System.getenv(name);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(env == null ? matcher.group(0) : env));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内置 / 官方 runtime

    /** 启动器自带 runtime：jpackage app 目录、jar 同级 runtime/jre、MCLQE_HOME、工作目录。 */
    private static void scanBundledRuntimes(Map<String, JavaInfo> unique) {
        List<Path> bases = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path parent = Paths.get(appPath).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                bases.add(parent);
            }
        }
        try {
            var source = JavaDetector.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Paths.get(source.getLocation().toURI());
                Path dir = Files.isDirectory(location) ? location : location.getParent();
                if (dir != null) {
                    bases.add(dir);
                    if (dir.getParent() != null) {
                        bases.add(dir.getParent());
                    }
                }
            }
        } catch (Exception ignored) {
            // 无法定位 jar 位置时忽略
        }
        String mclqeHome = System.getenv("MCLQE_HOME");
        if (mclqeHome != null && !mclqeHome.isBlank()) {
            bases.add(Paths.get(mclqeHome));
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            bases.add(Paths.get(userDir));
        }
        for (Path base : bases) {
            for (String name : new String[]{"runtime", "jre", "java", "jdk"}) {
                scanHomeDir(unique, base.resolve(name), 2, "启动器内置 runtime");
            }
        }
    }

    /** 官方启动器（以及部分第三方启动器）自带的 java-runtime-* 运行时。 */
    private static void scanMinecraftRuntimes(Map<String, JavaInfo> unique) {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return;
        }
        Path runtime = Paths.get(appData, ".minecraft", "runtime");
        if (!Files.isDirectory(runtime)) {
            return;
        }
        try (var stream = Files.walk(runtime, 4)) {
            List<Path> exes = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equalsIgnoreCase(isWindows() ? "java.exe" : "java"))
                    .limit(32)
                    .toList();
            for (Path exe : exes) {
                Path bin = exe.getParent();
                Path home = bin == null ? null : bin.getParent();
                if (home == null) {
                    continue;
                }
                addCandidate(unique, home, "官方启动器 runtime");
            }
        } catch (IOException | RuntimeException ignored) {
            // 忽略无权访问的目录
        }
    }

    // ------------------------------------------------------------------ 选择策略

    /**
     * 从全部候选中挑选「最合适」的运行时：优先主版本精确匹配（架构兼容），同主版本取版本号最高者。
     *
     * @return 无精确匹配时返回 null
     */
    public static JavaInfo pickBest(List<JavaInfo> candidates, int requiredMajor) {
        JavaInfo best = null;
        for (JavaInfo info : candidates) {
            if (info.getMajorVersion() != requiredMajor || !isArchCompatible(info)) {
                continue;
            }
            if (best == null || isBetter(info, best)) {
                best = info;
            }
        }
        return best;
    }

    /**
     * 同一主版本内的优劣：版本号高者优先；版本号相同时优先「架构已知」且「非 javapath 转发目录」的真实安装。
     */
    private static boolean isBetter(JavaInfo candidate, JavaInfo current) {
        int byVersion = compareVersion(candidate.getVersion(), current.getVersion());
        if (byVersion != 0) {
            return byVersion > 0;
        }
        boolean candidateArchKnown = !candidate.getArch().isEmpty();
        boolean currentArchKnown = !current.getArch().isEmpty();
        if (candidateArchKnown != currentArchKnown) {
            return candidateArchKnown;
        }
        boolean candidateShim = isOraclePathShim(candidate);
        boolean currentShim = isOraclePathShim(current);
        if (candidateShim != currentShim) {
            return !candidateShim;
        }
        return false;
    }

    /** Oracle 安装器写在 Common Files 下的 javapath / java8path 转发目录（非真实 JDK 目录）。 */
    private static boolean isOraclePathShim(JavaInfo info) {
        String home = info.getHome() == null ? "" : info.getHome().toString().toLowerCase(Locale.ROOT);
        return home.contains("common files") && (home.contains("javapath") || home.contains("java8path"));
    }

    /**
     * 无精确匹配时的兜底候选：优先取低于要求的最高版本（旧版本游戏用高版本 Java 更容易崩溃），
     * 只有全部高于要求时才取最低的高版本。
     */
    public static JavaInfo pickFallback(List<JavaInfo> candidates, int requiredMajor) {
        JavaInfo exact = pickBest(candidates, requiredMajor);
        if (exact != null) {
            return exact;
        }
        JavaInfo lower = null;
        JavaInfo higher = null;
        for (JavaInfo info : candidates) {
            if (!isArchCompatible(info) || info.getMajorVersion() <= 0) {
                continue;
            }
            int major = info.getMajorVersion();
            if (major < requiredMajor) {
                if (lower == null || major > lower.getMajorVersion()
                        || (major == lower.getMajorVersion() && isBetter(info, lower))) {
                    lower = info;
                }
            } else if (major > requiredMajor) {
                if (higher == null || major < higher.getMajorVersion()
                        || (major == higher.getMajorVersion()
                        && compareVersion(info.getVersion(), higher.getVersion()) < 0)) {
                    higher = info;
                }
            }
        }
        return lower != null ? lower : higher;
    }

    /**
     * 架构兼容性：64 位 Windows 无法用 32 位 Java 启动现代版本（缺少 64 位原生库）。
     * 架构未知时不拦截，避免误判。
     */
    public static boolean isArchCompatible(JavaInfo info) {
        if (info == null) {
            return false;
        }
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean host64 = osArch.contains("64") || "aarch64".equals(osArch);
        if (!host64) {
            return true;
        }
        String javaArch = info.getArch() == null ? "" : info.getArch().toLowerCase(Locale.ROOT);
        return !(javaArch.equals("x86") || javaArch.equals("i386") || javaArch.equals("i686"));
    }

    /** 版本号比较：按数字段逐段比较，"21.0.12" &gt; "21.0.2"、"1.8.0_402" 视为 1.8.0.402。 */
    public static int compareVersion(String left, String right) {
        String[] a = splitVersion(left);
        String[] b = splitVersion(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? toInt(a[i]) : 0;
            int y = i < b.length ? toInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static String[] splitVersion(String version) {
        if (version == null || version.isBlank()) {
            return new String[0];
        }
        return version.trim().split("[^0-9]+");
    }

    private static int toInt(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 单个探测

    /** 探测 JDK/JRE 目录，返回 null 表示不是有效的 Java 运行时。 */
    public static JavaInfo probe(Path home) {
        if (home == null) {
            return null;
        }
        Path exe = javaExecutableOf(home);
        if (!Files.isRegularFile(exe)) {
            return null;
        }
        String version = readReleaseVersion(home);
        if (version == null) {
            version = queryVersion(exe);
        }
        if (version == null) {
            return null;
        }
        return new JavaInfo(home, exe, version, parseMajorVersion(version), readReleaseArch(home));
    }

    /** 根据用户配置的 java.exe 路径构造 JavaInfo。 */
    public static JavaInfo probeExecutable(Path javaExecutable) {
        if (javaExecutable == null || !Files.isRegularFile(javaExecutable)) {
            return null;
        }
        Path home = javaExecutable.getParent();
        if (home != null && home.getFileName() != null && "bin".equalsIgnoreCase(home.getFileName().toString())) {
            home = home.getParent();
        }
        String version = home == null ? null : readReleaseVersion(home);
        if (version == null) {
            version = queryVersion(javaExecutable);
        }
        if (version == null) {
            return null;
        }
        return new JavaInfo(home == null ? javaExecutable.getParent() : home,
                javaExecutable, version, parseMajorVersion(version), readReleaseArch(home));
    }

    /** 从 release 文件的 OS_ARCH 字段读取架构；读取不到且是当前 JVM 所在目录时用 os.arch 兜底。 */
    private static String readReleaseArch(Path home) {
        if (home != null) {
            Path release = home.resolve("release");
            if (Files.isRegularFile(release)) {
                try {
                    String content = Files.readString(release, StandardCharsets.UTF_8);
                    Matcher matcher = Pattern.compile("OS_ARCH=\"([^\"]+)\"").matcher(content);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                } catch (IOException ignored) {
                    // 读取失败时用 os.arch 兜底
                }
            }
            String thisHome = System.getProperty("java.home");
            if (thisHome != null && home.toAbsolutePath().normalize()
                    .equals(Paths.get(thisHome).toAbsolutePath().normalize())) {
                return System.getProperty("os.arch", "");
            }
        }
        return "";
    }

    private static Path javaExecutableOf(Path home) {
        return home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    private static String readReleaseVersion(Path home) {
        if (home == null) {
            return null;
        }
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return null;
        }
        try {
            String content = Files.readString(release, StandardCharsets.UTF_8);
            Matcher matcher = RELEASE_VERSION_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IOException ignored) {
            // 读取失败则回退到执行 java -version
        }
        return null;
    }

    /** 执行 java -version 并解析版本号。 */
    public static String queryVersion(Path javaExecutable) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExecutable.toString(), "-version");
            pb.redirectErrorStream(true);
            process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(sb.toString());
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    /** "21.0.12" -> 21；"1.8.0_402" -> 8 */
    public static int parseMajorVersion(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String v = version.trim();
        try {
            if (v.startsWith("1.")) {
                String[] parts = v.split("\\.");
                return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            }
            int idx = 0;
            while (idx < v.length() && Character.isDigit(v.charAt(idx))) {
                idx++;
            }
            return idx == 0 ? -1 : Integer.parseInt(v.substring(0, idx));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** PATH 中的全部 java 可执行文件（不再只取第一个）。 */
    private static List<Path> findAllOnPath() {
        List<Path> found = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return found;
        }
        String exeName = isWindows() ? "java.exe" : "java";
        for (String segment : pathEnv.split(java.io.File.pathSeparator)) {
            if (segment.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(segment.trim().replace("\"", "")).resolve(exeName);
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate);
                }
            } catch (Exception ignored) {
                // 非法路径段忽略
            }
        }
        return found;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
