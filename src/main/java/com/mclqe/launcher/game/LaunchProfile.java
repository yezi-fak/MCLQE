package com.mclqe.launcher.game;

import com.mclqe.launcher.auth.AuthAccount;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动上下文：承载参数拼接所需的一切路径与账户信息，并负责生成占位符映射表。
 */
public class LaunchProfile {

    public static final String LAUNCHER_NAME = "MCLQE";
    public static final String LAUNCHER_VERSION = "1.0.0";

    private String versionId = "";
    private String versionType = "release";

    private Path javaExecutable;
    private Path gameDir;
    private Path assetsDir;
    private Path librariesDir;
    private Path nativesDir;
    private Path clientJar;

    private String classpath = "";
    private String assetIndexName = "";

    private int maxMemoryMb = 4096;
    private int minMemoryMb = 512;
    private String extraJvmArgs = "";

    private int windowWidth;
    private int windowHeight;

    private AuthAccount account;

    /** 占位符映射，供 ${...} 替换使用。 */
    public Map<String, String> toPlaceholders() {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("${auth_player_name}", account == null ? "Player" : account.getUsername());
        ph.put("${version_name}", versionId);
        ph.put("${version_type}", versionType);
        ph.put("${game_directory}", str(gameDir));
        ph.put("${assets_root}", str(assetsDir));
        ph.put("${assets_index_name}", assetIndexName);
        ph.put("${auth_uuid}", account == null ? "" : account.getUuid());
        ph.put("${auth_access_token}", account == null ? "0" : account.getAccessToken());
        ph.put("${auth_session}", account == null ? "0" : account.getAccessToken());
        ph.put("${user_type}", account == null ? "legacy" : account.getUserType());
        ph.put("${natives_directory}", str(nativesDir));
        ph.put("${library_directory}", str(librariesDir));
        ph.put("${classpath}", classpath);
        ph.put("${classpath_separator}", File.pathSeparator);
        ph.put("${launcher_name}", LAUNCHER_NAME);
        ph.put("${launcher_version}", LAUNCHER_VERSION);
        ph.put("${clientid}", account == null ? "" : blankToEmpty(account.getClientId()));
        ph.put("${auth_xuid}", account == null ? "" : blankToEmpty(account.getXuid()));
        ph.put("${user_properties}", account == null ? "{}" : blankToDefault(account.getUserProperties(), "{}"));
        ph.put("${resolution_width}", String.valueOf(windowWidth));
        ph.put("${resolution_height}", String.valueOf(windowHeight));
        ph.put("${quickPlayPath}", "");
        ph.put("${quickPlaySingleplayer}", "");
        ph.put("${quickPlayMultiplayer}", "");
        ph.put("${quickPlayRealms}", "");
        return ph;
    }

    private static String str(Path path) {
        return path == null ? "" : path.toString();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    // ------------------------------------------------------------------ getters / setters

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getVersionType() {
        return versionType;
    }

    public void setVersionType(String versionType) {
        this.versionType = versionType;
    }

    public Path getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(Path javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    public Path getGameDir() {
        return gameDir;
    }

    public void setGameDir(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Path getAssetsDir() {
        return assetsDir;
    }

    public void setAssetsDir(Path assetsDir) {
        this.assetsDir = assetsDir;
    }

    public Path getLibrariesDir() {
        return librariesDir;
    }

    public void setLibrariesDir(Path librariesDir) {
        this.librariesDir = librariesDir;
    }

    public Path getNativesDir() {
        return nativesDir;
    }

    public void setNativesDir(Path nativesDir) {
        this.nativesDir = nativesDir;
    }

    public Path getClientJar() {
        return clientJar;
    }

    public void setClientJar(Path clientJar) {
        this.clientJar = clientJar;
    }

    public String getClasspath() {
        return classpath;
    }

    public void setClasspath(String classpath) {
        this.classpath = classpath;
    }

    public String getAssetIndexName() {
        return assetIndexName;
    }

    public void setAssetIndexName(String assetIndexName) {
        this.assetIndexName = assetIndexName;
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

    public String getExtraJvmArgs() {
        return extraJvmArgs;
    }

    public void setExtraJvmArgs(String extraJvmArgs) {
        this.extraJvmArgs = extraJvmArgs;
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

    public AuthAccount getAccount() {
        return account;
    }

    public void setAccount(AuthAccount account) {
        this.account = account;
    }
}
