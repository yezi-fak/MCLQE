package com.mclqe.launcher.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 版本下载源（参考 HMCL 的 BMCLAPIDownloadProvider 与 PCL2 的镜像方案实现）。
 *
 * <p>官方地址会按「最长前缀匹配」重写为当前下载源地址，覆盖版本清单 / 版本 JSON /
 * client.jar / libraries / assets / forge / neoforge / fabric / liteloader /
 * authlib-injector 等全部下载地址。</p>
 *
 * <p>源列表：{@link #AUTO}（自动优选，默认）/ {@link #OFFICIAL}（官方）/
 * {@link #MIRROR}（社区镜像 https://bsmirror.hkmc.online）/ {@link #CUSTOM}（自定义）。
 * 自动优选在后台线程探测各源 manifest 可用性（连接 / 读取超时 5 秒），取最快且成功者；
 * 全部失败或离线时回退官方源，不阻塞界面。历史配置里的 BMCLAPI / MCBBS 一律回退为 AUTO。</p>
 */
public enum DownloadSource {

    /** 自动优选（默认）：后台探测各源可用性，取最快且成功者 */
    AUTO("AUTO", "自动", ""),

    /** 官方源（Mojang），不做任何重写 */
    OFFICIAL("OFFICIAL", "官方", "https://piston-meta.mojang.com"),

    /** 社区镜像（bsmirror，兼容 BMCLAPI 路径布局） */
    MIRROR("MIRROR", "社区镜像", "https://bsmirror.hkmc.online"),

    /** 自定义镜像（地址由配置项 customSourceRoot 提供，需与 BMCLAPI 路径布局兼容） */
    CUSTOM("CUSTOM", "自定义", "");

    /** 官方版本清单地址（v2） */
    public static final String OFFICIAL_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** 官方资源文件 CDN 前缀 */
    public static final String OFFICIAL_ASSETS_BASE = "https://resources.download.minecraft.net/";
    /** 官方依赖库仓库前缀 */
    public static final String OFFICIAL_LIBRARIES_BASE = "https://libraries.minecraft.net/";
    /** 官方对象存储根（client.jar 等 /v1/objects 文件） */
    public static final String PISTON_DATA_ROOT = "https://piston-data.mojang.com";
    /** 社区镜像根（bsmirror） */
    public static final String MIRROR_ROOT = "https://bsmirror.hkmc.online";
    /** 社区镜像备用域（bsmirror 返回的版本清单中登记的地址所在域，与 MIRROR_ROOT 同站） */
    public static final String MIRROR_ALT_ROOT = "https://mirror.hkmc.online";
    /** 已停用 / 不可用的历史镜像（BMCLAPI），仅用于地址反推与旧配置兼容 */
    public static final String LEGACY_BMCLAPI_ROOT = "https://bmclapi2.bangbang93.com";
    /** 已关停的历史镜像（MCBBS），仅用于地址反推与旧配置兼容 */
    public static final String LEGACY_MCBBS_ROOT = "https://download.mcbbs.net";

    /** 可用性探测的连接 / 读取超时（毫秒） */
    public static final int PROBE_TIMEOUT_MS = 5_000;
    /** 单次下载最多尝试的候选地址数量 */
    private static final int MAX_CANDIDATES = 3;
    /** 镜像通用的版本清单子路径 */
    private static final String MANIFEST_PATH = "/mc/game/version_manifest_v2.json";

    /** 官方前缀 → 镜像根之后的路径（最长前缀匹配） */
    private static final List<Rule> RULES = List.of(
            // ---- Mojang 官方（镜像根下直接同名）----
            new Rule("https://piston-meta.mojang.com", ""),
            new Rule("http://piston-meta.mojang.com", ""),
            new Rule("https://launchermeta.mojang.com", ""),
            new Rule("http://launchermeta.mojang.com", ""),
            new Rule("https://piston-data.mojang.com", "", null, true),
            new Rule("http://piston-data.mojang.com", "", null, true),
            new Rule("https://launcher.mojang.com", "", null, true),
            new Rule("http://launcher.mojang.com", "", null, true),
            // ---- 依赖库仓库 → 镜像根 /maven ----
            new Rule("https://libraries.minecraft.net", "/maven"),
            new Rule("http://libraries.minecraft.net", "/maven"),
            new Rule("https://maven.minecraftforge.net", "/maven"),
            new Rule("http://maven.minecraftforge.net", "/maven"),
            new Rule("https://files.minecraftforge.net/maven", "/maven"),
            new Rule("http://files.minecraftforge.net/maven", "/maven"),
            new Rule("https://maven.neoforged.net/releases", "/maven"),
            new Rule("https://maven.fabricmc.net", "/maven"),
            new Rule("http://dl.liteloader.com/versions", "/maven"),
            new Rule("https://dl.liteloader.com/versions", "/maven"),
            // ---- Maven 中央仓库 → 腾讯云公共镜像（与 HMCL 一致，不依赖镜像根）----
            new Rule("https://repo1.maven.org/maven2", "", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
            new Rule("https://repo.maven.apache.org/maven2", "", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
            // ---- 资源文件 → 镜像根 /assets ----
            new Rule("https://resources.download.minecraft.net", "/assets"),
            new Rule("http://resources.download.minecraft.net", "/assets"),
            // ---- 元数据镜像 ----
            new Rule("https://meta.fabricmc.net", "/fabric-meta"),
            new Rule("https://authlib-injector.yushi.moe", "/mirrors/authlib-injector"),
            // ---- 已是镜像地址时，允许在镜像之间互相切换 ----
            new Rule("https://bsmirror.hkmc.online", ""),
            // bsmirror 返回的版本清单中，地址落在同站备用域 mirror.hkmc.online，一并归一到所选源根
            new Rule("https://mirror.hkmc.online", ""),
            new Rule("https://bmclapi2.bangbang93.com", ""),
            new Rule("https://download.mcbbs.net", "")
    );

    /** 官方对象文件地址（/v1/objects/两位目录/sha1/文件） */
    private static final java.util.regex.Pattern OBJECT_WITH_DIR =
            java.util.regex.Pattern.compile("^/v1/objects/([0-9a-fA-F]{2})/([0-9a-fA-F]{40})/(.+)$");
    /** 镜像对象文件地址（/v1/objects/sha1/文件） */
    private static final java.util.regex.Pattern OBJECT_NO_DIR =
            java.util.regex.Pattern.compile("^/v1/objects/([0-9a-fA-F]{40})/(.+)$");

    private final String id;
    private final String label;
    private final String defaultRoot;

    DownloadSource(String id, String label, String defaultRoot) {
        this.id = id;
        this.label = label;
        this.defaultRoot = defaultRoot;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** 界面展示名（AUTO 附带当前自动优选结果） */
    public String displayName() {
        if (this != AUTO) {
            return label;
        }
        ProbeResult result = lastProbe;
        if (result == null || PROBING.get()) {
            return "自动（探测中…）";
        }
        return "自动（已选：" + result.chosen().label() + " " + formatMillis(result.millis()) + "）";
    }

    /** 解析为真正生效的源（AUTO → 自动优选结果；AUTO 未探测完成时按官方处理） */
    private DownloadSource resolved() {
        if (this != AUTO) {
            return this;
        }
        DownloadSource auto = effective;
        return (auto == null || auto == AUTO) ? OFFICIAL : auto;
    }

    /** 该源的镜像根地址；官方源返回 null（表示不做任何重写） */
    public String mirrorRoot() {
        DownloadSource source = resolved();
        if (source == OFFICIAL) {
            return null;
        }
        String value = source == CUSTOM ? customRoot : source.defaultRoot;
        if (value == null) {
            return null;
        }
        value = value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /** 该源实际使用的根地址（官方源返回 Mojang 官方根，便于界面展示） */
    public String root() {
        String mirror = mirrorRoot();
        return mirror == null ? OFFICIAL.defaultRoot : mirror;
    }

    // ------------------------------------------------------------------ 当前生效的源

    /** 用户选择的源（可能为 AUTO） */
    private static volatile DownloadSource current = AUTO;
    /** AUTO 模式下自动优选出的实际源 */
    private static volatile DownloadSource effective = OFFICIAL;
    private static volatile String customRoot = "";
    private static volatile ProbeResult lastProbe;
    private static final AtomicBoolean PROBING = new AtomicBoolean(false);
    private static volatile Consumer<ProbeResult> probeListener;

    /** 当前真正生效的下载源（AUTO 时返回自动优选结果） */
    public static DownloadSource active() {
        return current == AUTO ? effective : current;
    }

    /** 用户选择的下载源（可能是 AUTO） */
    public static DownloadSource selected() {
        return current;
    }

    /** 是否处于自动优选模式 */
    public static boolean isAuto() {
        return current == AUTO;
    }

    /** 自动优选是否正在探测中 */
    public static boolean isProbing() {
        return PROBING.get();
    }

    /** 最近一次自动优选结果（未探测过时为 null） */
    public static ProbeResult lastProbe() {
        return lastProbe;
    }

    /** 注册自动优选完成回调（界面据此刷新显示；回调在探测线程中触发） */
    public static void setProbeListener(Consumer<ProbeResult> listener) {
        probeListener = listener;
    }

    /** 当前自定义源根地址（可能为空） */
    public static String customRoot() {
        return customRoot;
    }

    /**
     * 由配置设置当前下载源；旧配置（BMCLAPI / MCBBS）与未知名称一律回退 AUTO，不抛异常。
     * 处于 AUTO 时在后台线程启动可用性探测（不阻塞调用方）。
     */
    public static void configure(String sourceName, String customSourceRoot) {
        DownloadSource previous = current;
        String previousRoot = customRoot;
        DownloadSource selected = of(sourceName);
        String normalizedRoot = normalizeRoot(customSourceRoot);
        current = selected;
        customRoot = normalizedRoot;

        if (selected != AUTO) {
            return;
        }
        if (PROBING.get()) {
            return;
        }
        boolean needsProbe = previous != AUTO || lastProbe == null || !normalizedRoot.equals(previousRoot);
        if (needsProbe) {
            lastProbe = null;
            startAutoProbe();
        }
    }

    /** 解析配置里的下载源名称；兼容枚举名 / id / 中文名，未知或为空时返回 AUTO。 */
    public static DownloadSource of(String name) {
        if (name == null || name.isBlank()) {
            return AUTO;
        }
        String key = name.trim();
        for (DownloadSource source : values()) {
            if (source.id.equalsIgnoreCase(key)
                    || source.name().equalsIgnoreCase(key)
                    || source.label.equalsIgnoreCase(key)) {
                return source;
            }
        }
        if (key.toLowerCase(Locale.ROOT).contains("bsmirror")) {
            return MIRROR;
        }
        // BMCLAPI（本机不可用）/ MCBBS（已关停）等历史配置一律回退自动优选
        return AUTO;
    }

    /** 规范化镜像根地址：去空白、补协议、去尾部斜杠。 */
    public static String normalizeRoot(String root) {
        if (root == null) {
            return "";
        }
        String value = root.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        return value;
    }

    // ------------------------------------------------------------------ 地址入口

    /** 当前下载源的版本清单地址 */
    public String manifestUrl() {
        String root = mirrorRoot();
        return root == null ? OFFICIAL_MANIFEST_URL : root + MANIFEST_PATH;
    }

    /**
     * 版本 JSON 直链地址（BMCLAPI 式 {@code /version/<id>/json}）。
     *
     * <p>实测 bsmirror 不提供该直链接口（返回 404），因此官方源与社区镜像一律返回 null，
     * 版本 JSON 统一取版本清单中登记的官方地址并经 {@link #rewrite(String)} 重写下载；
     * 仅自定义源保留该直链，便于对接自建 BMCLAPI 布局镜像。</p>
     */
    public String versionJsonUrl(String versionId) {
        if (resolved() != CUSTOM) {
            return null;
        }
        String root = mirrorRoot();
        return root == null ? null : root + "/version/" + versionId + "/json";
    }

    /**
     * client.jar 直链地址（BMCLAPI 式 {@code /version/<id>/client}）。
     *
     * <p>同 {@link #versionJsonUrl(String)}：bsmirror 实测 404，官方源与社区镜像返回 null，
     * client.jar 统一取版本 JSON 的 downloads.client 地址重写后下载。</p>
     */
    public String clientJarUrl(String versionId) {
        if (resolved() != CUSTOM) {
            return null;
        }
        String root = mirrorRoot();
        return root == null ? null : root + "/version/" + versionId + "/client";
    }

    /** 资源文件（assets objects）下载前缀 */
    public String assetsBase() {
        String root = mirrorRoot();
        return root == null ? OFFICIAL_ASSETS_BASE : root + "/assets/";
    }

    /** 依赖库仓库下载前缀 */
    public String librariesBase() {
        String root = mirrorRoot();
        return root == null ? OFFICIAL_LIBRARIES_BASE : root + "/maven/";
    }

    // ------------------------------------------------------------------ 地址重写

    /** 按当前下载源重写地址（官方源直接原样返回）。 */
    public static String rewrite(String url) {
        return rewrite(url, current, customRoot);
    }

    /** 按指定下载源重写地址，最长前缀匹配；未命中前缀时原样返回。 */
    public static String rewrite(String url, DownloadSource source, String customSourceRoot) {
        if (url == null || url.isBlank()) {
            return url;
        }
        DownloadSource src = (source == null ? current : source).resolved();
        if (src == OFFICIAL) {
            return url;
        }
        String root;
        if (src == CUSTOM) {
            root = normalizeRoot(customSourceRoot == null ? customRoot : customSourceRoot);
        } else {
            root = src.defaultRoot;
        }
        if (root == null || root.isEmpty()) {
            return url;
        }
        Rule best = null;
        for (Rule rule : RULES) {
            if (url.startsWith(rule.official)
                    && (best == null || rule.official.length() > best.official.length())) {
                best = rule;
            }
        }
        if (best == null) {
            return url;
        }
        String suffix = url.substring(best.official.length());
        if (best.absoluteTarget != null) {
            // 该前缀重写为固定镜像地址（如 Maven 中央仓库 → 腾讯云镜像），与镜像根无关
            return best.absoluteTarget + suffix;
        }
        if (best.stripObjectDir) {
            suffix = stripObjectDir(suffix);
        }
        return root + best.mirrorPath + suffix;
    }

    /** /v1/objects/&lt;两位目录&gt;/&lt;sha1&gt;/&lt;文件&gt; → /v1/objects/&lt;sha1&gt;/&lt;文件&gt;（BMCLAPI 系镜像的对象布局）。 */
    private static String stripObjectDir(String suffix) {
        java.util.regex.Matcher m = OBJECT_WITH_DIR.matcher(suffix);
        return m.matches() ? "/v1/objects/" + m.group(2) + "/" + m.group(3) : suffix;
    }

    /** /v1/objects/&lt;sha1&gt;/&lt;文件&gt; → /v1/objects/&lt;两位目录&gt;/&lt;sha1&gt;/&lt;文件&gt;（还原为官方布局）。 */
    private static String unstripObjectDir(String suffix) {
        java.util.regex.Matcher m = OBJECT_NO_DIR.matcher(suffix);
        return m.matches() ? "/v1/objects/" + m.group(1).substring(0, 2) + "/" + m.group(1) + "/" + m.group(2) : null;
    }

    /**
     * 下载地址候选列表（最多 {@value #MAX_CANDIDATES} 个，按优先级）：
     * ① 当前源地址 → ② 官方地址 → ③ 社区镜像（bsmirror）地址，自动去重。
     * 便于「主源整轮失败后用下一候选再重试一轮」。
     */
    public static List<String> candidates(String url) {
        List<String> list = new ArrayList<>(MAX_CANDIDATES);
        if (url == null || url.isBlank()) {
            return list;
        }
        addUnique(list, rewrite(url));
        String official = officialOf(url);
        addUnique(list, official);
        addUnique(list, rewrite(official, MIRROR, null));
        return list.size() > MAX_CANDIDATES ? new ArrayList<>(list.subList(0, MAX_CANDIDATES)) : list;
    }

    /** 推测给定地址对应的官方地址（无法反推时返回原地址）。 */
    private static String officialOf(String url) {
        for (Rule rule : RULES) {
            if (rule.absoluteTarget != null && startsWithDir(url, rule.absoluteTarget)) {
                return rule.official + url.substring(rule.absoluteTarget.length());
            }
        }
        for (String root : knownMirrorRoots()) {
            if (startsWithDir(url, root)) {
                List<String> reversed = reverseOfficials(url, root);
                if (!reversed.isEmpty()) {
                    return reversed.get(0);
                }
            }
        }
        return url;
    }

    /** 已知镜像根（社区镜像 / 备用域 / 历史镜像 / 自定义源） */
    private static List<String> knownMirrorRoots() {
        List<String> roots = new ArrayList<>(5);
        roots.add(MIRROR_ROOT);
        roots.add(MIRROR_ALT_ROOT);
        roots.add(LEGACY_BMCLAPI_ROOT);
        roots.add(LEGACY_MCBBS_ROOT);
        if (!customRoot.isEmpty()) {
            roots.add(customRoot);
        }
        return roots;
    }

    private static void addUnique(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }

    private static boolean startsWithDir(String url, String prefix) {
        return prefix != null && !prefix.isEmpty() && (url.equals(prefix) || url.startsWith(prefix + "/"));
    }

    /** 依据替换表反向推导镜像地址对应的官方地址（可能存在多个候选）。 */
    private static List<String> reverseOfficials(String mirrorUrl, String root) {
        List<String> result = new ArrayList<>(4);
        for (Rule rule : RULES) {
            if (rule.absoluteTarget != null) {
                // 固定镜像地址由 officialOf 的第一段逻辑处理
                continue;
            }
            if (rule.mirrorPath.isEmpty()) {
                continue;
            }
            String prefix = root + rule.mirrorPath;
            if (mirrorUrl.equals(prefix) || mirrorUrl.startsWith(prefix + "/")) {
                result.add(rule.official + mirrorUrl.substring(prefix.length()));
            }
        }
        if (result.isEmpty() && startsWithDir(mirrorUrl, root)) {
            String suffix = mirrorUrl.substring(root.length());
            String objectPath = unstripObjectDir(suffix);
            if (objectPath != null) {
                result.add(PISTON_DATA_ROOT + objectPath);
            }
            for (Rule rule : RULES) {
                if (!rule.mirrorPath.isEmpty() || rule.stripObjectDir) {
                    continue;
                }
                result.add(rule.official + suffix);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 可用性探测（自动优选）

    /**
     * 后台启动自动优选探测：对每个候选源请求版本清单（连接 / 读取超时 5 秒），
     * 取最快且成功者作为 AUTO 的实际源；全部失败则回退官方源。不阻塞界面。
     */
    public static void startAutoProbe() {
        if (current != AUTO) {
            return;
        }
        if (!PROBING.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                probeNow();
            } finally {
                PROBING.set(false);
                Consumer<ProbeResult> listener = probeListener;
                ProbeResult result = lastProbe;
                if (listener != null && result != null) {
                    try {
                        listener.accept(result);
                    } catch (Exception ignored) {
                        // 界面回调异常不影响启动器主流程
                    }
                }
            }
        }, "mclqe-source-probe");
        thread.setDaemon(true);
        thread.start();
    }

    /** 同步探测全部候选源，选择最快且成功者；全失败或离线时使用官方源。 */
    public static ProbeResult probeNow() {
        Map<String, Long> timings = new LinkedHashMap<>();
        DownloadSource best = null;
        long bestMillis = Long.MAX_VALUE;
        for (DownloadSource source : probeTargets()) {
            long millis = source.pingManifest();
            timings.put(source.label(), millis);
            if (millis >= 0 && millis < bestMillis) {
                best = source;
                bestMillis = millis;
            }
        }
        DownloadSource chosen = best == null ? OFFICIAL : best;
        ProbeResult result = new ProbeResult(chosen, best == null ? -1 : bestMillis, timings, best == null);
        effective = chosen;
        lastProbe = result;
        return result;
    }

    /** 等待探测结束（最多 timeoutMs 毫秒），返回最近一次探测结果（可能为 null）。 */
    public static ProbeResult awaitProbe(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (PROBING.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return lastProbe;
    }

    /** 需要参与自动优选的源：官方 + 社区镜像（+ 已配置的自定义源） */
    private static List<DownloadSource> probeTargets() {
        List<DownloadSource> targets = new ArrayList<>(3);
        targets.add(OFFICIAL);
        targets.add(MIRROR);
        if (!customRoot.isEmpty()) {
            targets.add(CUSTOM);
        }
        return targets;
    }

    /** 请求该源的版本清单，返回耗时（毫秒）；失败或超时返回 -1。 */
    private long pingManifest() {
        String url = manifestUrl();
        if (url == null || url.isBlank()) {
            return -1;
        }
        HttpURLConnection conn = null;
        long start = System.nanoTime();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", HttpUtils.USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return -1;
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[256];
                if (in.read(buffer) <= 0) {
                    return -1;
                }
            }
            return Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 自动优选结果 */
    public static final class ProbeResult {

        private final DownloadSource chosen;
        private final long millis;
        private final Map<String, Long> timings;
        private final boolean allFailed;

        ProbeResult(DownloadSource chosen, long millis, Map<String, Long> timings, boolean allFailed) {
            this.chosen = chosen;
            this.millis = millis;
            this.timings = timings;
            this.allFailed = allFailed;
        }

        /** 自动优选出的源（全失败时为官方源） */
        public DownloadSource chosen() {
            return chosen;
        }

        /** 选中源的探测耗时（毫秒）；全失败时为 -1 */
        public long millis() {
            return millis;
        }

        /** 各源探测耗时（key 为源名称，value 为毫秒或 -1 表示失败 / 超时） */
        public Map<String, Long> timings() {
            return timings;
        }

        /** 是否全部源都失败 */
        public boolean allFailed() {
            return allFailed;
        }

        /** 供界面展示的摘要，如「已选 官方（0.6s）｜官方 0.6s、社区镜像 2.1s」 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("已选 ").append(chosen.label());
            if (allFailed) {
                sb.append("（全部源探测失败，回退官方源）");
            } else {
                sb.append('（').append(formatMillis(millis)).append('）');
            }
            sb.append("｜各源耗时：");
            boolean first = true;
            for (Map.Entry<String, Long> entry : timings.entrySet()) {
                if (!first) {
                    sb.append('、');
                }
                first = false;
                long value = entry.getValue();
                sb.append(entry.getKey()).append(' ').append(value < 0 ? "失败/超时" : formatMillis(value));
            }
            return sb.toString();
        }
    }

    static String formatMillis(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    /** 界面状态文案：当前使用的源 / 自动优选结果 */
    public static String statusText() {
        if (current != AUTO) {
            return "当前使用：" + current.label() + "（" + current.root() + "）";
        }
        ProbeResult result = lastProbe;
        if (result == null || PROBING.get()) {
            return "自动优选：探测中…（未完成前默认使用官方源）";
        }
        return "自动优选：" + result.describe();
    }

    @Override
    public String toString() {
        return displayName();
    }

    /** 前缀替换规则 */
    private static final class Rule {
        final String official;
        final String mirrorPath;
        /** 固定目标地址（不走镜像根，如 Maven 中央仓库 → 腾讯云镜像）；为 null 时使用镜像根 + mirrorPath */
        final String absoluteTarget;
        /** 官方 /v1/objects/&lt;两位目录&gt;/&lt;sha1&gt;/&lt;文件&gt; 在 BMCLAPI 系镜像中为 /v1/objects/&lt;sha1&gt;/&lt;文件&gt;，需要去掉两位目录 */
        final boolean stripObjectDir;

        Rule(String official, String mirrorPath) {
            this(official, mirrorPath, null, false);
        }

        Rule(String official, String mirrorPath, String absoluteTarget) {
            this(official, mirrorPath, absoluteTarget, false);
        }

        Rule(String official, String mirrorPath, String absoluteTarget, boolean stripObjectDir) {
            this.official = official;
            this.mirrorPath = mirrorPath;
            this.absoluteTarget = absoluteTarget;
            this.stripObjectDir = stripObjectDir;
        }
    }

    /** 供界面展示的源列表（固定顺序） */
    public static List<DownloadSource> all() {
        return List.of(AUTO, OFFICIAL, MIRROR, CUSTOM);
    }

    /** 解析入口的容错：空值返回默认源名称 */
    public static String normalizeName(String name) {
        return of(name).name();
    }

    /** 便于日志输出的小写标识 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
