package com.mclqe.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microsoft 正版登录（OAuth 2.0 设备码流程）。
 *
 * <p>实现参照 HMCL 的 {@code MicrosoftService} / {@code OAuth.MICROSOFT} 与
 * Prism Launcher 的 {@code MSADeviceCodeStep}：</p>
 * <ol>
 *   <li>POST consumers/oauth2/v2.0/devicecode 申请设备码（client_id + scope）；</li>
 *   <li>按 RFC 8628 轮询 token 端点：authorization_pending 继续、slow_down 间隔 +5s、
 *       网络抖动则间隔翻倍；</li>
 *   <li>拿到 MSA access_token 后依次完成 Xbox Live 用户认证 → XSTS 授权 →
 *       Minecraft 登录 → 授权校验 → 档案获取；</li>
 *   <li>refresh_token 走 token 端点刷新后重走同一条链路。</li>
 * </ol>
 *
 * <p>线程模型：所有方法均为阻塞式 IO，必须在后台线程调用；{@link #cancel()} 可从任意线程
 * 中断轮询，用于用户取消登录。</p>
 *
 * <p>安全约束：本类不打印、不记录任何 token；异常信息只包含错误码与中文描述。</p>
 */
public class MicrosoftAuthService {

    /** Prism Launcher 使用的公开 client_id，可在设置页覆盖为用户自行注册的 Azure 应用 ID。 */
    public static final String DEFAULT_CLIENT_ID = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";

    /** 与 HMCL 一致：Xbox Live 登录 + 离线访问（离线访问才能拿到 refresh_token）。 */
    public static final String SCOPE = "XboxLive.signin offline_access";

    private static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_USER_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String MC_LICENSE_URL = "https://api.minecraftservices.com/entitlements/license";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    /**
     * 设备码整体等待上限（秒）。
     *
     * <p>Microsoft 的 expires_in 通常为 900 秒，服务端异常时可能返回更大值；
     * 本地等待时间取 min(expires_in, 900) 并保底 60 秒，避免线程被异常值长时间占用。</p>
     */
    private static final long DEVICE_CODE_MAX_WAIT_SECONDS = 900L;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String USER_AGENT = "MCLQE-Launcher/1.0.0";

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 阶段提示回调（在调用线程触发，UI 侧需自行切回 JavaFX 线程）。 */
    public interface StageListener {
        void onStage(String message);
    }

    /** 设备码信息（仅用于界面展示与轮询，deviceCode 不参与日志输出）。 */
    public static class DeviceCode {
        private final String deviceCode;
        private final String userCode;
        private final String verificationUri;
        private final int expiresIn;
        private final int interval;
        private final String message;

        public DeviceCode(String deviceCode, String userCode, String verificationUri,
                          int expiresIn, int interval, String message) {
            this.deviceCode = deviceCode == null ? "" : deviceCode;
            this.userCode = userCode == null ? "" : userCode;
            this.verificationUri = verificationUri == null ? "" : verificationUri;
            this.expiresIn = expiresIn <= 0 ? 900 : expiresIn;
            this.interval = interval <= 0 ? 5 : interval;
            this.message = message == null ? "" : message;
        }

        public String getDeviceCode() {
            return deviceCode;
        }

        public String getUserCode() {
            return userCode;
        }

        public String getVerificationUri() {
            return verificationUri;
        }

        public int getExpiresIn() {
            return expiresIn;
        }

        public int getInterval() {
            return interval;
        }

        public String getMessage() {
            return message;
        }
    }

    // ------------------------------------------------------------------ 取消

    /** 请求取消当前轮询（线程安全，可从 JavaFX 线程调用）。 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // ------------------------------------------------------------------ 设备码

    /**
     * 申请设备码。
     *
     * @param clientId Azure 应用 ID；为空时使用 {@link #DEFAULT_CLIENT_ID}
     */
    public DeviceCode requestDeviceCode(String clientId) throws AuthException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", normalizeClientId(clientId));
        form.put("scope", SCOPE);

        HttpResult result = sendFormWithRetry(DEVICE_CODE_URL, form, null);
        if (result.code != 200) {
            throw new AuthException("devicecode_failed",
                    "申请设备码失败（HTTP " + result.code + "）：" + errorDescription(parse(result.body)));
        }
        JsonObject json = parse(result.body);
        String deviceCode = str(json, "device_code");
        String userCode = str(json, "user_code");
        String verificationUri = str(json, "verification_uri");
        if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
            throw new AuthException("devicecode_failed", "设备码响应缺少必要字段（device_code / user_code / verification_uri）");
        }
        int expiresIn = (int) num(json, "expires_in", 900);
        int interval = (int) num(json, "interval", 5);
        return new DeviceCode(deviceCode, userCode, verificationUri, expiresIn, interval, str(json, "message"));
    }

    // ------------------------------------------------------------------ 轮询登录

    /**
     * 按设备码轮询授权结果，成功后自动完成 Xbox → XSTS → Minecraft 全链路。
     *
     * @return 已完成登录的微软账户（已含 Minecraft access token、uuid、xuid 等）
     */
    public MicrosoftAccount login(DeviceCode deviceCode, String clientId, StageListener listener)
            throws AuthException {
        String cid = normalizeClientId(clientId);
        notify(listener, "等待在浏览器中完成 Microsoft 授权 ...");

        // 设备码整体等待上限：min(expires_in, 900) 秒，同时保留 60 秒下限防止服务端返回异常值
        long waitSeconds = Math.max(60L, Math.min(deviceCode.getExpiresIn(), DEVICE_CODE_MAX_WAIT_SECONDS));
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        long interval = Math.max(1, deviceCode.getInterval()) * 1000L;

        while (true) {
            if (cancelled.get()) {
                throw new AuthException("cancelled", "已取消登录");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AuthException("expired_token", "设备码已过期，请重新发起登录");
            }
            // 分片休眠，保证取消可以及时生效
            if (!sleepInterruptible(interval)) {
                throw new AuthException("cancelled", "已取消登录");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AuthException("expired_token", "设备码已过期，请重新发起登录");
            }

            HttpResult result;
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", cid);
                form.put("grant_type", DEVICE_CODE_GRANT);
                // 严格按 RFC 8628 / Microsoft 文档只下发 device_code，避免多余参数触发 invalid_request
                form.put("device_code", deviceCode.getDeviceCode());
                result = sendForm(TOKEN_URL, form);
            } catch (AuthException e) {
                // 连接异常：按 Prism 的策略把轮询间隔翻倍后继续，直到设备码过期
                interval = Math.min(interval * 2L, 30_000L);
                notify(listener, "网络不稳定，稍后用更长间隔重试 ...");
                continue;
            }

            if (result.code == 200) {
                JsonObject token = parse(result.body);
                String msaToken = str(token, "access_token");
                String refreshToken = str(token, "refresh_token");
                if (msaToken.isBlank()) {
                    throw new AuthException("invalid_response", "Microsoft 未返回 access_token");
                }
                return completeChain(msaToken, refreshToken, cid, listener);
            }

            // 429 / 5xx 属服务端临时故障：按 RFC 8628 拉长间隔继续轮询，绝不直接中断登录
            if (result.code == 429 || result.code >= 500) {
                interval = Math.min(interval + 5_000L, 60_000L);
                notify(listener, "Microsoft 服务暂时不可用（HTTP " + result.code + "），稍后自动重试 ...");
                continue;
            }
            // 请求被网关截断/超时等无响应体情况，同样退避重试
            if (result.body == null || result.body.isBlank()) {
                interval = Math.min(interval + 5_000L, 60_000L);
                notify(listener, "授权服务返回空响应（HTTP " + result.code + "），稍后自动重试 ...");
                continue;
            }

            JsonObject error = parse(result.body);
            String code = str(error, "error");
            switch (code) {
                case "authorization_pending" -> {
                    // 用户尚未完成授权，继续轮询
                }
                case "slow_down" -> interval = Math.min(interval + 5_000L, 60_000L);
                case "expired_token" -> throw new AuthException("expired_token", "设备码已过期，请重新发起登录");
                case "authorization_declined" -> throw new AuthException("authorization_declined",
                        "授权已被拒绝，请重新登录");
                case "bad_verification_code" -> throw new AuthException("bad_verification_code",
                        "验证码无效，请重新发起登录");
                case "invalid_grant" -> throw new AuthException("invalid_grant",
                        "授权已失效，请重新发起登录");
                default -> throw new AuthException(
                        code.isBlank() ? "http_" + result.code : code,
                        "授权失败：" + errorDescription(error));
            }
        }
    }

    // ------------------------------------------------------------------ 刷新

    /** 用 refresh_token 刷新账户凭据（令牌到期前 12 小时内由界面调用；游戏运行中不得调用）。 */
    public MicrosoftAccount refresh(MicrosoftAccount account) throws AuthException {
        return refresh(account, null);
    }

    public MicrosoftAccount refresh(MicrosoftAccount account, StageListener listener) throws AuthException {
        if (account == null) {
            throw new AuthException("no_account", "没有可刷新的正版账户");
        }
        if (account.getRefreshToken() == null || account.getRefreshToken().isBlank()) {
            throw new AuthException("no_refresh_token", "登录已失效，请重新登录");
        }
        String cid = normalizeClientId(account.getClientId());
        notify(listener, "正在刷新 Microsoft 登录凭据 ...");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", cid);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", account.getRefreshToken());
        form.put("scope", SCOPE);

        HttpResult result = sendFormWithRetry(TOKEN_URL, form, listener);
        if (result.code != 200) {
            JsonObject error = parse(result.body);
            String code = str(error, "error");
            if ("invalid_grant".equals(code) || "interaction_required".equals(code)
                    || "invalid_request".equals(code) || "unauthorized_client".equals(code)) {
                throw new AuthException("invalid_grant", "登录已失效，请重新登录");
            }
            throw new AuthException(code.isBlank() ? "http_" + result.code : code,
                    "刷新登录凭据失败：" + errorDescription(error));
        }
        JsonObject token = parse(result.body);
        String msaToken = str(token, "access_token");
        String refreshToken = str(token, "refresh_token");
        if (msaToken.isBlank()) {
            throw new AuthException("invalid_response", "Microsoft 未返回 access_token");
        }
        if (refreshToken.isBlank()) {
            refreshToken = account.getRefreshToken();
        }
        return completeChain(msaToken, refreshToken, cid, listener);
    }

    // ------------------------------------------------------------------ Xbox / XSTS / Minecraft

    private MicrosoftAccount completeChain(String msaAccessToken, String refreshToken,
                                           String clientId, StageListener listener) throws AuthException {
        // ---- 1) Xbox Live 用户认证 ----
        notify(listener, "正在验证 Xbox Live 账户 ...");
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaAccessToken);

        JsonObject xboxBody = new JsonObject();
        xboxBody.add("Properties", props);
        xboxBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xboxBody.addProperty("TokenType", "JWT");

        HttpResult xboxResult = sendJson(XBOX_USER_AUTH_URL, xboxBody, null);
        if (xboxResult.code != 200) {
            throw new AuthException("xbox_auth_failed",
                    "Xbox Live 用户认证失败（HTTP " + xboxResult.code + "）");
        }
        JsonObject xboxJson = parse(xboxResult.body);
        String xblToken = str(xboxJson, "Token");
        String uhs = firstUhs(xboxJson);
        if (xblToken.isBlank() || uhs.isBlank()) {
            throw new AuthException("xbox_auth_failed", "Xbox Live 返回数据缺少 Token 或 uhs");
        }

        // ---- 2) XSTS 授权 ----
        notify(listener, "正在获取 XSTS 授权令牌 ...");
        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        xstsProps.add("UserTokens", tokens);

        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");

        HttpResult xstsResult = sendJson(XSTS_URL, xstsBody, null);
        if (xstsResult.code == 401) {
            JsonObject error = parse(xstsResult.body);
            throw xstsError(xstsResult.body, error);
        }
        if (xstsResult.code != 200) {
            throw new AuthException("xsts_failed", "XSTS 授权失败（HTTP " + xstsResult.code + "）");
        }
        JsonObject xstsJson = parse(xstsResult.body);
        String xstsToken = str(xstsJson, "Token");
        String xstsUhs = firstUhs(xstsJson);
        // XUID 是 XSTS DisplayClaims 里的 xid（Xbox 用户 ID）；取不到时退回 uhs 保证字段非空
        String xuid = firstClaim(xstsJson, "xid");
        if (xstsToken.isBlank()) {
            throw new AuthException("xsts_failed", "XSTS 返回数据缺少 Token");
        }
        // uhs（Xbox User Hash）一致性校验：XSTS 与 Xbox Live 用户认证阶段返回的 uhs 必须完全一致，
        // 否则说明服务端返回了其他用户的授权（对标 Prism XboxAuthorizationStep 的 user hash 校验）。
        if (xstsUhs.isBlank() || !xstsUhs.equals(uhs)) {
            throw new AuthException("xsts_failed",
                    "XSTS 返回的用户标识与 Xbox Live 阶段不一致（uhs 校验失败），出于安全考虑已中止登录，请重试");
        }

        // ---- 3) Minecraft 登录 ----
        notify(listener, "正在登录 Minecraft 服务 ...");
        JsonObject loginBody = new JsonObject();
        loginBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        HttpResult loginResult = sendJson(MC_LOGIN_URL, loginBody, null);
        if (loginResult.code != 200) {
            if (loginResult.code == 401) {
                throw new AuthException("mc_login_failed", "Minecraft 登录被拒绝：该账户没有 Minecraft Java 版");
            }
            throw new AuthException("mc_login_failed", "Minecraft 登录失败（HTTP " + loginResult.code + "）");
        }
        JsonObject loginJson = parse(loginResult.body);
        String mcToken = str(loginJson, "access_token");
        if (mcToken.isBlank()) {
            throw new AuthException("mc_login_failed", "Minecraft 登录响应缺少 access_token");
        }
        long expiresIn = (long) num(loginJson, "expires_in", 86_400);
        long notAfter = System.currentTimeMillis() + expiresIn * 1000L;

        // ---- 4) 授权校验（mcstore） ----
        notify(listener, "正在校验游戏授权 ...");
        HttpResult storeResult = sendGetWithRetry(MC_ENTITLEMENTS_URL, mcToken);
        if (storeResult.code != 200) {
            throw new AuthException("no_entitlement",
                    "该账户没有 Minecraft Java 版授权（未购买或授权校验失败，HTTP " + storeResult.code + "）");
        }
        if (!hasMinecraftEntitlement(parse(storeResult.body))) {
            throw new AuthException("no_entitlement", "该账户未购买 Minecraft Java 版");
        }

        // ---- 5) 游戏档案 ----
        notify(listener, "正在读取 Minecraft 游戏档案 ...");
        HttpResult profileResult = sendGetWithRetry(MC_PROFILE_URL, mcToken);
        String profileId;
        String profileName;
        if (profileResult.code == 200) {
            JsonObject profile = parse(profileResult.body);
            profileId = formatUuid(str(profile, "id"));
            profileName = str(profile, "name");
        } else if (profileResult.code == 404) {
            throw profileMissingError(mcToken);
        } else {
            throw new AuthException("profile_failed",
                    "读取 Minecraft 游戏档案失败（HTTP " + profileResult.code + "）");
        }
        if (profileName.isBlank() || profileId.isBlank()) {
            throw new AuthException("profile_failed", "Minecraft 档案数据缺少 id 或 name");
        }

        MicrosoftAccount account = new MicrosoftAccount();
        account.setName(profileName);
        account.setUuid(profileId);
        account.setAccessToken(mcToken);
        account.setRefreshToken(refreshToken == null ? "" : refreshToken);
        account.setXuid(xuid.isBlank() ? uhs : xuid);
        account.setClientId(clientId);
        account.setNotAfter(notAfter);
        account.setUserProperties("{}");
        notify(listener, "登录完成：" + profileName);
        return account;
    }

    /** profile 404：区分「有 Java 版授权但未创建档案」与「无 Java 版授权」。 */
    private AuthException profileMissingError(String mcToken) {
        HttpResult license;
        try {
            license = sendGetWithRetry(MC_LICENSE_URL, mcToken);
        } catch (AuthException e) {
            return new AuthException("no_profile", "该账户没有 Minecraft Java 版游戏档案（可能未购买 Java 版）");
        }
        if (license.code == 200 && hasItems(parse(license.body))) {
            return new AuthException("profile_missing",
                    "该账户拥有 Minecraft Java 版但尚未创建游戏档案，请先前往 minecraft.net 创建档案后重试");
        }
        return new AuthException("no_profile", "该账户没有 Minecraft Java 版游戏档案（可能未购买 Java 版）");
    }

    /**
     * XSTS 401：按 XErr 映射中文提示。
     *
     * <p>码表与 Prism Launcher XboxAuthorizationStep::processSTSError 完全对齐，
     * 每条提示都给出可操作的处理指引，未知码保留兜底提示并回显原始 XErr 数值。</p>
     */
    private AuthException xstsError(String body, JsonObject error) {
        long xerr = (long) num(error, "XErr", 0);
        String redirect = str(error, "Redirect");
        String suffix = redirect.isBlank() ? "" : "（" + redirect + "）";
        // 注意：XErr 数值均超过 int 上限（2148916227 > 2147483647），必须用 long 常量比较，不能写成 int switch
        if (xerr == 2148916233L) {
            return new AuthException("xerr_2148916233",
                    "该 Microsoft 账户尚未创建 Xbox 档案（从未登录过 Xbox Live）。"
                            + "请先前往 https://www.minecraft.net/en-us/store/minecraft-java-edition 购买或"
                            + "访问 https://start.ui.xboxlive.com 创建 Xbox 档案后重试" + suffix);
        }
        if (xerr == 2148916235L) {
            return new AuthException("xerr_2148916235",
                    "该账户所在国家或地区已被 Xbox Live 屏蔽，无法登录 Minecraft Java 版。"
                            + "请更换支持 Xbox Live 的国家/地区账户或网络环境后重试" + suffix);
        }
        if (xerr == 2148916238L) {
            return new AuthException("xerr_2148916238",
                    "该账户为未成年人账户且未加入 Microsoft 家庭组。"
                            + "请家长按 https://help.minecraft.net/hc/en-us/articles/4408968616077 完成家庭组绑定后重试" + suffix);
        }
        if (xerr == 2148916234L) {
            return new AuthException("xerr_2148916234",
                    "该账户尚未接受 Xbox 服务条款（Xbox Terms of Service）。"
                            + "请登录 https://www.xbox.com 接受相关条款后重新登录" + suffix);
        }
        if (xerr == 2148916236L) {
            return new AuthException("xerr_2148916236",
                    "该账户需要完成年龄验证才能登录 Xbox Live。"
                            + "请先登录 https://login.live.com/login.srf 完成年龄验证后重试" + suffix);
        }
        if (xerr == 2148916237L) {
            return new AuthException("xerr_2148916237",
                    "该账户的游戏时长已用尽，已被 Xbox 限制登录。"
                            + "请在家长控制 / 屏幕时间设置中调整该账户的时长限制后重试" + suffix);
        }
        if (xerr == 2148916227L) {
            return new AuthException("xerr_2148916227",
                    "该账户因违反 Xbox 社区准则已被 Xbox 封禁，无法使用。"
                            + "请前往 https://enforcement.xbox.com 查看封禁详情并申诉" + suffix);
        }
        if (xerr == 2148916229L) {
            return new AuthException("xerr_2148916229",
                    "该账户当前受家长控制限制，监护人尚未授予在线游戏权限。"
                            + "请监护人登录 https://account.microsoft.com/family/ 修改该账户权限后重试" + suffix);
        }
        return new AuthException("xsts_failed",
                "XSTS 授权失败，返回了未知错误码" + (xerr == 0 ? "" : "（XErr " + xerr + "）")
                        + "，请稍后重试；若持续出现，请检查账户状态或重新登录" + suffix);
    }

    private boolean hasMinecraftEntitlement(JsonObject json) {
        JsonElement items = json.get("items");
        if (items == null || !items.isJsonArray()) {
            return false;
        }
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = str(element.getAsJsonObject(), "name");
            if ("game_minecraft".equalsIgnoreCase(name) || "product_minecraft".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasItems(JsonObject json) {
        JsonElement items = json.get("items");
        return items != null && items.isJsonArray() && !items.getAsJsonArray().isEmpty();
    }

    // ------------------------------------------------------------------ HTTP

    private static final class HttpResult {
        private final int code;
        private final String body;

        private HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private HttpResult sendForm(String url, Map<String, String> form) throws AuthException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return send(url, "POST", sb.toString(), "application/x-www-form-urlencoded; charset=utf-8", null);
    }

    /**
     * 表单端点（devicecode / token）的有限重试：429、5xx 与网络异常最多额外重试两次。
     *
     * <p>设备码轮询本身有长时间的退避循环，这里只服务于「只发一次」的请求，
     * 避免一次网络抖动就让整条登录失败。</p>
     */
    private HttpResult sendFormWithRetry(String url, Map<String, String> form, StageListener listener)
            throws AuthException {
        AuthException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                notify(listener, "Microsoft 服务暂时不可用，正在重试（第 " + attempt + " 次）...");
                try {
                    Thread.sleep(1_000L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AuthException("cancelled", "已取消登录");
                }
                if (cancelled.get()) {
                    throw new AuthException("cancelled", "已取消登录");
                }
            }
            try {
                HttpResult result = sendForm(url, form);
                if (result.code == 429 || result.code >= 500) {
                    last = new AuthException("http_" + result.code,
                            "Microsoft 服务暂时不可用（HTTP " + result.code + "）");
                    continue;
                }
                return result;
            } catch (AuthException e) {
                if (!"network_error".equals(e.getCode())) {
                    throw e;
                }
                last = e;
            }
        }
        throw last == null
                ? new AuthException("network_error", "连接 Microsoft 登录服务失败，请检查网络后重试")
                : last;
    }

    private HttpResult sendJson(String url, JsonObject body, Map<String, String> headers)
            throws AuthException {
        return send(url, "POST", body.toString(), "application/json; charset=utf-8", headers);
    }

    private HttpResult sendGet(String url, String bearerToken) throws AuthException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + bearerToken);
        return send(url, "GET", null, null, headers);
    }

    /** GET 端点（entitlements / profile）的有限重试：429、5xx、网络异常各重试两次。 */
    private HttpResult sendGetWithRetry(String url, String bearerToken) throws AuthException {
        AuthException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AuthException("cancelled", "已取消登录");
                }
                if (cancelled.get()) {
                    throw new AuthException("cancelled", "已取消登录");
                }
            }
            try {
                HttpResult result = sendGet(url, bearerToken);
                if (result.code == 429 || result.code >= 500) {
                    last = new AuthException("http_" + result.code,
                            "Minecraft 服务暂时不可用（HTTP " + result.code + "）");
                    continue;
                }
                return result;
            } catch (AuthException e) {
                if (!"network_error".equals(e.getCode())) {
                    throw e;
                }
                last = e;
            }
        }
        throw last == null
                ? new AuthException("network_error", "连接 Minecraft 服务失败，请检查网络后重试")
                : last;
    }

    private HttpResult send(String url, String method, String body, String contentType,
                            Map<String, String> headers) throws AuthException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            return new HttpResult(code, text);
        } catch (IOException e) {
            // 只暴露类别，不回显 URL 之外的信息，避免任何凭据进入日志
            throw new AuthException("network_error", "网络请求失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）"));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream(4096)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ JSON 工具

    private static JsonObject parse(String text) {
        try {
            if (text == null || text.isBlank()) {
                return new JsonObject();
            }
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject json, String key) {
        if (json == null) {
            return "";
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static double num(JsonObject json, String key, double fallback) {
        if (json == null) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstUhs(JsonObject json) {
        return firstClaim(json, "uhs");
    }

    /** 读取 DisplayClaims.xui[0] 中的指定声明（uhs / xid 等）。 */
    private static String firstClaim(JsonObject json, String name) {
        JsonElement claims = json == null ? null : json.get("DisplayClaims");
        if (claims == null || !claims.isJsonObject()) {
            return "";
        }
        JsonElement xui = claims.getAsJsonObject().get("xui");
        if (xui == null || !xui.isJsonArray() || xui.getAsJsonArray().isEmpty()) {
            return "";
        }
        JsonElement first = xui.getAsJsonArray().get(0);
        return first.isJsonObject() ? str(first.getAsJsonObject(), name) : "";
    }

    private static String errorDescription(JsonObject error) {
        String description = str(error, "error_description");
        String code = str(error, "error");
        if (!description.isBlank()) {
            // OAuth 描述里可能带换行，压成一行便于界面展示
            return description.replaceAll("\\s+", " ").trim();
        }
        return code.isBlank() ? "未知错误" : code;
    }

    public static String normalizeClientId(String clientId) {
        return (clientId == null || clientId.isBlank()) ? DEFAULT_CLIENT_ID : clientId.trim();
    }

    /** 32 位无连字符 UUID → 标准带连字符格式；其他格式原样返回。 */
    public static String formatUuid(String id) {
        if (id == null) {
            return "";
        }
        String raw = id.replace("-", "");
        if (raw.length() != 32) {
            return id;
        }
        return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
    }

    private static void notify(StageListener listener, String message) {
        if (listener != null) {
            try {
                listener.onStage(message);
            } catch (Exception ignored) {
                // 界面回调异常不影响登录流程
            }
        }
    }

    /** 分片休眠；返回 false 表示被取消。 */
    private boolean sleepInterruptible(long millis) {
        long remain = millis;
        while (remain > 0) {
            if (cancelled.get()) {
                return false;
            }
            long slice = Math.min(remain, 200L);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remain -= slice;
        }
        return !cancelled.get();
    }
}
