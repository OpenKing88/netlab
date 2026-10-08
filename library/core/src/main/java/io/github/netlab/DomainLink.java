package io.github.netlab;

/**
 * 域名实际走的链路。
 *
 * <p>自动识别只能看到"配置里有哪些 URL 字符串"，看不到这个字符串最后被谁消费；
 * 而域名切换库只对自己挂载的那条链路有效。所以要把它标出来，避免面板给出会误导的选项。
 */
public enum DomainLink {

    /** 走 OkHttp：Retrofit、直连 OkHttp、Coil 等，由 application 拦截器改写。 */
    OKHTTP,

    /** 走 WebView 加载，由 loadUrl 插桩改写。 */
    WEBVIEW,

    /** 识别不出链路，切换可能不生效。 */
    UNKNOWN
}
