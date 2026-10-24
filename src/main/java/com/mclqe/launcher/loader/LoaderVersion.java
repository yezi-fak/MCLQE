package com.mclqe.launcher.loader;

/**
 * 一个可安装的加载器版本。
 *
 * @param version 加载器版本号（如 0.16.9 / 52.1.20 / 21.1.219）
 * @param tag     标签（推荐 / 最新 / 稳定 / 测试），无特殊标记时为空串
 * @param stable  是否稳定版
 */
public record LoaderVersion(String version, String tag, boolean stable) {

    /** 下拉框展示文案。 */
    public String displayName() {
        if (tag == null || tag.isBlank()) {
            return version;
        }
        return version + "  ·  " + tag;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
