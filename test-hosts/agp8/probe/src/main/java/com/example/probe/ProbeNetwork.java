package com.example.probe;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import retrofit2.Retrofit;

/**
 * 模拟宿主网络层：完全按官方姿势写，**没有任何**域名切换相关代码。
 */
public final class ProbeNetwork {

    private ProbeNetwork() {
    }

    /** 宿主自己的业务拦截器，用于验证注入顺序。 */
    public static final Interceptor BUSINESS_INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            return chain.proceed(chain.request());
        }
    };

    public static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .addInterceptor(BUSINESS_INTERCEPTOR)
            .build();

    public static final Retrofit RETROFIT = new Retrofit.Builder()
            .baseUrl("https://api.example.com/")
            .client(CLIENT)
            .build();
}
