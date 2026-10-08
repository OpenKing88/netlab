package com.example.domainswitchverify

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.openking88.netlab.DomainSwitch

private const val TAG = "DomainSwitchWebView"
private const val H5_BASE = "h5.example.com"
private const val H5_TARGET = "h5-test.internal"

/**
 * WebView 链路的验证探针。
 *
 * 宿主的写法就是最普通的 `webView.loadUrl(...)`，没有任何域名切换相关代码；
 * 如果插件把调用点换成了 DomainSwitchAgent.loadUrl，这里加载的就会是改写后的域名。
 */
class DevTestWebViewProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DomainSwitch.setTarget(H5_BASE, H5_TARGET)

        val webView = WebView(this)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Log.i(TAG, "WebView 实际请求的 URL = $url")
            }
        }
        setContentView(webView)

        Log.i(TAG, "宿主调用 loadUrl(\"https://$H5_BASE/help\")")
        webView.loadUrl("https://$H5_BASE/help")
    }

    override fun onDestroy() {
        DomainSwitch.setTarget(H5_BASE, null)
        super.onDestroy()
    }
}
