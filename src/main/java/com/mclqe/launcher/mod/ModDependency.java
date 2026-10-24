package com.mclqe.launcher.mod;

/**
 * Modrinth 版本依赖（接口 {@code versions[].dependencies[]} 元素）。
 *
 * <p>{@code dependencyType} 取值 {@code required} / {@code optional} / {@code incompatible} /
 * {@code embedded}。</p>
 */
public record ModDependency(String versionId, String projectId, String dependencyType) {

    /** 是否为必须安装的前置。 */
    public boolean required() {
        return "required".equalsIgnoreCase(dependencyType);
    }

    public String typeLabel() {
        if (dependencyType == null) {
            return "未知";
        }
        return switch (dependencyType.toLowerCase()) {
            case "required" -> "必需";
            case "optional" -> "可选";
            case "incompatible" -> "不兼容";
            case "embedded" -> "已内置";
            default -> dependencyType;
        };
    }
}
