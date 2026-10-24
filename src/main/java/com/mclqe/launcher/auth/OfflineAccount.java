package com.mclqe.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 离线账户。
 * <p>UUID 采用官方离线算法：UUID.nameUUIDFromBytes("OfflinePlayer:&lt;name&gt;".getBytes(UTF_8))，
 * 与 HMCL / 官方启动器离线模式保持一致，可被服务端识别为同一玩家。</p>
 */
public class OfflineAccount implements AuthAccount {

    private final String username;
    /** 无连字符的 32 位 UUID 字符串 */
    private final String uuid;
    private final String accessToken;
    private final String userType;

    public OfflineAccount(String username, String uuid, String accessToken, String userType) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.userType = userType;
    }

    /** 依据用户名创建离线账户（自动派生 UUID）。 */
    public static OfflineAccount of(String username) {
        String name = (username == null || username.isBlank()) ? "Player" : username.trim();
        return new OfflineAccount(name, offlineUuid(name), "0", "legacy");
    }

    /** 使用已有 UUID 创建离线账户；uuid 为空时按用户名派生。 */
    public static OfflineAccount of(String username, String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return of(username);
        }
        return new OfflineAccount(username, uuid.replace("-", ""), "0", "legacy");
    }

    public static String offlineUuid(String username) {
        UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return u.toString().replace("-", "");
    }

    @Override
    public String getUsername() {
        return username;
    }

    /** 参数替换 ${auth_uuid} 使用：带连字符的标准格式 */
    @Override
    public String getUuid() {
        if (uuid.length() == 32) {
            return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16)
                    + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
        }
        return uuid;
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String getUserType() {
        return userType;
    }
}
