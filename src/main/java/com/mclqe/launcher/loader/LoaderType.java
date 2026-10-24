package com.mclqe.launcher.loader;

/**
 * 模组加载器类型。
 *
 * <p>负责各加载器的展示名、版本号约定与 MC 版本支持判定，避免在 UI 与安装器里散落 if-else。</p>
 */
public enum LoaderType {

    FABRIC("Fabric", "fabric"),
    QUILT("Quilt", "quilt"),
    FORGE("Forge", "forge"),
    NEOFORGE("NeoForge", "neoforge");

    private final String displayName;
    private final String id;

    LoaderType(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String displayName() {
        return displayName;
    }

    /** 小写标识，用于实例 id 关键字判定与日志。 */
    public String id() {
        return id;
    }

    /**
     * 该加载器是否支持指定 MC 版本。
     *
     * <p>规则：NeoForge 自 MC 1.20.2 起才有独立发行版（1.20.1 及更早用 Forge 替代）；
     * Quilt/Fabric 无版本上限；Forge 对 1.13 以下的老版本仍可安装，故不做下限限制。</p>
     */
    public boolean supports(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return true;
        }
        if (this == NEOFORGE) {
            return compareMc(mcVersion, "1.20.2") >= 0;
        }
        return true;
    }

    /** 不支持时的提示文案，供 UI 置灰说明。 */
    public String unsupportedHint(String mcVersion) {
        if (this == NEOFORGE && !supports(mcVersion)) {
            return "NeoForge 从 MC 1.20.2 起才提供，1.20.1 及更早请用 Forge";
        }
        return "";
    }

    /** 按 MC 版本字符串比较（仅比较数字段，缺失段按 0 处理）。 */
    static int compareMc(String left, String right) {
        String[] a = left.split("[^0-9]+");
        String[] b = right.split("[^0-9]+");
        int size = Math.max(a.length, b.length);
        for (int i = 0; i < size; i++) {
            int x = i < a.length && !a[i].isBlank() ? parseIntSafe(a[i]) : 0;
            int y = i < b.length && !b[i].isBlank() ? parseIntSafe(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
