package com.mclqe.launcher.model;

import java.util.List;
import java.util.Map;

/**
 * 单个版本的 JSON（versions/&lt;id&gt;/&lt;id&gt;.json）。
 * 同时兼容 1.13+ 的 arguments 写法与旧版 minecraftArguments 写法。
 */
public class VersionMeta {

    private String id;
    private String type;
    private String mainClass;
    private String assets;
    private String inheritsFrom;
    private String jar;

    private AssetIndex assetIndex;
    private Map<String, DownloadInfo> downloads;
    private List<Library> libraries;
    private Arguments arguments;
    private String minecraftArguments;
    private JavaVersion javaVersion;

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getMainClass() {
        return mainClass;
    }

    public String getAssets() {
        return assets;
    }

    public String getInheritsFrom() {
        return inheritsFrom;
    }

    /** 1.17+ 中部分版本通过 jar 字段指向父版本的真实客户端 jar */
    public String getJar() {
        return jar;
    }

    public AssetIndex getAssetIndex() {
        return assetIndex;
    }

    public Map<String, DownloadInfo> getDownloads() {
        return downloads;
    }

    public List<Library> getLibraries() {
        return libraries;
    }

    public Arguments getArguments() {
        return arguments;
    }

    public String getMinecraftArguments() {
        return minecraftArguments;
    }

    public JavaVersion getJavaVersion() {
        return javaVersion;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public void setAssets(String assets) {
        this.assets = assets;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public void setAssetIndex(AssetIndex assetIndex) {
        this.assetIndex = assetIndex;
    }

    public void setDownloads(Map<String, DownloadInfo> downloads) {
        this.downloads = downloads;
    }

    public void setLibraries(List<Library> libraries) {
        this.libraries = libraries;
    }

    public void setArguments(Arguments arguments) {
        this.arguments = arguments;
    }

    public void setMinecraftArguments(String minecraftArguments) {
        this.minecraftArguments = minecraftArguments;
    }

    public void setJavaVersion(JavaVersion javaVersion) {
        this.javaVersion = javaVersion;
    }

    public DownloadInfo getClientDownload() {
        return downloads == null ? null : downloads.get("client");
    }

    /** 版本 JSON 中声明的 Java 组件版本要求（1.17+ 才有） */
    public static class JavaVersion {
        private String component;
        private int majorVersion;

        public String getComponent() {
            return component;
        }

        public int getMajorVersion() {
            return majorVersion;
        }
    }
}
