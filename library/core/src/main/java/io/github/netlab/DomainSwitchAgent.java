package io.github.netlab;

import android.webkit.WebView;

import java.util.Map;

import okhttp3.OkHttpClient;

import io.github.netlab.capture.CaptureInterceptor;

/**
 * 插桩目标。
 *
 * <p>⚠️ 本类名、方法名、方法签名是 Gradle 插件写入字节码时使用的约定，
 * 一旦改动会导致已编译产物 NoSuchMethodError，必须保持向后兼容。
 */
public final class DomainSwitchAgent {

    private DomainSwitchAgent() {
    }

    /**
     * 由 Gradle 插件在 {@code new OkHttpClient.Builder()} 之后自动调用。
     *
     * <p>任何异常都必须被吞掉：域名切换库绝不能因为自身问题让宿主的请求失败。
     */
    public static void install(OkHttpClient.Builder builder) {
        if (builder == null) {
            return;
        }
        try {
            builder.addInterceptor(DomainSwitchInterceptor.INSTANCE);
            // 抓包排在域名改写之后：记录到的是改写后真正发出去的地址，
            // 同时靠 Request.tag 保留原始域名，界面上能显示"原域名 → 实际域名"
            builder.addInterceptor(CaptureInterceptor.INSTANCE);
        } catch (Throwable ignored) {
            // 静默降级：宿主请求照常发出
        }
    }

    /**
     * 由 Gradle 插件在 `new OkHttpClient()` 与 `OkHttpClient.Builder.build()` 之后自动调用。
     *
     * <p>为什么还需要这两个插桩点：`OkHttpClient()` 无参构造内部会调用 `new Builder()`，
     * 而那段字节码在 okhttp 自己的类里，宿主的类看不到，只靠 {@link #install} 会漏掉
     * `OkHttpClient().newBuilder()...` 这种最常见的写法。
     *
     * <p>OkHttpClient 是不可变对象，只能把"刚创建出来的实例"替换成已经带上拦截器的新实例。
     * 返回值一定是带拦截器的那个，调用方不需要关心是原实例还是新实例。
     *
     * <p>顺序说明：OkHttp 没有"插入到首位"的公开 API，这里只能追加。当实例本来就是刚
     * `new OkHttpClient()` 出来的（拦截器列表为空）时，结果仍是第 0 位；只有在实例已经
     * 自带拦截器时才会排到最后。两种情况下改写都在 ConnectInterceptor 之前生效，
     * 差别仅限于排在前面的拦截器看到的是改写前的 URL。
     */
    public static OkHttpClient installClient(OkHttpClient client) {
        if (client == null) {
            return null;
        }
        try {
            if (client.interceptors().contains(DomainSwitchInterceptor.INSTANCE)) {
                return client;
            }
            return client.newBuilder()
                    .addInterceptor(DomainSwitchInterceptor.INSTANCE)
                    .addInterceptor(CaptureInterceptor.INSTANCE)
                    .build();
        } catch (Throwable ignored) {
            // 静默降级：至少原样返回，绝不能把 null 交给宿主
            return client;
        }
    }

    // ───────────────────────── WebView 链路的接管 ─────────────────────────
    //
    // WebView 的请求完全不走 OkHttp，拦截器对它一点办法都没有。
    // 这里把宿主自己的加载调用整体替换成下面的静态方法：
    // 参数列表与原方法完全一致，所以字节码里只需把 INVOKEVIRTUAL 换成 INVOKESTATIC，
    // 操作数栈的形状不用动。

    /** 对应 `WebView.loadUrl(String)`。 */
    public static void loadUrl(WebView webView, String url) {
        if (webView == null) {
            return;
        }
        webView.loadUrl(rewriteSafely(url));
    }

    /** 对应 `WebView.loadUrl(String, Map)`。 */
    public static void loadUrl(WebView webView, String url, Map<String, String> additionalHttpHeaders) {
        if (webView == null) {
            return;
        }
        webView.loadUrl(rewriteSafely(url), additionalHttpHeaders);
    }

    /** 对应 `WebView.loadDataWithBaseURL(...)`，改写的是 baseUrl，相对路径会跟着走。 */
    public static void loadDataWithBaseURL(
            WebView webView,
            String baseUrl,
            String data,
            String mimeType,
            String encoding,
            String historyUrl
    ) {
        if (webView == null) {
            return;
        }
        webView.loadDataWithBaseURL(
                rewriteSafely(baseUrl),
                data,
                mimeType,
                encoding,
                historyUrl
        );
    }

    private static String rewriteSafely(String url) {
        try {
            return DomainSwitch.rewriteUrl(url);
        } catch (Throwable ignored) {
            // 任何解析异常都不能拦住宿主加载页面
            return url;
        }
    }
}
