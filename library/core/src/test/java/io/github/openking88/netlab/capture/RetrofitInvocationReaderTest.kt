package io.github.openking88.netlab.capture

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Invocation

private interface SampleApi {
    fun listOrders()
}

class RetrofitInvocationReaderTest {

    @Test
    fun `能从 Retrofit 的 Invocation tag 里读出接口与方法`() {
        val method = SampleApi::class.java.getMethod("listOrders")
        val request = Request.Builder()
            .url("https://api.example.com/v1/orders")
            .tag(Invocation::class.java, Invocation.of(method, emptyList<Any>()))
            .build()
        val record = CaptureRecord()

        RetrofitInvocationReader.applyTo(record, request)

        assertEquals(SampleApi::class.java.name, record.retrofitService)
        assertEquals("listOrders", record.retrofitMethod)
    }

    @Test
    fun `没有 Invocation tag 时安静跳过，不影响记录`() {
        val request = Request.Builder()
            .url("https://api.example.com/v1/ping")
            .build()
        val record = CaptureRecord()

        RetrofitInvocationReader.applyTo(record, request)

        assertNull(record.retrofitService)
        assertNull(record.retrofitMethod)
    }
}
