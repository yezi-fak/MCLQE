package com.mclqe.launcher.auth;

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

/**
 * 正版账户持久化。
 *
 * <p>落盘位置：{@code D:\MCLQE\SA\STEV\accounts.json}（见 {@link AuthPaths#msaFile()}）。结构为
 * 「msa 账户列表 + 当前选中的 UUID」，与离线账户文件（{@code D:\MCLQE\SA\offline.json}）物理隔离，
 * 正版令牌不会污染非正版账户。</p>
 *
 * <p>切回离线账户只清空 selectedUuid，已保存的 msa 记录保留（不参与启动）。</p>
 *
 * <p>安全约束：本类不打印文件内容，也不在异常信息中携带令牌。</p>
 */
public final class AccountStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;
    private Data data = new Data();

    /** accounts.json 的内存结构。 */
    public static final class Data {
        private List<MicrosoftAccount> microsoft = new ArrayList<>();
        /** 当前选中的正版账户 UUID；空串表示使用离线账户 */
        private String selectedUuid = "";

        public List<MicrosoftAccount> getMicrosoft() {
            return microsoft;
        }

        public void setMicrosoft(List<MicrosoftAccount> microsoft) {
            this.microsoft = microsoft == null ? new ArrayList<>() : microsoft;
        }

        public String getSelectedUuid() {
            return selectedUuid;
        }

        public void setSelectedUuid(String selectedUuid) {
            this.selectedUuid = selectedUuid == null ? "" : selectedUuid;
        }
    }

    /** 使用默认位置：D:\MCLQE\SA\STEV\accounts.json。 */
    public AccountStore() {
        this(AuthPaths.msaDir());
    }

    public AccountStore(Path configDir) {
        this.file = configDir.resolve("accounts.json");
        load();
    }

    public Path getFile() {
        return file;
    }

    /** 读取账户文件；文件缺失或损坏时回退为空列表（不抛异常）。 */
    public synchronized void load() {
        Data loaded = null;
        try {
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                loaded = GSON.fromJson(json, Data.class);
            }
        } catch (Exception ignored) {
            // 账户文件损坏时静默回退，避免启动失败
        }
        if (loaded == null) {
            loaded = new Data();
        }
        if (loaded.getMicrosoft() == null) {
            loaded.setMicrosoft(new ArrayList<>());
        }
        if (loaded.getSelectedUuid() == null) {
            loaded.setSelectedUuid("");
        }
        data = loaded;
    }

    /** 原子写入 accounts.json（先写临时文件再移动，避免中断导致文件损坏）。 */
    public synchronized void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling("accounts.json.tmp");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized List<MicrosoftAccount> microsoftAccounts() {
        return new ArrayList<>(data.getMicrosoft());
    }

    /** 当前选中的正版账户；未选中时返回 null（表示使用离线账户）。 */
    public synchronized MicrosoftAccount selectedAccount() {
        String selected = data.getSelectedUuid();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        for (MicrosoftAccount account : data.getMicrosoft()) {
            if (account != null && selected.equalsIgnoreCase(account.getUuid())) {
                return account;
            }
        }
        return null;
    }

    public synchronized String selectedUuid() {
        return data.getSelectedUuid();
    }

    /** 以 UUID 为键新增或更新账户。 */
    public synchronized void upsert(MicrosoftAccount account) {
        if (account == null || account.getUuid() == null || account.getUuid().isBlank()) {
            return;
        }
        List<MicrosoftAccount> list = data.getMicrosoft();
        for (int i = 0; i < list.size(); i++) {
            MicrosoftAccount existing = list.get(i);
            if (existing != null && account.getUuid().equalsIgnoreCase(existing.getUuid())) {
                list.set(i, account);
                return;
            }
        }
        list.add(account);
    }

    /** 选中某个正版账户（不存在时先入库）。 */
    public synchronized void select(MicrosoftAccount account) {
        if (account == null) {
            return;
        }
        upsert(account);
        data.setSelectedUuid(account.getUuid());
    }

    /** 切回离线账户：保留 msa 记录，仅清空选中项。 */
    public synchronized void clearSelection() {
        data.setSelectedUuid("");
    }

    /** 删除某个正版账户（退出登录时可选调用）；同时清理选中状态。 */
    public synchronized void remove(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        data.getMicrosoft().removeIf(a -> a != null && uuid.equalsIgnoreCase(a.getUuid()));
        if (uuid.equalsIgnoreCase(data.getSelectedUuid())) {
            data.setSelectedUuid("");
        }
    }

    public synchronized boolean isEmpty() {
        return data.getMicrosoft().isEmpty();
    }
}
