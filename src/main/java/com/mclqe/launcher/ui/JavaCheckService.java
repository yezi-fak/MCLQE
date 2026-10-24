package com.mclqe.launcher.ui;

import com.mclqe.launcher.config.ConfigManager;
import com.mclqe.launcher.config.LauncherConfig;
import com.mclqe.launcher.game.GamePaths;
import com.mclqe.launcher.game.JavaDetector;
import com.mclqe.launcher.game.JavaRequirement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时 Java 环境检查服务。
 *
 * <p>职责（全部为纯逻辑，界面与浏览器打开动作由调用方完成）：
 * <ol>
 *   <li>解析当前版本所需的 Java 主版本（{@link JavaRequirement}）；</li>
 *   <li>后台线程探测本机 Java 运行时（{@link JavaDetector}，不阻塞 UI）；</li>
 *   <li>结合「手动指定 javaPath」与「自动检测」给出结论：正常 / 未安装 / 版本过低 / 版本过高；</li>
 *   <li>按防打扰策略判定本次是否需要提示，并在提示后写入抑制窗口（默认 24 小时）。</li>
 * </ol>
 * 自动化验证开关：
 * <ul>
 *   <li>{@code -Dmclqe.javacheck.force=missing|tooLow|tooHigh|ok}：强制指定检测结论（截图自验用）；</li>
 *   <li>{@code -Dmclqe.javacheck.noBrowse=true}：只打印下载地址，不真正打开浏览器（截图自验用）。</li>
 * </ul>
 */
public final class JavaCheckService {

    /** 检测结论 */
    public enum Conclusion {
        /** 环境正常，无需提示 */
        OK,
        /** 本机未检测到任何可用 Java */
        MISSING,
        /** 检测到的 Java 主版本低于要求 */
        TOO_LOW,
        /** 检测到的 Java 主版本高于要求（旧版本游戏用高版本 Java 会崩溃） */
        TOO_HIGH
    }

    /** 一次检查的完整结果 */
    public static final class Result {
        private final Conclusion conclusion;
        private final JavaRequirement requirement;
        private final List<JavaDetector.JavaInfo> detected;
        private final JavaDetector.JavaInfo matched;
        private final boolean promptNeeded;
        private final String suppressReason;
        private final String message;
        private final String detail;

        Result(Conclusion conclusion, JavaRequirement requirement, List<JavaDetector.JavaInfo> detected,
               JavaDetector.JavaInfo matched, boolean promptNeeded, String suppressReason,
               String message, String detail) {
            this.conclusion = conclusion;
            this.requirement = requirement;
            this.detected = detected;
            this.matched = matched;
            this.promptNeeded = promptNeeded;
            this.suppressReason = suppressReason;
            this.message = message;
            this.detail = detail;
        }

        public Conclusion conclusion() {
            return conclusion;
        }

        public JavaRequirement requirement() {
            return requirement;
        }

        public List<JavaDetector.JavaInfo> detected() {
            return detected;
        }

        /** 与要求匹配的运行时（正常时为选中项，异常时为可参考的最接近项），可能为 null */
        public JavaDetector.JavaInfo matched() {
            return matched;
        }

        public boolean promptNeeded() {
            return promptNeeded;
        }

        /** 被抑制时给出原因（便于日志排查） */
        public String suppressReason() {
            return suppressReason;
        }

        public String message() {
            return message;
        }

        public String detail() {
            return detail;
        }

        public String downloadUrl() {
            return requirement == null ? "" : requirement.downloadUrl();
        }

        public String backupDownloadUrl() {
            return requirement == null ? "" : requirement.backupDownloadUrl();
        }
    }

    private final LauncherConfig config;
    private final ConfigManager configManager;

    public JavaCheckService(LauncherConfig config, ConfigManager configManager) {
        this.config = config;
        this.configManager = configManager;
    }

    // ------------------------------------------------------------------ 自动化开关

    /** 强制结论（截图自验用）；返回 null 表示按真实检测结果判定。 */
    public static String forcedConclusion() {
        String value = System.getProperty("mclqe.javacheck.force", "").trim().toLowerCase(java.util.Locale.ROOT);
        // 只接受三个合法模拟项；其余取值（含命令行占位符如 "real"）一律按真实检测处理
        if ("missing".equals(value) || "toolow".equals(value) || "toohigh".equals(value)) {
            return value;
        }
        return null;
    }

