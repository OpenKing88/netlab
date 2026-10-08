package com.example.domainswitchverify.net

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * 模拟宿主真实的网络层写法：
 * 完全按照官方姿势 new Builder()、addInterceptor()、build()，
 * 里面**没有任何**域名切换相关的代码。
 */
object SampleNetwork {

    /** 宿主自己的业务拦截器，用来验证注入顺序（域名切换必须排在它前面）。 */
    private val businessInterceptor = Interceptor { chain ->
        chain.proceed(chain.request())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(businessInterceptor)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://api.example.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val interceptorNames: List<String>
        get() = client.interceptors.map { it.javaClass.simpleName }
}
