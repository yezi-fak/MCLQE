package com.mclqe.launcher.model;

import java.util.List;
import java.util.Map;

/**
 * 依赖库条目。name 采用 Maven 坐标 group:artifact:version[:classifier][@ext]。
 * 支持新式 downloads 字段与旧式 url 字段（Mojang 早期版本）。
 */
public class Library {

    private String name;
    private String url;
    private Downloads downloads;
    private List<Rule> rules;
    private Map<String, String> natives;
    private Extract extract;

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public Downloads getDownloads() {
        return downloads;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public Map<String, String> getNatives() {
        return natives;
    }

    public Extract getExtract() {
        return extract;
    }

    public void setName(String name) {
        this.name = name;
    }

    // ---------- 坐标解析 ----------

    private String[] coords() {
        return name == null ? new String[0] : name.split(":");
    }

    public String getGroupId() {
        String[] c = coords();
        return c.length > 0 ? c[0] : "";
    }

    public String getArtifactId() {
        String[] c = coords();
        return c.length > 1 ? c[1] : "";
    }

    public String getVersion() {
        String[] c = coords();
        return c.length > 2 ? c[2] : "";
    }

    /** 形如 org/lwjgl/lwjgl/3.3.1 */
    public String getDirectoryPath() {
        return getGroupId().replace('.', '/') + "/" + getArtifactId() + "/" + getVersion();
    }

    /** 普通 artifact 文件名，形如 lwjgl-3.3.1.jar */
    public String getArtifactFileName() {
        String version = getVersion();
        String[] c = coords();
        String extension = "jar";
        if (c.length > 2 && c[2].contains("@")) {
            version = c[2].substring(0, c[2].indexOf('@'));
            extension = c[2].substring(c[2].indexOf('@') + 1);
        }
        return getArtifactId() + "-" + version + "." + extension;
    }

    /** 判断是否为「当前平台需要解压的 natives」类型的 library */
    public boolean hasNatives() {
        return natives != null && !natives.isEmpty();
    }

    public static class Downloads {
        private DownloadInfo artifact;
        private Map<String, DownloadInfo> classifiers;

        public DownloadInfo getArtifact() {
            return artifact;
        }

        public Map<String, DownloadInfo> getClassifiers() {
            return classifiers;
        }
    }

    public static class Extract {
        private List<String> exclude;

        public List<String> getExclude() {
            return exclude;
        }
    }
}
