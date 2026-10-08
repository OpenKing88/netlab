package io.github.openking88.netlab.capture;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.GzipSource;

/**
 * 请求/响应体的捕获与解码。
 *
 * <p>与 Monitor 的实现相比，这里刻意修掉了三个问题：
 * <ol>
 *   <li><b>不再无界缓冲</b>：长度未知的流式 body（chunked 上传）不再"先全部读进内存再判断大小"，
 *       而是直接标记为未捕获、原样转发，避免大文件上传把 App 撑爆；</li>
 *   <li><b>解码不在 OkHttp 线程做</b>：这里只负责把字节抢下来，gzip 解压 / 编码判断 /
 *       字符串构造都交给写库线程；</li>
 *   <li><b>文本判定采样更多</b>：从 64 字节提到 512 字节，减少二进制被误判成文本的概率。</li>
 * </ol>
 */
public final class CaptureBody {

    /** 单个 body 最多抢多少字节。 */
    public static final long DEFAULT_MAX_BODY_BYTES = 512L * 1024L;

    private static final int TEXT_SAMPLE_BYTES = 512;

    private CaptureBody() {
    }

    /** 请求体的捕获结果。 */
    public static final class RequestRead {

        /** 真正要发出去的请求（可能被替换成可重放的 body）。 */
        public final Request request;
        public final CapturedBytes body;
        public final String contentType;
        public final long contentLength;

        RequestRead(Request request, CapturedBytes body, String contentType, long contentLength) {
            this.request = request;
            this.body = body;
            this.contentType = contentType;
            this.contentLength = contentLength;
        }
    }

