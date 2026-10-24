package com.mclqe.launcher.mod;

import java.util.List;

/**
 * Modrinth 项目详情（接口 {@code /project/{id}} 响应的精简视图）。
 */
public record ModProject(
        String id,
        String slug,
        String title,
        String description,
        String body,
        String iconUrl,
        long downloads,
        long follows,
        List<String> categories,
        List<String> gameVersions,
        String projectType) {
}
