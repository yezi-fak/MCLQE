package com.mclqe.launcher.auth;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 账户凭据落盘位置。
 *
 * <p>正版（Microsoft）账户：{@code D:\MCLQE\SA\STEV\accounts.json}</p>
 * <p>非正版（离线）账户：{@code D:\MCLQE\SA\offline.json}</p>
 *
 * <p>根目录可用系统属性 {@code -Dmclqe.storage.root=<目录>} 覆盖，默认 {@code D:\MCLQE}。</p>
 */
public final class AuthPaths {

    private static final String DEFAULT_ROOT = "D:\\MCLQE";

    private AuthPaths() {
    }

    /** 凭据根目录（默认 D:\MCLQE）。 */
    public static Path root() {
        String override = System.getProperty("mclqe.storage.root", "").trim();
        String base = override.isEmpty() ? DEFAULT_ROOT : override;
        return Paths.get(base).toAbsolutePath().normalize();
    }

    /** 正版账户目录：D:\MCLQE\SA\STEV */
    public static Path msaDir() {
        return root().resolve("SA").resolve("STEV");
    }

    /** 非正版（离线）账户目录：D:\MCLQE\SA */
    public static Path offlineDir() {
        return root().resolve("SA");
    }

    /** 正版账户文件：D:\MCLQE\SA\STEV\accounts.json */
    public static Path msaFile() {
        return msaDir().resolve("accounts.json");
    }

    /** 非正版账户文件：D:\MCLQE\SA\offline.json */
    public static Path offlineFile() {
        return offlineDir().resolve("offline.json");
    }
}
