package io.github.openking88.netlab

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import io.github.openking88.netlab.capture.CaptureInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSwitchTest {

    @Test
    fun `install 让域名拦截器排在宿主业务拦截器之前`() {
        val hostInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }

        // 复刻插桩后的字节码顺序：
        // new OkHttpClient.Builder() -> DomainSwitchAgent.install(builder) -> 宿主 addInterceptor(...)
        val builder = OkHttpClient.Builder()
        DomainSwitchAgent.install(builder)
        builder.addInterceptor(hostInterceptor)
        val client = builder.build()

        assertEquals(DomainSwitchInterceptor::class.java, client.interceptors[0]::class.java)
        // 抓包拦截器紧跟域名改写之后
        assertEquals(CaptureInterceptor::class.java, client.interceptors[1]::class.java)
        assertSame(hostInterceptor, client.interceptors[2])
    }

    @Test
    fun `install 可重复调用而不重复注入`() {
        val builder = OkHttpClient.Builder()
        DomainSwitchAgent.install(builder)
        DomainSwitchAgent.install(builder)

        // 一次 install 挂两个（域名改写 + 抓包），两次 install 就是四个
        assertEquals(4, builder.build().interceptors.size)
    }

    @Test
    fun `installClient 补进无拦截器的实例时排在第 0 位且幂等`() {
        // 对应 `OkHttpClient()` 无参构造：刚建出来的实例拦截器列表是空的
        val patched = DomainSwitchAgent.installClient(OkHttpClient())

        assertEquals(DomainSwitchInterceptor::class.java, patched.interceptors[0]::class.java)
        // 幂等：已经带上就直接返回原实例
        assertSame(patched, DomainSwitchAgent.installClient(patched))
    }

    @Test
    fun `installClient 对已有拦截器的实例只追加，仍在连接前生效`() {
        val hostInterceptor = Interceptor { chain -> chain.proceed(chain.request()) }
        val base = OkHttpClient.Builder().addInterceptor(hostInterceptor).build()

        val patched = DomainSwitchAgent.installClient(base)

        // OkHttp 没有提供"插入到首位"的 API，只能追加；
        // 但 application 拦截器全部在 ConnectInterceptor 之前执行，域名改写照常生效，
        // 差别仅限于排在前面的拦截器看到的是改写前的 URL。
        assertSame(hostInterceptor, patched.interceptors[0])
        assertEquals(DomainSwitchInterceptor::class.java, patched.interceptors[1]::class.java)
        assertSame(patched, DomainSwitchAgent.installClient(patched))
    }

    @Test
    fun `没有规则时返回同一个实例，不构造新对象`() {
        DomainSwitch.clear()
        val original = "https://api.example.com/v1/user?id=1".toHttpUrl()

        val result = DomainSwitch.rewrite(original)

        assertSame(original, result)
    }

    @Test
    fun `命中规则时只替换 host，path 与 query 原样保留`() {
        DomainSwitch.clear()
        DomainSwitch.apply("api.example.com", "api.pre-test.internal")
        val original = "https://api.example.com/v1/user?id=1".toHttpUrl()

        val result = DomainSwitch.rewrite(original)

        assertEquals("https://api.pre-test.internal/v1/user?id=1", result.toString())
        DomainSwitch.clear()
    }

    @Test
    fun `改写是单向且幂等的，目标域名不会被再次改写`() {
        DomainSwitch.clear()
        DomainSwitch.apply("api.example.com", "api.pre-test.internal")
        val original = "https://api.example.com/v1/user".toHttpUrl()

        val once = DomainSwitch.rewrite(original)
        val twice = DomainSwitch.rewrite(once)

        assertEquals("https://api.pre-test.internal/v1/user", once.toString())
        assertSame(once, twice)
        DomainSwitch.clear()
    }

    @Test
    fun `未命中的域名不被改动`() {
        DomainSwitch.clear()
        DomainSwitch.apply("api.example.com", "api.pre-test.internal")
        val other = "https://third-party.example.org/ping".toHttpUrl()

        assertSame(other, DomainSwitch.rewrite(other))
        DomainSwitch.clear()
    }

    @Test
    fun `用户输入支持裸域名完整 URL 与端口三种写法`() {
        assertEquals("test.example.com", DomainSwitch.normalizeHost("test.example.com"))
        assertEquals("test.example.com", DomainSwitch.normalizeHost("  https://test.example.com/v1/user?x=1  "))
        assertEquals("test.example.com", DomainSwitch.normalizeHost("test.example.com:8080"))
        assertEquals("test.example.com", DomainSwitch.normalizeHost("TEST.Example.COM"))
        assertNull(DomainSwitch.normalizeHost("   "))
        assertNull(DomainSwitch.normalizeHost(null))
    }

    @Test
    fun `自定义域名去重后进入候选列表`() {
        DomainSwitch.clear()

        assertTrue(DomainSwitch.addCustomTarget("https://test-a.example.com/live"))
        assertTrue(DomainSwitch.addCustomTarget("test-b.example.com"))
        // 重复添加（含等价写法）应被忽略
        assertFalse(DomainSwitch.addCustomTarget("TEST-A.example.com"))

        assertEquals(
            listOf("test-a.example.com", "test-b.example.com"),
            DomainSwitch.targetOptions()
        )

        assertTrue(DomainSwitch.removeCustomTarget("test-a.example.com"))
        assertEquals(listOf("test-b.example.com"), DomainSwitch.targetOptions())
        DomainSwitch.clear()
    }

    @Test
    fun `目标与基线相同时视为还原，不产生映射`() {
        DomainSwitch.clear()

        DomainSwitch.setTarget("api.example.com", "api.example.com")
        assertTrue(DomainSwitch.targets().isEmpty())

        DomainSwitch.setTarget("api.example.com", "api.pre-test.internal")
        assertEquals("api.pre-test.internal", DomainSwitch.targetOf("api.example.com"))

        // 传空表示取消这条映射
        DomainSwitch.setTarget("api.example.com", null)
        assertNull(DomainSwitch.targetOf("api.example.com"))
        DomainSwitch.clear()
    }

    @Test
    fun `字符串改写只替换 host，其余原样保留`() {
        DomainSwitch.clear()
        DomainSwitch.apply("h5.example.com", "h5-test.internal")

        assertEquals(
            "https://h5-test.internal/a/b?x=1#f",
            DomainSwitch.rewriteUrl("https://h5.example.com/a/b?x=1#f")
        )
        // 端口保留
        assertEquals(
            "http://h5-test.internal:8080/p",
            DomainSwitch.rewriteUrl("http://h5.example.com:8080/p")
        )
        // 未命中不动
        assertEquals(
            "https://other.example.org/p",
            DomainSwitch.rewriteUrl("https://other.example.org/p")
        )
        // 非 http(s) 原样返回
        assertEquals("about:blank", DomainSwitch.rewriteUrl("about:blank"))
        assertEquals("javascript:void(0)", DomainSwitch.rewriteUrl("javascript:void(0)"))
        assertNull(DomainSwitch.rewriteUrl(null))
        DomainSwitch.clear()
    }

    @Test
    fun `运行期观测到的 host 会被判定为 OkHttp 链路`() {
        DomainSwitch.clear()

        DomainSwitch.recordObservedHost("api.example.com")

        assertEquals(DomainLink.OKHTTP, DomainSwitch.linkOf("api.example.com"))
        assertTrue(DomainSwitch.isObservedOnOkHttp("api.example.com"))
        assertFalse(DomainSwitch.isObservedOnOkHttp("never-seen.example.org"))
        // 没观测到、也没有编译期分类信息时是未知链路
        assertEquals(DomainLink.UNKNOWN, DomainSwitch.linkOf("never-seen.example.org"))
    }

    @Test
    fun `删除自定义候选时，指向它的映射一并还原`() {
        DomainSwitch.clear()
        DomainSwitch.addCustomTarget("test.example.com")
        DomainSwitch.setTarget("api.example.com", "test.example.com")
        assertEquals("test.example.com", DomainSwitch.targetOf("api.example.com"))

        assertTrue(DomainSwitch.removeCustomTarget("test.example.com"))

        // 候选没了，映射也不能留着 —— 否则指针指向一个列表里不存在的域名
        assertNull(DomainSwitch.targetOf("api.example.com"))
        assertFalse(DomainSwitch.customTargets().contains("test.example.com"))
        DomainSwitch.clear()
    }
}
