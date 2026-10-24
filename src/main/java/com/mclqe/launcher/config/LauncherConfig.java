package com.mclqe.launcher.config;

import com.mclqe.launcher.net.DownloadSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动器持久化配置（序列化为 JSON）。
 */
public class LauncherConfig {

    /** 当前选中的版本 id，如 1.20.1-forge-47.2.0 */
    private String versionId = "";

    /** 游戏根目录；为空时由启动器填充为「程序目录\MCLQE」（不可写时回退 %USERPROFILE%\MCLQE） */
    private String gameDir = "";

    /** 已登记的游戏目录列表（可多目录并存，gameDir 为当前生效目录，必然包含在列表内） */
    private List<String> gameDirs = new ArrayList<>();

    /** 下载线程数（1-64），同时用于客户端 / 资源 / 库文件并发下载 */
    private int downloadThreads = 16;

    /** 新建实例时默认启用版本隔离 */
    private boolean defaultVersionIsolation = true;

    /** 手动指定的 java.exe 路径；为空表示自动检测 */
    private String javaPath = "";

    private boolean autoDetectJava = true;

    /** ---------------- 启动时 Java 环境检测 ---------------- */
    /** 同一版本 + 同一结论的提示抑制窗口（24 小时），避免每次启动重复打扰 */
    public static final long JAVA_CHECK_SUPPRESS_MILLIS = 24L * 60 * 60 * 1000;

    /** 启动时检测 Java 环境，缺失或版本不符时提示并给出下载页 */
    private boolean javaCheckEnabled = true;
    /** 永久不再提示（设置页可恢复） */
    private boolean javaCheckDontAskAgain = false;
    /** 提示抑制截止时间（epoch 毫秒）；0 表示不抑制 */
    private long javaCheckSuppressUntil = 0L;
    /** 上次提示的标识：版本 id + "#" + 所需主版本 */
    private String javaCheckLastPromptVersion = "";

    /** 最大堆内存，单位 MB */
    private int maxMemoryMb = 4096;

    /** 最小堆内存，单位 MB */
    private int minMemoryMb = 512;

    /** 窗口分辨率（0 表示交给游戏默认值） */
    private int windowWidth = 854;
    private int windowHeight = 480;

    /** 自定义 JVM 参数，空格分隔 */
    private String jvmArgs = "";

    /** 深色主题开关 */
    private boolean darkMode = false;

    /** ---------------- 下载源 ---------------- */
    /** 下载源名称：AUTO / OFFICIAL / MIRROR / CUSTOM（默认 AUTO 自动优选） */
    private String downloadSource = "AUTO";
    /** 自定义下载源根地址（downloadSource = CUSTOM 时生效） */
    private String customSourceRoot = "";

    /** Modrinth 模组接口是否优先走中国镜像 mod.mcimirror.top（默认 false，镜像仅作兜底） */
    private boolean modrinthMirror = false;

    /** ---------------- 离线账户 ---------------- */
    private String username = "Steve";
    private String uuid = "";
    /** 离线模式固定为 0，仅占位以保证参数完整 */
    private String accessToken = "0";
    private String userType = "legacy";
    private String clientId = "";

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getGameDir() {
        return gameDir;
    }

    public void setGameDir(String gameDir) {
        this.gameDir = gameDir;
        if (gameDir != null && !gameDir.isBlank()) {
            addGameDir(gameDir);
        }
    }

