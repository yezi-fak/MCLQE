package com.mclqe.launcher.game;

import java.nio.file.Path;

/**
 * 本地游戏目录 {@code versions} 下扫描到的已安装版本。
 *
 * <p>状态分三档：JSON 与 jar 齐备为「已安装」；JSON 可读但缺同名 {@code .jar}（且非
 * 继承自父版本的 Forge / Fabric 等）为「未安装」；JSON 缺失或解析失败为「损坏」。</p>
 */
public record LocalVersion(
        String id,
        Path dir,
        Path json,
        boolean hasJar,
        boolean jsonValid,
        String type,
        String inheritsFrom,
        String mainClass,
        String error,
        long sizeBytes) {

    /** 版本类型；缺失时回退为 custom（第三方整合版本常见）。 */
    public String resolvedType() {
        return type == null || type.isBlank() ? "custom" : type;
    }

    /** 是否通过 inheritsFrom 继承父版本。 */
    public boolean inheriting() {
        return inheritsFrom != null && !inheritsFrom.isBlank();
    }

    /**
     * 损坏原因；版本文件齐全时返回 {@code null}。
     *
     * <p>只有 JSON 缺失或解析失败才算损坏。仅缺同名 {@code .jar} 属于「未安装」
     * （见 {@link #missingJar()}），不计入损坏。</p>
     */
    public String damageReason() {
        if (error != null && !error.isBlank()) {
            return error;
        }
        if (!jsonValid) {
            return "版本 JSON 无效";
        }
        return null;
    }

    /** 有版本描述、但没有本体 {@code .jar} 且不继承父版本：属于未安装完成的版本。 */
    public boolean missingJar() {
        return jsonValid && !hasJar && !inheriting();
    }

    /** 是否已完整安装：JSON 可读，且本体 jar 存在或由父版本提供。 */
    public boolean installedComplete() {
        return jsonValid && (hasJar || inheriting());
    }

    /** 是否为损坏版本（仅指 JSON 缺失或解析失败）。 */
    public boolean damaged() {
        return damageReason() != null;
    }

    public boolean healthy() {
        return !damaged();
    }
}
