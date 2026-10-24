package com.mclqe.launcher.auth;

/**
 * 启动器账户抽象：离线账户与 Microsoft 正版账户共用同一套参数拼接入口。
 *
 * <p>字段语义与 HMCL 的 {@code AuthAccount} 保持一致，其中 {@link #getUuid()} 返回
 * 带连字符的标准 UUID，供 {@code ${auth_uuid}} 占位符使用。</p>
 */
public interface AuthAccount {

    /** 玩家名（正版账户为 Minecraft 档案名）。 */
    String getUsername();

    /** 带连字符的 UUID（8-4-4-4-12）。 */
    String getUuid();

    /** 游戏登录令牌；离线账户固定为 "0"。 */
    String getAccessToken();

    /** 令牌类型：msa（正版） / legacy（离线）。 */
    String getUserType();

    /** Xbox Live 用户 ID，仅正版账户有值。 */
    default String getXuid() {
        return "";
    }

    /** 登录所用的 Azure 应用 client_id，仅正版账户有值。 */
    default String getClientId() {
        return "";
    }

    /** ${user_properties} 占位符内容，离线账户为空对象。 */
    default String getUserProperties() {
        return "{}";
    }

    /** 是否需要在启动前刷新凭据（离线账户永远为 false）。 */
    default boolean needsRefresh() {
        return false;
    }

    /**
     * 刷新凭据。离线账户为空实现；正版账户会用 refresh_token 重新走一遍
     * Xbox → XSTS → Minecraft 链路，并把新令牌写回自身。
     */
    default void refresh(MicrosoftAuthService service) throws AuthException {
        // 离线账户无需刷新
    }
}
