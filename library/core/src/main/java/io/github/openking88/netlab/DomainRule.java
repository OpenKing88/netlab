package io.github.openking88.netlab;

import okhttp3.HttpUrl;

/** 一条单向域名映射规则：把 sourceHost 的请求改写到 target 上。 */
public final class DomainRule {

    public final String sourceHost;
    public final String targetScheme;
    public final String targetHost;
    public final int targetPort;

    public DomainRule(String sourceHost, String targetScheme, String targetHost, int targetPort) {
        if (sourceHost == null || sourceHost.isEmpty()) {
            throw new IllegalArgumentException("sourceHost must not be empty");
        }
        this.sourceHost = sourceHost;
        this.targetScheme = targetScheme;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /** 把命中规则的 URL 改写到目标域名，path / query / fragment 原样保留。 */
    public HttpUrl rewrite(HttpUrl original) {
        HttpUrl.Builder builder = original.newBuilder().host(targetHost);
        if (targetScheme != null && !targetScheme.isEmpty()) {
            builder.scheme(targetScheme);
        }
        if (targetPort > 0) {
            builder.port(targetPort);
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return sourceHost + " -> " + targetHost;
    }
}
