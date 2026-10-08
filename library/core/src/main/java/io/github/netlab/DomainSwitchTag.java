package io.github.netlab;

import okhttp3.HttpUrl;

/**
 * 挂在 {@link okhttp3.Request#tag(Class)} 上的改写标记。
 *
 * <p>用途：抓包/日志类工具（比如 Monitor）读这个 tag，就能在列表里同时显示
 * "原始域名 → 实际域名"。tag 不参与网络传输，不会污染请求头。
 */
public final class DomainSwitchTag {

    public final HttpUrl originalUrl;

    public DomainSwitchTag(HttpUrl originalUrl) {
        this.originalUrl = originalUrl;
    }
}
