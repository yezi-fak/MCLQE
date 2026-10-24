package com.mclqe.launcher.model;

import java.util.List;

/**
 * 官方版本清单 version_manifest_v2.json。
 */
public class VersionManifest {

    private Latest latest;
    private List<VersionEntry> versions;

    public Latest getLatest() {
        return latest;
    }

    public List<VersionEntry> getVersions() {
        return versions;
    }

    public static class Latest {
        private String release;
        private String snapshot;

        public String getRelease() {
            return release;
        }

        public String getSnapshot() {
            return snapshot;
        }
    }

    public static class VersionEntry {
        private String id;
        private String type;
        private String url;
        private String time;
        private String releaseTime;
        private String sha1;
        private int complianceLevel;

        /** 本地 versions 目录中是否已安装（运行时标记，不参与 JSON 序列化） */
        private transient boolean installed;

        /** 仅存在于本地、官方清单中没有的版本（如第三方整合 / 手动放入） */
        private transient boolean localOnly;

        /** 本地版本的附加说明（继承信息 / 异常原因），无则为空 */
        private transient String localNote;

        /** 本地已安装但损坏（版本 JSON 缺失或解析失败） */
        private transient boolean damaged;

        /** 本地存在版本文件夹、但缺本体 jar（未安装完成），不算已安装 */
        private transient boolean incomplete;

        public VersionEntry() {
        }

        public VersionEntry(String id) {
            this.id = id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public void setType(String type) {
            this.type = type;
        }

        public void setReleaseTime(String releaseTime) {
            this.releaseTime = releaseTime;
        }

        public boolean isInstalled() {
            return installed;
        }

        public void setInstalled(boolean installed) {
            this.installed = installed;
        }

        public boolean isLocalOnly() {
            return localOnly;
        }

        public void setLocalOnly(boolean localOnly) {
            this.localOnly = localOnly;
        }

        public String getLocalNote() {
            return localNote;
        }

        public void setLocalNote(String localNote) {
            this.localNote = localNote;
        }

        public boolean isDamaged() {
            return damaged;
        }

        public void setDamaged(boolean damaged) {
            this.damaged = damaged;
        }

        public boolean isIncomplete() {
            return incomplete;
        }

        public void setIncomplete(boolean incomplete) {
            this.incomplete = incomplete;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getUrl() {
            return url;
        }

        public String getTime() {
            return time;
        }

        public String getReleaseTime() {
            return releaseTime;
        }

        public String getSha1() {
            return sha1;
        }

        public int getComplianceLevel() {
            return complianceLevel;
        }

        @Override
        public String toString() {
            // 供 JavaFX ComboBox 直接展示
            return id + "  [" + type + "]";
        }
    }
}
