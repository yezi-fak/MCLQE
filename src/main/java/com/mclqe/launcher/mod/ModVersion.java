package com.mclqe.launcher.mod;

import java.util.List;

/**
 * Modrinth 模组版本（接口 {@code /project/{id}/version} 数组元素）。
 */
public record ModVersion(
        String id,
        String projectId,
        String name,
        String versionNumber,
        String versionType,
        List<String> gameVersions,
        List<String> loaders,
        String datePublished,
        long downloads,
        List<ModFile> files,
        List<ModDependency> dependencies) {

    /** 主文件；接口未标 primary 时取第一个文件，全空返回 {@code null}。 */
    public ModFile primaryFile() {
        if (files == null || files.isEmpty()) {
            return null;
        }
        for (ModFile file : files) {
            if (file.primary()) {
                return file;
            }
        }
        return files.get(0);
    }

    /** 必需前置依赖（不含可选 / 不兼容）。 */
    public List<ModDependency> requiredDependencies() {
        if (dependencies == null) {
            return List.of();
        }
        return dependencies.stream().filter(ModDependency::required).toList();
    }

    public boolean release() {
        return "release".equalsIgnoreCase(versionType);
    }

    /** 版本类型中文标签。 */
    public String typeLabel() {
        if (versionType == null) {
            return "未知";
        }
        return switch (versionType.toLowerCase()) {
            case "release" -> "正式版";
            case "beta" -> "测试版";
            case "alpha" -> "内测版";
            default -> versionType;
        };
    }

    /** 下拉展示文案。 */
    public String displayName() {
        String label = (name == null || name.isBlank()) ? versionNumber : name;
        return label + " [" + typeLabel() + "]";
    }
}
