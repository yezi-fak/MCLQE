package com.mclqe.launcher.mod;

import com.mclqe.launcher.game.Instance;
import com.mclqe.launcher.game.InstanceManager;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模组安装目标：实例本身 + 其 mods 目录 + 推断出的 MC 版本与加载器。
 *
 * <p>MC 版本优先取版本 JSON 的 {@code inheritsFrom}（Forge / NeoForge 版本 id 里
 * 常含构建号，不能直接取第一段数字），为空再从实例 id 中抽取 {@code x.y[.z]}；
 * 加载器按 id / 类型中的关键字识别。</p>
 */
public record ModTarget(Instance instance, Path modsDir, String gameVersion, String loader) {

    private static final Pattern MC_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");
    private static final String UNKNOWN = "未知";

    public static ModTarget of(Instance instance) {
        if (instance == null) {
            return null;
        }
        return new ModTarget(instance,
                instance.contentDir(InstanceManager.ContentKind.MODS),
                detectGameVersion(instance),
                detectLoader(instance));
    }

    /** 版本 JSON 的 inheritsFrom 为纯版本号时即 MC 版本。 */
    static String detectGameVersion(Instance instance) {
        String inherits = instance.inheritsFrom();
        if (inherits != null) {
            String trimmed = inherits.trim();
            if (MC_VERSION.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        String id = instance.id() == null ? "" : instance.id();
        Matcher matcher = MC_VERSION.matcher(id);
        if (matcher.find()) {
            return matcher.group();
        }
        return "";
    }

    /** 依据实例 id / 版本类型识别加载器，识别不到返回空串。 */
    static String detectLoader(Instance instance) {
        String id = (instance.id() == null ? "" : instance.id()).toLowerCase(Locale.ROOT);
        String type = (instance.type() == null ? "" : instance.type()).toLowerCase(Locale.ROOT);
        String combined = id + " " + type;
        if (combined.contains("neoforge")) {
            return "neoforge";
        }
        if (combined.contains("quilt")) {
            return "quilt";
        }
        if (combined.contains("fabric")) {
            return "fabric";
        }
        if (combined.contains("forge")) {
            return "forge";
        }
        return "";
    }

    public boolean hasGameVersion() {
        return gameVersion != null && !gameVersion.isBlank();
    }

    public boolean hasLoader() {
        return loader != null && !loader.isBlank();
    }

    /** 界面展示：实例名（MC 版本 / 加载器）。 */
    public String label() {
        return instance.displayName() + "（" + (hasGameVersion() ? gameVersion : UNKNOWN)
                + (hasLoader() ? " · " + loader : "") + "）";
    }

    /** 兼容性提示：两者都无法识别时提醒用户。 */
    public String missingHint() {
        if (hasGameVersion() && hasLoader()) {
            return null;
        }
        return "未从实例 " + instance.id() + " 识别到"
                + (hasGameVersion() ? "" : " MC 版本")
                + ((!hasGameVersion() && !hasLoader()) ? " 与" : "")
                + (hasLoader() ? "" : " 加载器")
                + "，将按全部版本显示";
    }
}