    /** 已登记的游戏目录（去重、去空白，并保证当前生效目录始终在列表中）。 */
    public List<String> getGameDirs() {
        List<String> result = new ArrayList<>();
        String active = (gameDir == null || gameDir.isBlank()) ? null : gameDir.trim();
        if (active != null) {
            result.add(active);
        }
        if (gameDirs != null) {
            for (String dir : gameDirs) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                String trimmed = dir.trim();
                boolean duplicated = false;
                for (String exists : result) {
                    if (exists.equalsIgnoreCase(trimmed)) {
                        duplicated = true;
                        break;
                    }
                }
                if (!duplicated) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    public void setGameDirs(List<String> dirs) {
        this.gameDirs = dirs == null ? new ArrayList<>() : new ArrayList<>(dirs);
    }

    /** 登记一个游戏目录（已存在时忽略）。 */
    public void addGameDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        String trimmed = dir.trim();
        if (gameDirs == null) {
            gameDirs = new ArrayList<>();
        }
        for (String exists : gameDirs) {
            if (exists != null && exists.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        gameDirs.add(trimmed);
    }

    /**
     * 从列表移除游戏目录；若移除的是当前生效目录，则自动切换到剩余目录中的第一个，
     * 剩余目录为空时保持当前目录不变（避免启动器失去可用目录）。
     */
    public void removeGameDir(String dir) {
        if (dir == null || dir.isBlank() || gameDirs == null) {
            return;
        }
        String trimmed = dir.trim();
        gameDirs.removeIf(exists -> exists != null && exists.equalsIgnoreCase(trimmed));
        if (trimmed.equalsIgnoreCase(gameDir == null ? "" : gameDir.trim())) {
            List<String> rest = new ArrayList<>();
            for (String exists : gameDirs) {
                if (exists != null && !exists.isBlank()) {
                    rest.add(exists.trim());
                }
            }
            if (!rest.isEmpty()) {
                gameDir = rest.get(0);
            }
        }
    }

    public int getDownloadThreads() {
        return Math.max(1, Math.min(64, downloadThreads));
    }

    public void setDownloadThreads(int downloadThreads) {
        this.downloadThreads = Math.max(1, Math.min(64, downloadThreads));
    }

    public boolean isDefaultVersionIsolation() {
        return defaultVersionIsolation;
    }

    public void setDefaultVersionIsolation(boolean defaultVersionIsolation) {
        this.defaultVersionIsolation = defaultVersionIsolation;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public boolean isAutoDetectJava() {
        return autoDetectJava;
    }

    public void setAutoDetectJava(boolean autoDetectJava) {
        this.autoDetectJava = autoDetectJava;
    }

    public boolean isJavaCheckEnabled() {
        return javaCheckEnabled;
    }

    public void setJavaCheckEnabled(boolean javaCheckEnabled) {
        this.javaCheckEnabled = javaCheckEnabled;
    }

    public boolean isJavaCheckDontAskAgain() {
        return javaCheckDontAskAgain;
    }

    public void setJavaCheckDontAskAgain(boolean javaCheckDontAskAgain) {
        this.javaCheckDontAskAgain = javaCheckDontAskAgain;
    }

    public long getJavaCheckSuppressUntil() {
        return javaCheckSuppressUntil;
    }

    public void setJavaCheckSuppressUntil(long javaCheckSuppressUntil) {
        this.javaCheckSuppressUntil = javaCheckSuppressUntil;
    }

    public String getJavaCheckLastPromptVersion() {
        return javaCheckLastPromptVersion == null ? "" : javaCheckLastPromptVersion;
    }

    public void setJavaCheckLastPromptVersion(String javaCheckLastPromptVersion) {
        this.javaCheckLastPromptVersion = javaCheckLastPromptVersion == null ? "" : javaCheckLastPromptVersion;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    public void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    /** 旧 config.json 无该字段时由 Gson 保留字段默认值 AUTO；历史值 BMCLAPI / MCBBS 由 DownloadSource.of 回退为 AUTO。 */
    public String getDownloadSource() {
        return (downloadSource == null || downloadSource.isBlank()) ? "AUTO" : downloadSource;
    }

    public void setDownloadSource(String downloadSource) {
        this.downloadSource = downloadSource;
    }

    /** 自定义下载源根地址（downloadSource = CUSTOM 时生效）。读取时规范化：去空白、补协议、去尾部斜杠。 */
    public String getCustomSourceRoot() {
        return DownloadSource.normalizeRoot(customSourceRoot);
    }

    /** 设置自定义下载源根地址，落盘前统一规范化。 */
    public void setCustomSourceRoot(String customSourceRoot) {
        this.customSourceRoot = DownloadSource.normalizeRoot(customSourceRoot);
    }

    /** Modrinth 接口是否优先走中国镜像；关闭时官方优先、镜像兜底。 */
    public boolean isModrinthMirror() {
        return modrinthMirror;
    }

    public void setModrinthMirror(boolean modrinthMirror) {
        this.modrinthMirror = modrinthMirror;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    // ---------- 便捷方法 ----------

    public Path gameDirOrNull() {
        return (gameDir == null || gameDir.isBlank()) ? null : Path.of(gameDir);
    }
}
