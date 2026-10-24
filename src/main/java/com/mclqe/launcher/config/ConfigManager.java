package com.mclqe.launcher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mclqe.launcher.net.DownloadSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动器配置读写（JSON 持久化）。
 * 默认位置：%USER_HOME%/.mclqe/config.json
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path configFile;

    public ConfigManager(Path configDir) {
        this.configFile = configDir.resolve("config.json");
    }

    public static Path defaultConfigDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".mclqe");
    }

    public Path getConfigFile() {
        return configFile;
    }

    /** 读取配置；文件不存在或损坏时返回默认配置，不抛异常。 */
    public LauncherConfig load() {
        LauncherConfig cfg = null;
        try {
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile, StandardCharsets.UTF_8);
                cfg = GSON.fromJson(json, LauncherConfig.class);
            }
        } catch (Exception ignored) {
            // 配置损坏时静默回退到默认配置
        }
        if (cfg == null) {
            cfg = new LauncherConfig();
        }
        // 旧 config.json 缺少下载源字段或为历史值（BMCLAPI / MCBBS）时由 of() 回退 AUTO，这里同步生效
        applyDownloadSource(cfg);
        return cfg;
    }

    /** 把配置中的下载源同步到 {@link DownloadSource} 运行时状态（切换后立即生效；AUTO 时后台启动自动优选探测）。 */
    public static void applyDownloadSource(LauncherConfig config) {
        // 历史值（BMCLAPI / MCBBS）与未知值统一归一为 auto，避免旧配置反复回退时把旧值写回磁盘
        DownloadSource selected = DownloadSource.of(config.getDownloadSource());
        config.setDownloadSource(selected.key());
        DownloadSource.configure(selected.key(), config.getCustomSourceRoot());
    }

    public void save(LauncherConfig config) throws IOException {
        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(configFile, GSON.toJson(config), StandardCharsets.UTF_8);
    }
}
