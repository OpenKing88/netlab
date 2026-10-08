package io.github.netlab.capture;

import java.lang.reflect.Method;

import okhttp3.Request;
import retrofit2.Invocation;

import io.github.netlab.internal.ApiDescriptions;

/**
 * 从 OkHttp 请求上读出"这是哪个 Retrofit 接口方法发的"。
 *
 * <p>原理：Retrofit 在构造请求时会把 {@link Invocation} 挂到 Request 的 tag 上
 * （见 retrofit2 的 RequestFactory），里面带着 service / method / 实参。
 * 拦截器本来只看得到 HTTP 请求，靠这个才能跟接口定义对上号。
 *
 * <p>**必须容忍宿主没有 Retrofit**：这时 {@code Invocation} 类不存在，
 * 访问 class 字面量会抛 {@link NoClassDefFoundError}。它是 Error 不是 Exception，
 * 所以这里刻意 catch Throwable —— 抓包工具绝不能因为宿主没引 Retrofit 就崩。
 */
final class RetrofitInvocationReader {

    private RetrofitInvocationReader() {
    }

    static void applyTo(CaptureRecord record, Request request) {
        if (record == null || request == null) {
            return;
        }
        try {
            Invocation invocation = request.tag(Invocation.class);
            if (invocation == null) {
                return;
            }
            Class<?> service = invocation.service();
            if (service != null) {
                record.retrofitService = service.getName();
            }
            Method method = invocation.method();
            if (method != null) {
                record.retrofitMethod = method.getName();
            }
            record.apiDescription = ApiDescriptions.lookup(
                    record.retrofitService,
                    record.retrofitMethod
            );
        } catch (Throwable ignored) {
            // 宿主没引 Retrofit、或这个 tag 被宿主自己占用：静默降级
        }
    }
}
