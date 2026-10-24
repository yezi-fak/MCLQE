package com.mclqe.launcher.model;

/**
 * 版本 JSON 中的 assetIndex 节点。
 */
public class AssetIndex {

    private String id;
    private String sha1;
    private long size;
    private long totalSize;
    private String url;

    public String getId() {
        return id;
    }

    public String getSha1() {
        return sha1;
    }

    public long getSize() {
        return size;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public String getUrl() {
        return url;
    }
}
