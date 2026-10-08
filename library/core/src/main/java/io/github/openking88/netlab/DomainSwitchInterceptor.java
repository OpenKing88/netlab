package io.github.openking88.netlab;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 域名改写拦截器。
 *
 * <p>必须作为 **application interceptor 且排在第 0 位**：application 层在
 * ConnectInterceptor 之前执行，这里改 URL 才是"真的换目标"；network 层连接已建立，改 URL 无意义。
 */
public final class DomainSwitchInterceptor implements Interceptor {

    public static final DomainSwitchInterceptor INSTANCE = new DomainSwitchInterceptor();

    private DomainSwitchInterceptor() {
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        // 记录这条链路真实走过的 host，供面板区分"哪些域名确实走 OkHttp"
        DomainSwitch.recordObservedHost(request.url().host());
        HttpUrl mapped = DomainSwitch.rewrite(request.url());
        if (mapped == request.url()) {
            return chain.proceed(request);
        }
        Request.Builder builder = request.newBuilder().url(mapped);
        // 不覆盖宿主已有的 tag，避免侵入宿主行为
        if (request.tag(DomainSwitchTag.class) == null) {
            builder.tag(DomainSwitchTag.class, new DomainSwitchTag(request.url()));
        }
        return chain.proceed(builder.build());
    }
}
