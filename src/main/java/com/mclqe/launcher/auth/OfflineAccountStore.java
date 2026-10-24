package com.mclqe.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 非正版（离线）账户凭证持久化。
 *
 * <p>落盘位置：{@code D:\MCLQE\SA\offline.json}（见 {@link AuthPaths#offlineFile()}），
 * 与正版账户文件（{@code SA\STEV\accounts.json}）物理隔离，两类账户互不干扰。</p>
 *
 * <p>文件结构：{@code username / uuid / accessToken / userType}；uuid 采用官方离线算法派生。</p>
 */
public final class OfflineAccountStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;
    private String username = "Steve";
    private String uuid = "";
    private boolean loaded;

    /** offline.json 的磁盘结构。 */
    private static final class Data {
        private String username = "Steve";
        private String uuid = "";
        private String accessToken = "0";
        private String userType = "legacy";
    }

    public OfflineAccountStore() {
        this(AuthPaths.offlineDir());
    }

    public OfflineAccountStore(Path dir) {
        this.file = dir.resolve("offline.json");
    }

    public Path getFile() {
        return file;
    }

    public String getUsername() {
        return username == null || username.isBlank() ? "Steve" : username;
    }

    /** 无连字符的 32 位 UUID；为空时按用户名派生。 */
    public String getUuid() {
        if (uuid == null || uuid.isBlank()) {
            return OfflineAccount.offlineUuid(getUsername());
        }
        return uuid.replace("-", "");
    }

    /** 是否已成功从磁盘读取过（false 表示文件缺失或损坏，可回退到旧配置字段）。 */
    public boolean isLoaded() {
        return loaded;
    }

    /** 读取 offline.json；文件缺失或损坏时返回 false（不抛异常）。 */
    public synchronized boolean load() {
        loaded = false;
        try {
            if (Files.exists(file)) {
                Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (data != null) {
                    username = data.username;
                    uuid = data.uuid;
                    loaded = true;
                }
            }
        } catch (Exception ignored) {
            // 文件损坏时静默回退，避免启动失败
        }
        if (!loaded) {
            uuid = OfflineAccount.offlineUuid(getUsername());
        }
        return loaded;
    }

    /** 更新内存中的离线账户（用户名变化时重算 UUID），并立即落盘。 */
    public synchronized void update(String username, String uuid) throws IOException {
        String name = (username == null || username.isBlank()) ? "Steve" : username.trim();
        String derived = (uuid == null || uuid.isBlank()) ? OfflineAccount.offlineUuid(name) : uuid;
        boolean changed = !name.equals(this.username) || !derived.equalsIgnoreCase(this.uuid);
        this.username = name;
        this.uuid = derived;
        if (changed || !Files.exists(file)) {
            save();
        }
    }

    /** 仅按用户名更新（UUID 同步派生）。 */
    public synchronized void updateUsername(String username) throws IOException {
        update(username, null);
    }

    /** 原子写入 offline.json（先写临时文件再移动，避免中断导致文件损坏）。 */
    public synchronized void save() throws IOException {
        Data data = new Data();
        data.username = getUsername();
        data.uuid = getUuid();
        data.accessToken = "0";
        data.userType = "legacy";

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling("offline.json.tmp");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        loaded = true;
    }
}
