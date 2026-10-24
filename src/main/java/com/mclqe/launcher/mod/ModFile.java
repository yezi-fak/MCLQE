package com.mclqe.launcher.mod;

/**
 * Modrinth 版本文件（接口 {@code versions[].files[]} 元素）。
 *
 * @param url      下载地址（Modrinth CDN，不走下载源重写）
 * @param filename 建议落盘文件名
 * @param size     字节数
 * @param primary  是否为主文件
 * @param sha1     SHA1（接口 hashes.sha1）
 * @param sha512   SHA512（接口 hashes.sha512，暂未参与校验）
 */
public record ModFile(String url, String filename, long size, boolean primary, String sha1, String sha512) {
}
