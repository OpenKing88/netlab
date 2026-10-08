package io.github.openking88.netlab.capture;

/**
 * 已经"抢"下来的原始字节 + 它的状态。
 *
 * <p>设计上刻意拆成两步：OkHttp 线程只负责把字节拷出来（必须抢在响应体被消费之前完成），
 * gzip 解压、编码判断、字符串构造这些耗 CPU 的活儿交给写库线程做。
 */
public final class CapturedBytes {

    public final byte[] bytes;
    public final String state;
    public final boolean gzipEncoded;

    private CapturedBytes(byte[] bytes, String state, boolean gzipEncoded) {
        this.bytes = bytes;
        this.state = state;
        this.gzipEncoded = gzipEncoded;
    }

    public static CapturedBytes empty() {
        return new CapturedBytes(null, CaptureRecord.BODY_EMPTY, false);
    }

    public static CapturedBytes omitted() {
        return new CapturedBytes(null, CaptureRecord.BODY_OMITTED, false);
    }

    public static CapturedBytes tooLarge() {
        return new CapturedBytes(null, CaptureRecord.BODY_TOO_LARGE, false);
    }

    public static CapturedBytes captured(byte[] bytes, boolean gzipEncoded) {
        return new CapturedBytes(bytes, CaptureRecord.BODY_CAPTURED, gzipEncoded);
    }

    public boolean hasBytes() {
        return bytes != null && bytes.length > 0;
    }
}
