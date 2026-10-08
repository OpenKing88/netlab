package io.github.openking88.netlab.capture;

import java.io.IOException;

import io.github.openking88.netlab.DomainSwitchTag;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 抓包拦截器。
 *
 * <p>由 Gradle 插件自动挂载，宿主不需要写任何代码。位置紧跟在域名改写拦截器之后，
 * 因此记录到的是**改写之后真正发出去的地址**，同时通过 {@link DomainSwitchTag}
 * 保留改写前的原始 URL，界面上能显示"原域名 → 实际域名"。
 */
public final class CaptureInterceptor implements Interceptor {

    public static final CaptureInterceptor INSTANCE = new CaptureInterceptor();

    private CaptureInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        CaptureStore store = CaptureStore.peek();
        Request original = chain.request();
        if (store == null || !store.isEnabled()) {
            return chain.proceed(original);
        }

        CaptureRecord record = null;
        CaptureSnapshot snapshot = null;
        try {
            long maxBodyBytes = store.maxBodyBytes();
            CaptureBody.RequestRead requestRead = CaptureBody.readRequest(original, maxBodyBytes);
            snapshot = new CaptureSnapshot(
                    requestRead.body,
                    CaptureBody.charsetOf(original.body() == null ? null : original.body().contentType())
            );
            record = buildPendingRecord(store, original, requestRead);
            store.add(record);

            Response response = chain.proceed(requestRead.request);
            snapshot.responseBody = CaptureBody.peekResponse(response, maxBodyBytes);
            snapshot.responseCharset = CaptureBody.charsetOf(
                    response.body() == null ? null : response.body().contentType()
            );
            complete(record, response);
            store.complete(record, snapshot);
            return response;
        } catch (IOException | RuntimeException error) {
            if (record != null) {
                record.state = CaptureRecord.STATE_FAILED;
                record.error = error.getClass().getSimpleName() + ": " + error.getMessage();
                record.finishedAtMillis = System.currentTimeMillis();
                store.complete(record, snapshot);
            }
            throw error;
        }
    }

    private CaptureRecord buildPendingRecord(
            CaptureStore store,
            Request request,
            CaptureBody.RequestRead requestRead
    ) {
        CaptureRecord record = new CaptureRecord();
        record.id = store.nextId();
        record.state = CaptureRecord.STATE_REQUESTING;
        record.startedAtMillis = System.currentTimeMillis();
        record.method = request.method();
        record.url = request.url().toString();
        record.host = request.url().host();
        record.pathWithQuery = request.url().encodedPath()
                + (request.url().query() == null ? "" : "?" + request.url().query());
        record.requestHeaders = CaptureBody.headersToText(request.headers());
        record.requestContentType = requestRead.contentType;
        record.requestContentLength = requestRead.contentLength;
        record.requestBodyState = requestRead.body.state;

        // 把请求和 Retrofit 接口定义对上号（非 Retrofit 请求会静默跳过）
        RetrofitInvocationReader.applyTo(record, request);

        DomainSwitchTag tag = request.tag(DomainSwitchTag.class);
        if (tag != null && tag.originalUrl != null) {
            record.originalUrl = tag.originalUrl.toString();
        }
        return record;
    }

    private void complete(CaptureRecord record, Response response) {
        record.state = CaptureRecord.STATE_COMPLETED;
        record.finishedAtMillis = System.currentTimeMillis();

        // 请求头要用「最终发出去的那个请求」覆盖一次。
        //
        // 我们挂在应用拦截器链靠前的位置（紧跟域名改写），好处是请求体拿到的是加密前的明文、
        // 响应体拿到的是解密后的明文；代价是宿主如果在内层拦截器里注入请求头（token、设备号、
        // 渠道这些），此刻还没加上。response.request() 是走到网络层的那一份，头是齐的。
        Request sentRequest = response.request();
        if (sentRequest != null) {
            record.requestHeaders = CaptureBody.headersToText(sentRequest.headers());
        }

        record.responseCode = response.code();
        record.responseMessage = response.message();
        record.responseHeaders = CaptureBody.headersToText(response.headers());
        record.responseContentType = response.body() == null || response.body().contentType() == null
                ? ""
                : response.body().contentType().toString();
        record.responseContentLength = response.body() == null ? -1L : response.body().contentLength();
        record.protocol = response.protocol() == null ? "" : response.protocol().toString();
        if (response.handshake() != null) {
            record.tlsVersion = response.handshake().tlsVersion() == null
                    ? ""
                    : response.handshake().tlsVersion().javaName();
            record.cipherSuite = response.handshake().cipherSuite() == null
                    ? ""
                    : response.handshake().cipherSuite().javaName();
        }
    }
}
