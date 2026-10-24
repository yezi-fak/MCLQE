package com.mclqe.launcher.net;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 多线程下载调度器：并发下载 client.jar / libraries / natives / assets。
 */
public class DownloadManager {

    /** 单个下载任务 */
    public static class Task {
        /** 已按当前下载源重写后的地址（失败时由 HttpUtils 自动回退官方地址） */
        public final String url;
        /** 未重写的原始地址（版本 JSON 中登记的官方地址），可为 null */
        public final String rawUrl;
        public final Path target;
        public final String sha1;
        public final long size;

        public Task(String url, Path target, String sha1, long size) {
            this(url, null, target, sha1, size);
        }

        public Task(String url, String rawUrl, Path target, String sha1, long size) {
            this.url = url;
            this.rawUrl = rawUrl;
            this.target = target;
            this.sha1 = sha1;
            this.size = size;
        }
    }

    /** 进度回调（done/total 为任务数） */
    public interface ProgressListener {
        void onProgress(int done, int total, String current);

        ProgressListener NOOP = (done, total, current) -> {
        };
    }

    private final int threads;
    private final Consumer<String> logger;
    private final ProgressListener progress;

    public DownloadManager(int threads, Consumer<String> logger, ProgressListener progress) {
        this.threads = Math.max(1, threads);
        this.logger = logger == null ? msg -> {
        } : logger;
        this.progress = progress == null ? ProgressListener.NOOP : progress;
        // 让 HttpUtils 的下载源切换 / 校验失败信息进入界面日志
        HttpUtils.logger = this.logger;
    }

    public DownloadManager(Consumer<String> logger) {
        this(defaultThreads(), logger, ProgressListener.NOOP);
    }

    public static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.min(32, Math.max(4, cores * 2));
    }

    /**
     * 并发下载全部任务。
     *
     * @return true 表示全部成功；false 表示存在失败（失败详情已通过 logger 输出）
     */
    public boolean downloadAll(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return true;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, tasks.size()));
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(tasks.size());

        for (Task task : tasks) {
            futures.add(pool.submit(() -> {
                try {
                    // 候选地址：当前源地址 → 官方地址 → bsmirror 镜像地址（自动去重，最多 3 个）
                    List<String> candidates = DownloadSource.candidates(task.rawUrl != null ? task.rawUrl : task.url);
                    HttpUtils.download(candidates, task.url, task.target, task.sha1, task.size);
                } catch (Exception e) {
                    failed.incrementAndGet();
                    logger.accept("下载失败: " + task.url + " -> " + e.getMessage());
                } finally {
                    progress.onProgress(done.incrementAndGet(), tasks.size(), task.target.getFileName().toString());
                }
            }));
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.HOURS)) {
                pool.shutdownNow();
                logger.accept("下载超时，已中断剩余任务");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            return false;
        }
        return failed.get() == 0;
    }
}
