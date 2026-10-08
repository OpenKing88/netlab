package io.github.openking88.netlab.capture;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 用捕获到的字节重放请求体。
 *
 * <p>刻意不调用 {@code RequestBody.create(...)}：那组静态工厂在 OkHttp 4 已经废弃、
 * 在 OkHttp 5 被移除，而这个库要同时兼容两个大版本。自己实现只用到实例方法和自有类型，
 * 与 OkHttp 版本无关。
 */
final class ReplayRequestBody extends RequestBody {

    private final RequestBody original;
    private final byte[] bytes;

    ReplayRequestBody(RequestBody original, byte[] bytes) {
        this.original = original;
        this.bytes = bytes;
    }

    @Override
    public MediaType contentType() {
        return original.contentType();
    }

    @Override
    public long contentLength() {
        return bytes.length;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        sink.write(bytes);
    }
}
