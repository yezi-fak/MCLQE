package com.mclqe.launcher.mod;

import java.util.List;

/**
 * Modrinth 搜索命中项（接口 {@code /search} 的 {@code hits[]} 元素）。
 */
public record ModHit(
        String projectId,
        String slug,
        String title,
        String description,
        String author,
        String iconUrl,
        long downloads,
        long follows,
        List<String> categories,
        List<String> versions,
        String dateModified,
        String latestVersion) {

    /** 详情 / 版本接口可直接使用的标识（优先 project_id，其次 slug）。 */
    public String identifier() {
        if (projectId != null && !projectId.isBlank()) {
            return projectId;
        }
        return slug == null ? "" : slug;
    }
}