    /** 只打印下载地址不打开浏览器（截图自验用）。 */
    public static boolean browseDisabled() {
        return Boolean.getBoolean("mclqe.javacheck.noBrowse");
    }

    // ------------------------------------------------------------------ 检查

    /**
     * 在后台线程执行检查，结果回调到调用方（回调为工作线程，调用方需自行切回 UI 线程）。
     */
    public void runAsync(String versionId, GamePaths paths, Callback callback) {
        Thread thread = new Thread(() -> {
            Result result;
            try {
                result = check(versionId, paths, true);
            } catch (RuntimeException e) {
                return;
            }
            callback.onResult(result);
        }, "mclqe-java-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** 同步执行一次检查（不做任何 UI 操作）。 */
    public Result check(String versionId, GamePaths paths) {
        return check(versionId, paths, true);
    }

    /**
     * 同步执行一次检查。
     *
     * @param honorForce false 表示忽略 {@code -Dmclqe.javacheck.force}（用户点击「重新检测」时走真实检测）
     */
    public Result check(String versionId, GamePaths paths, boolean honorForce) {
        String id = versionId == null ? "" : versionId.trim();
        Path versionJson = null;
        if (paths != null && !id.isEmpty()) {
            try {
                versionJson = paths.versionJson(id);
            } catch (RuntimeException ignored) {
                versionJson = null;
            }
        }
        JavaRequirement requirement = JavaRequirement.resolve(id, versionJson);

        if (!config.isJavaCheckEnabled()) {
            return new Result(Conclusion.OK, requirement, List.of(), null, false,
                    "启动检测已在设置中关闭", "启动检测已关闭", "");
        }

        List<JavaDetector.JavaInfo> detected = JavaDetector.detectAll();
        String forced = honorForce ? forcedConclusion() : null;
        if ("missing".equals(forced)) {
            detected = new ArrayList<>();
        } else if ("toolow".equals(forced)) {
            detected = filterBelow(detected, requirement.requiredMajor());
        } else if ("toohigh".equals(forced)) {
            detected = filterAtLeast(detected, requirement.requiredMajor() + 1);
        }

        if (forced == null && !id.isEmpty() && config.isJavaCheckDontAskAgain()) {
            return new Result(Conclusion.OK, requirement, detected, JavaDetector.pickBest(detected, requirement.requiredMajor()),
                    false, "用户已选择永久不再提示", "已永久关闭 Java 缺失提示", "");
        }

        // 同机多版本共存：先把手动指定的 java.exe 并入候选集合，再按目标主版本择优。
        // 手动指定仅在「版本满足要求」时拥有最高优先级，避免单一 javaPath 命中高版本 JDK 就误报 TOO_HIGH。
        String manualPath = config.getJavaPath() == null ? "" : config.getJavaPath().trim();
        JavaDetector.JavaInfo manual = null;
        boolean manualInvalid = false;
        if (forced == null && !manualPath.isEmpty()) {
            try {
                manual = JavaDetector.probeExecutable(Paths.get(manualPath));
            } catch (RuntimeException e) {
                manual = null;
            }
            if (manual == null) {
                manualInvalid = true;
            } else {
                mergeCandidate(detected, manual);
                sortDetected(detected);
            }
        }

        int required = requirement.requiredMajor();
        JavaDetector.JavaInfo best = null;
        if (manual != null && manual.getMajorVersion() == required && JavaDetector.isArchCompatible(manual)) {
            best = manual;
        }
        if (best == null) {
            best = JavaDetector.pickBest(detected, required);
        }
        if (best != null) {
            String detail;
            if (manual != null && !sameExecutable(manual, best)) {
                detail = "手动指定的 " + manual.getVersion() + "（" + manual.getHome()
                        + "）与要求不符，已自动改用 " + best.getVersion() + "（" + best.getHome()
                        + "，来源：" + best.getOrigin() + "）";
            } else if (manual != null) {
                detail = "已使用手动指定的 " + best;
            } else {
                detail = "共枚举 " + detected.size() + " 个 Java 运行时，已自动匹配 " + best;
            }
            return new Result(Conclusion.OK, requirement, detected, best, false, "",
                    "Java 环境正常", detail);
        }

        if (detected.isEmpty()) {
            String detail = manualInvalid
                    ? "手动指定的 Java 已失效：" + manualPath + "，且未检测到其它 Java 运行时"
                    : "本机未检测到任何 Java 运行时";
            return build(Conclusion.MISSING, requirement, detected, null, detail);
        }

        // 无精确匹配：手动指定有效时以其为准给出结论，否则取最接近的候选
        JavaDetector.JavaInfo base = manual != null ? manual : JavaDetector.pickFallback(detected, required);
        if (base == null) {
            return build(Conclusion.MISSING, requirement, detected, null, "本机未检测到任何 Java 运行时");
        }
        Conclusion conclusion = base.getMajorVersion() < required ? Conclusion.TOO_LOW : Conclusion.TOO_HIGH;
        StringBuilder detail = new StringBuilder();
        if (manual != null) {
            detail.append("手动指定的 ").append(base.getVersion()).append("（").append(base.getHome())
                    .append("）与要求不符");
        } else {
            detail.append("仅检测到 ").append(base.getVersion()).append("（").append(base.getHome()).append("）");
        }
        detail.append("；共枚举 ").append(detected.size()).append(" 个 Java 运行时，均不满足 Java ").append(required);
        return build(conclusion, requirement, detected, base, detail.toString());
    }

    /** 把手动指定的运行时并入候选集合（按可执行文件路径 + 版本去重）。 */
    private static void mergeCandidate(List<JavaDetector.JavaInfo> detected, JavaDetector.JavaInfo info) {
        for (JavaDetector.JavaInfo existing : detected) {
            if (sameExecutable(existing, info)) {
                return;
            }
        }
        detected.add(info);
    }

    private static boolean sameExecutable(JavaDetector.JavaInfo left, JavaDetector.JavaInfo right) {
        if (left == null || right == null || left.getJavaExecutable() == null || right.getJavaExecutable() == null) {
            return false;
        }
        return left.getJavaExecutable().toAbsolutePath().normalize()
                .equals(right.getJavaExecutable().toAbsolutePath().normalize());
    }

    /** 候选按主版本降序、同主版本按版本号降序，便于日志与界面展示。 */
    private static void sortDetected(List<JavaDetector.JavaInfo> detected) {
        detected.sort((a, b) -> {
            int byMajor = Integer.compare(b.getMajorVersion(), a.getMajorVersion());
            return byMajor != 0 ? byMajor : JavaDetector.compareVersion(b.getVersion(), a.getVersion());
        });
    }

    /** 组装结论并按防打扰策略决定是否提示。 */
    private Result build(Conclusion conclusion, JavaRequirement requirement,
                         List<JavaDetector.JavaInfo> detected, JavaDetector.JavaInfo matched, String detail) {
        String required = requirement.displayName();
        String message;
        switch (conclusion) {
            case MISSING:
                message = "未检测到可用的 Java 运行时，游戏无法启动。";
                break;
            case TOO_LOW:
                message = "检测到的 Java 版本低于游戏要求，游戏将无法启动。";
                break;
            case TOO_HIGH:
                message = "检测到的 Java 版本高于游戏要求，启动旧版本游戏通常会崩溃。";
                break;
            default:
                message = "Java 环境正常。";
                break;
        }
        boolean promptNeeded = true;
        String suppressReason = "";
        String promptKey = promptKey(requirement);
        long now = System.currentTimeMillis();
        if (forcedInEffect()) {
            // 截图自验模式：无条件提示，保证验证可复现
            promptNeeded = true;
        } else if (config.isJavaCheckDontAskAgain()) {
            promptNeeded = false;
            suppressReason = "用户已选择永久不再提示";
        } else if (now < config.getJavaCheckSuppressUntil()
                && promptKey.equals(config.getJavaCheckLastPromptVersion())) {
            promptNeeded = false;
            long minutes = Math.max(1, (config.getJavaCheckSuppressUntil() - now) / 60000L);
            suppressReason = "同一版本同一结论已提示过，剩余抑制时间约 " + minutes + " 分钟";
        }
        String fullMessage = message + " " + requirement.requirementText() + "。";
        return new Result(conclusion, requirement, detected, matched, promptNeeded, suppressReason,
                fullMessage, detail);
    }

    /** 记录本次提示，写入抑制窗口（默认 24 小时）。 */
    public void markPrompted(Result result) {
        if (result == null || result.requirement() == null) {
            return;
        }
        config.setJavaCheckLastPromptVersion(promptKey(result.requirement()));
        config.setJavaCheckSuppressUntil(System.currentTimeMillis() + LauncherConfig.JAVA_CHECK_SUPPRESS_MILLIS);
        try {
            configManager.save(config);
        } catch (IOException ignored) {
            // 写盘失败不影响本次提示
        }
    }

    /** 永久关闭提示。 */
    public void disableForever() {
        config.setJavaCheckDontAskAgain(true);
        try {
            configManager.save(config);
        } catch (IOException ignored) {
            // 写盘失败时本次运行仍生效
        }
    }

    /** 恢复提示（设置页入口）。 */
    public void restorePrompts() {
        config.setJavaCheckEnabled(true);
        config.setJavaCheckDontAskAgain(false);
        config.setJavaCheckSuppressUntil(0L);
        config.setJavaCheckLastPromptVersion("");
        try {
            configManager.save(config);
        } catch (IOException ignored) {
            // 写盘失败时本次运行仍生效
        }
    }

    // ------------------------------------------------------------------ 工具方法

    /** 强制模式（截图自验）下不做防打扰抑制，保证每次都能看到提示卡。 */
    private static boolean forcedInEffect() {
        return forcedConclusion() != null;
    }

    /** 当前是否处于强制模拟模式（自验用，不应写入用户配置）。 */
    public static boolean isForcedMode() {
        return forcedConclusion() != null;
    }

    private static String promptKey(JavaRequirement requirement) {
        return requirement.versionId() + "#" + requirement.requiredMajor();
    }

    private static List<JavaDetector.JavaInfo> filterBelow(List<JavaDetector.JavaInfo> list, int major) {
        List<JavaDetector.JavaInfo> result = new ArrayList<>();
        for (JavaDetector.JavaInfo info : list) {
            if (info.getMajorVersion() < major) {
                result.add(info);
            }
        }
        return result;
    }

    private static List<JavaDetector.JavaInfo> filterAtLeast(List<JavaDetector.JavaInfo> list, int major) {
        List<JavaDetector.JavaInfo> result = new ArrayList<>();
        for (JavaDetector.JavaInfo info : list) {
            if (info.getMajorVersion() >= major) {
                result.add(info);
            }
        }
        return result;
    }

    /** 主版本最接近要求的运行时；同差距时取较小版本（更可能接近可用）。 */
    private static JavaDetector.JavaInfo pickClosest(List<JavaDetector.JavaInfo> list, int required) {
        JavaDetector.JavaInfo best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (JavaDetector.JavaInfo info : list) {
            int diff = Math.abs(info.getMajorVersion() - required);
            if (diff < bestDiff) {
                best = info;
                bestDiff = diff;
            }
        }
        return best;
    }

    /**
     * 架构兼容性：64 位 Windows 无法用 32 位 Java 启动现代版本（缺少 64 位原生库）。
     * 架构未知时不拦截，避免误判。
     */
    public static boolean isArchCompatible(JavaDetector.JavaInfo info) {
        String osArch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean host64 = osArch.contains("64") || "aarch64".equals(osArch);
        if (!host64) {
            return true;
        }
        String javaArch = info.getArch() == null ? "" : info.getArch().toLowerCase(java.util.Locale.ROOT);
        return !(javaArch.equals("x86") || javaArch.equals("i386") || javaArch.equals("i686"));
    }

    /** 回调接口（工作线程调用） */
    public interface Callback {
        void onResult(Result result);
    }

    /** 便于外部判断版本 JSON 是否存在（日志用） */
    public static boolean hasVersionJson(GamePaths paths, String versionId) {
        if (paths == null || versionId == null || versionId.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(paths.versionJson(versionId.trim()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
