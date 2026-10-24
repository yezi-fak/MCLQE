package com.mclqe.launcher.game;

import java.nio.file.Path;

/**
 * 游戏目录内可管理的子文件夹条目。
 *
 * <p>{@code versions} 下的版本文件夹会被展开为独立的条目（{@code versionDir = true}），
 * 名称形如 {@code versions/1.20.1}，以便在同一个列表中统一删除。</p>
 */
public record GameDirEntry(
        String name,
        Path path,
        boolean standard,
        boolean versionDir,
        String versionId,
        long fileCount,
        long sizeBytes,
        long lastModified) {

    /** 条目类型的中文说明。 */
    public String kindLabel() {
        if (versionDir) {
            return "已安装版本";
        }
        return standard ? "标准目录" : "自定义目录";
    }
}