    public static RequestRead readRequest(Request request, long maxBodyBytes) {
        RequestBody body = request.body();
        if (body == null) {
            return new RequestRead(request, CapturedBytes.empty(), "", -1L);
        }
        String contentType = body.contentType() == null ? "" : body.contentType().toString();
        long contentLength;
        try {
            contentLength = body.contentLength();
        } catch (Throwable error) {
            contentLength = -1L;
        }

        // 一次性 / 双向 body 不能重放，压根不能碰
        if (body.isOneShot() || body.isDuplex() || hasUnknownContentEncoding(request.headers())) {
            return new RequestRead(request, CapturedBytes.omitted(), contentType, contentLength);
        }
        // 已知长度且超限：不缓冲，原样转发
        if (contentLength > maxBodyBytes) {
            return new RequestRead(request, CapturedBytes.tooLarge(), contentType, contentLength);
        }
        // 长度未知（chunked 等）：不冒险缓冲，否则就是 Monitor 那个无界内存问题
        if (contentLength < 0L) {
            return new RequestRead(request, CapturedBytes.omitted(), contentType, contentLength);
        }
        if (contentLength == 0L) {
            return new RequestRead(request, CapturedBytes.empty(), contentType, 0L);
        }

        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            byte[] bytes = buffer.readByteArray();
            Request replay = request.newBuilder()
                    .method(request.method(), new ReplayRequestBody(body, bytes))
                    .build();
            return new RequestRead(
                    replay,
                    CapturedBytes.captured(bytes, isGzipEncoded(request.headers())),
                    contentType,
                    bytes.length
            );
        } catch (Throwable error) {
            // 抢不到就原样转发，绝不能让抓包影响业务请求
            return new RequestRead(request, CapturedBytes.omitted(), contentType, contentLength);
        }
    }

    /**
     * 抢下响应体的字节。必须在 OkHttp 线程上调用（要赶在响应体被消费之前），
     * 但只做拷贝，不做任何解码。
     */
    public static CapturedBytes peekResponse(Response response, long maxBodyBytes) {
        if (!hasResponseBody(response)) {
            return CapturedBytes.empty();
        }
        ResponseBody body = response.body();
        if (body == null) {
            return CapturedBytes.empty();
        }
        long contentLength;
        try {
            contentLength = body.contentLength();
        } catch (Throwable error) {
            contentLength = -1L;
        }
        if (hasUnknownContentEncoding(response.headers())) {
            return CapturedBytes.omitted();
        }
        if (contentLength > maxBodyBytes) {
            return CapturedBytes.tooLarge();
        }
        try {
            ResponseBody peeked = response.peekBody(maxBodyBytes);
            Buffer buffer = new Buffer();
            // 不用 Kotlin 的 use{}：那是标准库扩展，Java 里没有
            BufferedSource source = peeked.source();
            try {
                source.readAll(buffer);
            } finally {
                source.close();
            }
            byte[] bytes = buffer.readByteArray();
            if (bytes.length == 0) {
                return CapturedBytes.empty();
            }
            // peekBody 只读 maxBodyBytes，如果读满了且声明长度更大，说明被截断
            if (contentLength < 0L && bytes.length >= maxBodyBytes) {
                return CapturedBytes.tooLarge();
            }
            return CapturedBytes.captured(bytes, isGzipEncoded(response.headers()));
        } catch (Throwable error) {
            return CapturedBytes.omitted();
        }
    }

    /** 把抢下来的字节解成文本，在写库线程上调用。 */
    public static String decode(CapturedBytes captured, Charset charset) {
        if (captured == null || !captured.hasBytes()) {
            return "";
        }
        try {
            byte[] plain = captured.gzipEncoded ? gunzip(captured.bytes) : captured.bytes;
            if (plain == null || plain.length == 0) {
                return "";
            }
            if (!looksLikeText(plain)) {
                return "";
            }
            return new String(plain, charset == null ? StandardCharsets.UTF_8 : charset);
        } catch (Throwable error) {
            return "";
        }
    }

    public static Charset charsetOf(okhttp3.MediaType contentType) {
        Charset charset = contentType == null ? null : contentType.charset(null);
        return charset == null ? StandardCharsets.UTF_8 : charset;
    }

    public static String headersToText(Headers headers) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < headers.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(headers.name(index)).append(": ").append(headers.value(index));
        }
        return builder.toString();
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private static boolean hasResponseBody(Response response) {
        int code = response.code();
        if (code == 204 || code == 304) {
            return false;
        }
        String method = response.request().method();
        return !"HEAD".equalsIgnoreCase(method);
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        GzipSource source = new GzipSource(new Buffer().write(bytes));
        try {
            Buffer plain = new Buffer();
            source.read(plain, DEFAULT_MAX_BODY_BYTES);
            return plain.readByteArray();
        } finally {
            source.close();
        }
    }

    /**
     * 判断是不是可读文本。
     *
     * <p>采样 512 字节：Monitor 只取 64 字节，二进制文件开头恰好是一段 ASCII 就会被误判成文本，
     * 然后产生一个乱码大字符串存进库。
     */
    private static boolean looksLikeText(byte[] bytes) {
        int sampleSize = Math.min(bytes.length, TEXT_SAMPLE_BYTES);
        for (int index = 0; index < sampleSize; index++) {
            int value = bytes[index] & 0xFF;
            // 允许制表、换行、回车
            if (value == 0x09 || value == 0x0A || value == 0x0D) {
                continue;
            }
            // C0 控制字符（含 NUL）基本可以断定是二进制
            if (value < 0x20) {
                return false;
            }
        }
        try {
            Buffer buffer = new Buffer().write(bytes, 0, sampleSize);
            while (!buffer.exhausted()) {
                buffer.readUtf8CodePoint();
            }
            return true;
        } catch (EOFException notUtf8) {
            return false;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean isGzipEncoded(Headers headers) {
        String value = headers.get("Content-Encoding");
        return value != null && value.equalsIgnoreCase("gzip");
    }

    private static boolean hasUnknownContentEncoding(Headers headers) {
        String value = headers.get("Content-Encoding");
        if (value == null) {
            return false;
        }
        return !value.equalsIgnoreCase("identity") && !value.equalsIgnoreCase("gzip");
    }
}
