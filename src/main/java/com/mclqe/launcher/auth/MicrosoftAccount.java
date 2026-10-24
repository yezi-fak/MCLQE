package com.mclqe.launcher.auth;

/**
 * Microsoft 正版账户。
 *
 * <p>除账户标识外还持有 refresh_token 与凭据到期时间，可在启动前用
 * {@link #refresh(MicrosoftAuthService)} 静默续期。</p>
 *
 * <p>安全约束：{@link #toString()} 不输出 accessToken / refreshToken，便于安全地写日志。</p>
 */
public class MicrosoftAccount implements AuthAccount {

    /**
     * 预刷新阈值：到期前 12 小时即视为需要刷新。
     *
     * <p>对齐 Prism Launcher 的 MinecraftAccount::shouldRefresh：Minecraft 令牌有效期约 24 小时，
     * 提前 12 小时刷新可保证玩家在长时间游戏后仍持有有效凭据，避免启动瞬间才刷新导致失败。</p>
     */
    public static final long REFRESH_AHEAD_MS = 12L * 60L * 60L * 1000L;

    private String name = "";
    /** 带连字符的 UUID */
    private String uuid = "";
    /** Minecraft 登录令牌（仅落盘，不打印） */
    private String accessToken = "";
    /** Microsoft refresh_token（仅落盘，不打印） */
    private String refreshToken = "";
    /** Xbox Live 用户 ID，用于 ${auth_xuid} */
    private String xuid = "";
    /** 登录所用的 Azure 应用 ID，刷新时沿用 */
    private String clientId = "";
    /** Minecraft 令牌到期时间（epoch millis） */
    private long notAfter = 0L;
    /** ${user_properties} 占位符，正版账户默认空对象 */
    private String userProperties = "{}";

    public MicrosoftAccount() {
    }

    public MicrosoftAccount(String name, String uuid, String accessToken, String refreshToken,
                            String xuid, String clientId, long notAfter) {
        this.name = name == null ? "" : name;
        this.uuid = uuid == null ? "" : uuid;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.refreshToken = refreshToken == null ? "" : refreshToken;
        this.xuid = xuid == null ? "" : xuid;
        this.clientId = clientId == null ? "" : clientId;
        this.notAfter = notAfter;
    }

    @Override
    public String getUsername() {
        return name;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String getUserType() {
        return "msa";
    }

    @Override
    public String getXuid() {
        return xuid;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public String getUserProperties() {
        return (userProperties == null || userProperties.isBlank()) ? "{}" : userProperties;
    }

    /** 已过期或距过期不足 12 小时时返回 true。 */
    @Override
    public boolean needsRefresh() {
        return notAfter <= System.currentTimeMillis() + REFRESH_AHEAD_MS;
    }

    public boolean isExpired() {
        return notAfter <= System.currentTimeMillis();
    }

    /** 使用 refresh_token 续期，成功后把新凭据写回自身；失败时自身状态完全不变。 */
    @Override
    public void refresh(MicrosoftAuthService service) throws AuthException {
        if (service == null) {
            throw new AuthException("no_service", "缺少登录服务，无法刷新凭据");
        }
        // 先取到完整的新账户再落地，保证刷新失败（抛异常）时不会破坏已有的 refresh_token / xuid / notAfter
        MicrosoftAccount renewed = service.refresh(this);
        copyFrom(renewed);
    }

    /**
     * 合并刷新结果字段。
     *
     * <p>安全约束：新值缺失时保留原值，避免刷新链路的残缺响应把已有 refresh_token、UUID、
     * 用户名或 user_properties 清空，导致账户需要重新登录。</p>
     */
    public void copyFrom(MicrosoftAccount other) {
        if (other == null) {
            return;
        }
        this.name = blankOr(other.name, this.name);
        this.uuid = blankOr(other.uuid, this.uuid);
        this.accessToken = blankOr(other.accessToken, this.accessToken);
        this.refreshToken = blankOr(other.refreshToken, this.refreshToken);
        this.xuid = blankOr(other.xuid, this.xuid);
        this.clientId = blankOr(other.clientId, this.clientId);
        this.userProperties = blankOr(other.userProperties, this.userProperties);
        if (other.notAfter > 0L) {
            this.notAfter = other.notAfter;
        }
    }

    private static String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** 到期时间（epoch millis）。 */
    public long getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(long notAfter) {
        this.notAfter = notAfter;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken == null ? "" : refreshToken;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid == null ? "" : uuid;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken == null ? "" : accessToken;
    }

    public void setXuid(String xuid) {
        this.xuid = xuid == null ? "" : xuid;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId;
    }

    public void setUserProperties(String userProperties) {
        this.userProperties = userProperties == null ? "" : userProperties;
    }

    /** 安全字符串：不含任何令牌。 */
    @Override
    public String toString() {
        return "MicrosoftAccount{name=" + name + ", uuid=" + uuid + ", hasRefreshToken="
                + (refreshToken != null && !refreshToken.isBlank()) + ", notAfter=" + notAfter + "}";
    }
}
