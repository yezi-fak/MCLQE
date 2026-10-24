package com.mclqe.launcher.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.function.Consumer;

/**
 * HTTP 工具：文本请求 + 带 SHA1 校验 / 断点续传 / 自动重试的文件下载。
 */
public final class HttpUtils {

    public static final String USER_AGENT = "MCLQE-Launcher/1.0.0";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY = 3;

    /** 下载过程日志钩子（默认静默，由 DownloadManager 接入界面日志） */
    public static volatile Consumer<String> logger = line -> { };

    private HttpUtils() {
    }

    // ------------------------------------------------------------------ 文本请求

    public static String getString(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    public static byte[] getBytes(String url) throws IOException {
        return getBytes(url, DownloadSource.rewrite(url));
    }

    /** @param sourceUrl 原始地址（用于报错）；@param url 已按下载源重写后的实际请求地址 */
    private static byte[] getBytes(String sourceUrl, String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code + " -> " + url);
                }
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
                    copy(in, out);
                    return out.toByteArray();
                }
            } catch (IOException e) {
                last = e;
                backoff(attempt);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new IOException("请求失败: " + sourceUrl, last);
    }

    // ------------------------------------------------------------------ 文件下载

    /**
     * 下载文件到 target。
     *
     * <p>下载入口统一经过 {@link DownloadSource#rewrite(String)}：
     * 主源（当前下载源）整轮失败后，自动改用另一侧源（官方 ↔ 镜像）再重试一轮。</p>
     *
     * <ul>
     *   <li>已存在且 SHA1 / 大小校验通过 → 直接跳过（不产生网络请求）</li>
     *   <li>存在残文件且服务端支持 Range → 断点续传（206）</li>
     *   <li>单次尝试失败后先删除残文件再重下，杜绝在脏数据上反复 append</li>
     * </ul>
     *
     * @param expectedSha1 期望的 SHA1（可为 null，表示不校验）
     * @param expectedSize 期望的字节数（&lt;=0 表示不校验）
     */
    public static void download(String url, Path target, String expectedSha1, long expectedSize) throws IOException {
        download(DownloadSource.candidates(url), url, target, expectedSha1, expectedSize);
    }

    /** 按给定候选地址顺序下载，全部失败才抛出异常。 */
    public static void download(List<String> candidates, String sourceUrl, Path target,
                                String expectedSha1, long expectedSize) throws IOException {
        if (candidates == null || candidates.isEmpty()) {
            throw new IOException("无可用的下载地址: " + sourceUrl);
        }
        IOException last = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                downloadOnce(candidate, target, expectedSha1, expectedSize);
                return;
            } catch (IOException e) {
                last = e;
                if (i + 1 < candidates.size()) {
                    logger.accept("下载源 " + hostOf(candidate) + " 失败（" + e.getMessage()
                            + "），切换备用源 " + hostOf(candidates.get(i + 1)) + " 重试");
                }
            }
        }
        throw new IOException("下载失败: " + sourceUrl, last);
    }

    /** 单个候选地址的完整下载流程（含 {@value #MAX_RETRY} 次重试与残文件清理）。 */
    private static void downloadOnce(String url, Path target, String expectedSha1, long expectedSize)
            throws IOException {
        if (Files.exists(target) && isUpToDate(target, expectedSha1, expectedSize)) {
            return;
        }
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                if (attempt > 1) {
                    // 上一轮内容已判定不完整 / 校验失败：先删除残文件，避免反复 append 导致脏数据
                    Files.deleteIfExists(target);
                }
                resumeDownload(url, target, expectedSize);
                verify(target, expectedSha1, expectedSize);
                return;
            } catch (IOException e) {
                last = e;
                backoff(attempt);
            }
        }
        // 彻底失败时不保留残文件，防止下次启动在错误内容上续传
        Files.deleteIfExists(target);
        throw new IOException("下载失败: " + url, last);
    }

    /** 目标文件是否已是完整可用（大小与 SHA1 均符合预期）。 */
    private static boolean isUpToDate(Path target, String expectedSha1, long expectedSize) {
        try {
            long size = Files.size(target);
            if (expectedSize > 0 && size != expectedSize) {
                return false;
            }
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                return expectedSha1.equalsIgnoreCase(sha1(target));
            }
            return expectedSize > 0 && size > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 校验下载结果：大小与 SHA1 都必须符合预期。 */
    private static void verify(Path target, String expectedSha1, long expectedSize) throws IOException {
        long size = Files.size(target);
        if (expectedSize > 0 && size != expectedSize) {
            throw new IOException("文件大小不符: " + target.getFileName()
                    + "（期望 " + expectedSize + "，实际 " + size + "）");
        }
        if (expectedSha1 != null && !expectedSha1.isBlank() && !expectedSha1.equalsIgnoreCase(sha1(target))) {
            throw new IOException("SHA1 校验失败: " + target.getFileName());
        }
    }

    private static void resumeDownload(String url, Path target, long expectedSize) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        long existing = Files.exists(target) ? Files.size(target) : 0L;
        if (expectedSize > 0 && existing >= expectedSize) {
            // 大小已达/超过预期：内容必然不可信，删掉重下而不是继续 append
            Files.deleteIfExists(target);
            existing = 0L;
        }

        HttpURLConnection conn = open(url);
        try {
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_PARTIAL) { // 206：续传成功
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    copy(in, out);
                }
            } else if (code == HttpURLConnection.HTTP_OK) { // 200：服务端不支持续传，整文件重写
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    copy(in, out);
                }
            } else {
                throw new IOException("HTTP " + code + " -> " + url);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 取地址中的主机名，仅用于日志展示。 */
    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    // ------------------------------------------------------------------ 摘要工具

    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 JVM 不支持 SHA-1", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部实现

    private static HttpURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        // 关闭 gzip，保证 Range 续传与 Content-Length/SHA1 一致
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(4000L, 500L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
