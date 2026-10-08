package io.github.openking88.netlab.capture;

import java.nio.charset.Charset;

/**
 * 一次请求在 OkHttp 线程上"抢"到的原始素材。
 *
 * <p>只装字节，不装解码结果 —— 解码统一放到写库线程做。
 */
public final class CaptureSnapshot {

    public final CapturedBytes requestBody;
    public final Charset requestCharset;

    public CapturedBytes responseBody = CapturedBytes.empty();
    public Charset responseCharset;

    public CaptureSnapshot(CapturedBytes requestBody, Charset requestCharset) {
        this.requestBody = requestBody;
        this.requestCharset = requestCharset;
    }
}
