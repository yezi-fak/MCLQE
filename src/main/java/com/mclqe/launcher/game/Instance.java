package com.mclqe.launcher.game;

import java.nio.file.Path;

/**
 * 启动器「实例」视图模型：每个 {@code <gameDir>/versions/<id>} 目录即一个实例。
 *
 * <p>实例与版本目录一一对应，额外附带元数据（显示名 / 版本隔离开关），
 * 元数据落盘在 {@code <versionDir>/mclqe-instance.json}，不会修改原版版本 JSON。</p>
 *
 * <p>版本隔离：开启后该实例的 mods / saves / 资源包等全部读写 {@code versions/<id>} 内部，
 * 与游戏根目录互不干扰；关闭时沿用游戏根目录下的公共内容目录（与原版行为一致）。</p>
 */
public record Instance(
        String gameDir,
        String id,
        String name,
        String type,
        String inheritsFrom,
        boolean isolated,
        boolean damaged,
        boolean missingJar,
        long sizeBytes,
        Path versionDir) {

    /** 展示名：自定义名优先，其次版本 id。 */
    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }

    /** 版本类型标签（release / snapshot / forge / fabric / custom ...）。 */
    public String typeLabel() {
        if (type == null || type.isBlank()) {
            return "未知";
        }
        return switch (type.toLowerCase()) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_alpha" -> "远古 Alpha";
            case "old_beta" -> "远古 Beta";
            default -> type;
        };
    }

    /** 状态说明：损坏 / 缺少 jar / 已安装。 */
    public String statusLabel() {
        if (damaged) {
            return "损坏";
        }
        if (missingJar) {
            return "缺少 .jar";
        }
        return "可启动";
    }

    public boolean healthy() {
        return !damaged && !missingJar;
    }

    /** 该实例的内容根目录（隔离时为版本目录，否则为游戏根目录）。 */
    public Path contentRoot() {
        return isolated ? versionDir : Path.of(gameDir);
    }

    /** 内容目录绝对路径，例如 {@code .../mods}。 */
    public Path contentDir(InstanceManager.ContentKind kind) {
        return contentRoot().resolve(kind.folder());
    }
}
