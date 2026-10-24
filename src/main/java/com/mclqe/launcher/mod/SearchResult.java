package com.mclqe.launcher.mod;

import java.util.List;

/**
 * Modrinth 搜索结果的单页数据（接口 {@code /search} 响应）。
 */
public record SearchResult(List<ModHit> hits, int totalHits, int offset, int limit) {

    public boolean empty() {
        return hits == null || hits.isEmpty();
    }
}
