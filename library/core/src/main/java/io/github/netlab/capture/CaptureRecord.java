package io.github.netlab.capture;

import io.github.netlab.internal.ApiDescriptions;

/**
 * 一条抓包记录。
 *
 * <p>刻意做成可变 DTO：请求发出时先建出来放进列表（状态 Requesting，界面能立刻看到），
 * 响应回来后就地补齐。字段用 public 是为了让 Kotlin 侧（Compose UI）直接读，省掉几十个 getter。
 */
public final class CaptureRecord {

    // 生命周期状态
    public static final String STATE_REQUESTING = "requesting";
    public static final String STATE_COMPLETED = "completed";
    public static final String STATE_FAILED = "failed";

    // body 状态
    public static final String BODY_EMPTY = "empty";
    public static final String BODY_CAPTURED = "captured";
    public static final String BODY_OMITTED = "omitted";
    public static final String BODY_TOO_LARGE = "too_large";

    /** 由存储分配的序号，越大越新。 */
    public volatile long id = -1L;

    public volatile String state = STATE_REQUESTING;

    public volatile String method = "";
    public volatile String url = "";
    public volatile String host = "";
    public volatile String pathWithQuery = "";

    /** Retrofit 接口的全限定名；不是 Retrofit 请求时为 null。 */
    public volatile String retrofitService;

    /** Retrofit 接口方法名。 */
    public volatile String retrofitMethod;

    /**
     * 接口说明，来自编译期提取的接口注释。
     *
     * <p>为阶段 2 预留：拿到之后列表会直接显示人话，而不是混淆过的路径。
     */
    public volatile String apiDescription;

    /** 域名切换前的原始 URL；没有发生切换时为 null。 */
    public volatile String originalUrl;

    public volatile long startedAtMillis;
    public volatile long finishedAtMillis;

    public volatile String requestHeaders = "";
    public volatile String requestContentType = "";
    public volatile long requestContentLength = -1L;
    public volatile String requestBodyState = BODY_EMPTY;
    public volatile String requestBody = "";

    public volatile int responseCode;
    public volatile String responseMessage = "";
    public volatile String responseHeaders = "";
    public volatile String responseContentType = "";
    public volatile long responseContentLength = -1L;
    public volatile String responseBodyState = BODY_EMPTY;
    public volatile String responseBody = "";

    public volatile String protocol = "";
    public volatile String tlsVersion = "";
    public volatile String cipherSuite = "";
    public volatile String error;

    public long durationMillis() {
        if (startedAtMillis <= 0L || finishedAtMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, finishedAtMillis - startedAtMillis);
    }

    public boolean isFinished() {
        return !STATE_REQUESTING.equals(state);
    }

    /** 这次请求的域名是否被改写。 */
    public boolean isRewritten() {
        return originalUrl != null && !originalUrl.isEmpty();
    }

    /**
     * 展示用的接口说明。
     *
     * <p>优先用记录时抓到的说明；**没有就按当前映射现查一次** —— 后者是为了让
     * "说明提取功能上线之前抓的历史记录"也能显示说明。
     *
     * <p>说明本质上是一张静态映射表（编译期从源码注释生成），跟"这一次请求发生了什么"
     * 无关，所以不该只依赖记录时存下来的那一份。
     */
    public String resolveDescription() {
        String stored = apiDescription;
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        return ApiDescriptions.lookup(retrofitService, retrofitMethod);
    }
}
