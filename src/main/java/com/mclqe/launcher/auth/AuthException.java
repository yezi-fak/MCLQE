package com.mclqe.launcher.auth;

/**
 * 登录链路异常。
 *
 * <p>{@code code} 为协议层错误码（OAuth error 字符串、XErr 数值、http_xxx、network_error 等），
 * {@code message} 为可直接展示给用户的中文描述。</p>
 *
 * <p>约束：任何异常信息都不得携带 access_token / refresh_token / device_code 等敏感内容。</p>
 */
public class AuthException extends Exception {

    private final String code;

    public AuthException(String code, String message) {
        super(message);
        this.code = code == null ? "" : code;
    }

    public AuthException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? "" : code;
    }

    public String getCode() {
        return code;
    }
}
