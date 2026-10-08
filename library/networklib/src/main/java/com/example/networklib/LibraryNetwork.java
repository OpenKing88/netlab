package com.example.networklib;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * 模拟独立 library 模块里的网络层。
 *
 * <p>用途：验证 InstrumentationScope.PROJECT 是否只覆盖「应用插件的那个模块」——
 * 如果 app 模块单方面应用插件，这里的 OkHttpClient 是**不会**被插桩的。
 */
public final class LibraryNetwork {

    private LibraryNetwork() {
    }

    public static final Interceptor BUSINESS = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            return chain.proceed(chain.request());
        }
    };

    public static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .addInterceptor(BUSINESS)
            .build();
}
