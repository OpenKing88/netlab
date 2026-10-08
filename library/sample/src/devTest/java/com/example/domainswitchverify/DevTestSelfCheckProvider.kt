package com.example.domainswitchverify

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.example.domainswitchverify.net.SampleNetwork
import io.github.netlab.DomainSwitch
import io.github.netlab.capture.CaptureStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

private const val TAG = "DomainSwitchVerify"

/**
 * 渠道自检：在应用启动时打印实际生效的拦截器顺序、自动识别到的域名与改写结果。
 *
 * 全程只读宿主的真实配置，演示用的规则挂在独立域名上，不会覆盖面板里配的映射。
 * 这段代码只存在于 devTest 渠道，`prodSample` 渠道不会编译到它。
 */
class DevTestSelfCheckProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        runCatching {
            Log.i(TAG, "拦截器顺序 = ${SampleNetwork.interceptorNames}")
            // ContentProvider 的创建顺序不确定：宿主的 provider 可能先于本库的 InitProvider，
            // 所以这里先读一次（可能为空），初始化完成后再读一次。
            Log.i(TAG, "自动识别的配置域名(库初始化前) = ${DomainSwitch.configuredHosts()}")
            Log.i(TAG, "启动时的生效映射 = ${DomainSwitch.targets()}")

            val untouched = "https://api.example.com/v1/user".toHttpUrl()
            Log.i(TAG, "无规则时不改动: $untouched -> ${DomainSwitch.rewrite(untouched)}（同一对象=${DomainSwitch.rewrite(untouched) === untouched}）")

            Thread {
                Thread.sleep(300)
                Log.i(TAG, "自动识别的配置域名(库初始化后) = ${DomainSwitch.configuredHosts()}")
                Log.i(TAG, "初始化后的生效映射 = ${DomainSwitch.targets()}")

                DomainSwitch.addCustomTarget("https://custom-target.example.org/live")
                Log.i(TAG, "自动识别 + 自定义的候选列表 = ${DomainSwitch.targetOptions()}")

                // 端到端：走真实 OkHttp 请求链路，观察最终被访问的主机名。
                // 用独立域名做演示，避免覆盖面板里配置的映射。
                val probe = "https://self-check.example.com/ping".toHttpUrl()
                DomainSwitch.setTarget("self-check.example.com", "self-check.internal")
                Log.i(TAG, "演示改写: $probe -> ${DomainSwitch.rewrite(probe)}")
                val outcome = runCatching {
                    SampleNetwork.client
                        .newCall(Request.Builder().url(probe).build())
                        .execute()
                        .close()
                    "请求成功了（不应该发生）"
                }.exceptionOrNull()?.message
                Log.i(TAG, "真实请求链路的失败信息: $outcome")

                // 抓包：上面这次失败的请求也应该被记录下来
                val store = CaptureStore.peek()
                Log.i(
                    TAG,
                    "抓包配置: enabled=${store?.isEnabled} maxRecords=${store?.maxRecords()} " +
                            "maxBodyBytes=${store?.maxBodyBytes()}"
                )
                val records = store?.records().orEmpty()
                Log.i(TAG, "已录制 ${records.size} 条记录")
                records.firstOrNull()?.let { record ->
                    Log.i(TAG, "最新一条: ${record.method} ${record.url} state=${record.state} error=${record.error}")
                }
                DomainSwitch.setTarget("self-check.example.com", null)
            }.start()
        }.onFailure { Log.e(TAG, "自检失败", it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
