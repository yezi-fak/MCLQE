package com.mclqe.launcher.model;

/**
 * 与官方 JSON 中 downloads / libraries.downloads.artifact 等节点对应的下载描述。
 * 字段名与官方 version_manifest_v2.json、版本 JSON 保持一致。
 */
public class DownloadInfo {

    private String sha1;
    private long size;
    private String url;
    private String path;

    public String getSha1() {
        return sha1;
    }

    public long getSize() {
        return size;
    }

    public String getUrl() {
        return url;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "DownloadInfo{url='" + url + "', sha1='" + sha1 + "', size=" + size + "}";
    }
}
