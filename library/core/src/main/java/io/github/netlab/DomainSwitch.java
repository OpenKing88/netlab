package io.github.netlab;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.github.netlab.internal.DomainRuleSet;
import io.github.netlab.internal.ResourceConfig;
import okhttp3.HttpUrl;

/**
 * 域名切换对外 API。
 *
 * <p>线程安全：规则读取走 {@link AtomicReference}，拦截器在每次请求时读取，
 * 因此"改配置 → 下一个请求立即生效"，不需要重建 Retrofit / OkHttpClient，也不需要重启 App。
 */
public final class DomainSwitch {

    /** 与插件写入的资源名保持一致，改动即为破坏性变更。 */
    private static final String HOSTS_RESOURCE_NAME = "domain_switch_hosts";

    private static final String PREFS_NAME = "domain_switch";
    private static final String TARGET_KEY_PREFIX = "target:";
    private static final String CUSTOM_TARGETS_KEY = "custom_targets";

    private static final AtomicReference<Map<String, DomainRule>> RULES =
            new AtomicReference<>(Collections.<String, DomainRule>emptyMap());

    private static final AtomicReference<List<String>> CUSTOM_TARGETS =
            new AtomicReference<>(Collections.<String>emptyList());

    private static volatile Context applicationContext;

    /**
     * 该渠道配置里识别出的域名 → 它走的链路。
     *
     * <p>只有拿到 Context 后才缓存，避免"初始化之前读到空结果"被固化。
     */
    private static volatile Map<String, DomainLink> configuredHosts;

    /**
     * 运行期实际在 OkHttp 链路上观测到的 host。
     *
     * <p>编译期只能按字段名猜链路，运行期这个观测可以纠正它 —— 某个域名真的在 OkHttp 上出现过，
     * 那它一定可切换，不管它叫什么名字。
     */
    // 不用 ConcurrentHashMap.newKeySet()：那个要 API 24，本库 minSdk 21
    private static final Set<String> OBSERVED_HOSTS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private DomainSwitch() {
    }

    /** 由 InitProvider 调用，宿主不需要写任何初始化代码。 */
    public static void initialize(Context context) {
        if (context == null) {
            return;
        }
        applicationContext = context.getApplicationContext();
        // 初始化之前可能已经被读过一次（ContentProvider 的创建顺序不由我们决定），
        // 这里必须让缓存失效，否则会把"当时读不到"的空结果永久缓存下来。
        configuredHosts = null;
        io.github.netlab.internal.ApiDescriptions.invalidate();
        loadPersistedState();
    }

    public static Context applicationContext() {
        return applicationContext;
    }

    // ───────────────────────── 域名候选 ─────────────────────────

    /**
     * 构建期由插件从该渠道 buildConfigField 里自动识别出来的域名。
     *
     * <p>例如渠道里声明了 {@code BASE_URL = "https://api.example.com"}，
     * 这里就会拿到 {@code api.example.com}，不需要再人工维护一份清单。
     */
    public static List<String> configuredHosts() {
        Map<String, DomainLink> cached = configuredHosts;
        if (cached == null) {
            cached = readConfiguredHosts();
            // 只有在已经拿到 Context、确实读到了结果时才缓存，
            // 避免在初始化之前把空列表固化成"最终答案"。
            if (applicationContext != null) {
                configuredHosts = cached;
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(cached.keySet()));
    }

    /** 该域名实际走哪条链路。运行时观测优先于编译期的字段名初判。 */
    public static DomainLink linkOf(String rawHost) {
        String host = normalizeHost(rawHost);
        if (host == null) {
            return DomainLink.UNKNOWN;
        }
        if (OBSERVED_HOSTS.contains(host)) {
            return DomainLink.OKHTTP;
        }
        DomainLink declared = configuredHosts().isEmpty()
                ? null
                : configuredHosts.get(host);
        return declared == null ? DomainLink.UNKNOWN : declared;
    }

    /** 该 host 是否在 OkHttp 链路上真实出现过。 */
    public static boolean isObservedOnOkHttp(String rawHost) {
        String host = normalizeHost(rawHost);
        return host != null && OBSERVED_HOSTS.contains(host);
    }

    /** 拦截器内部调用：记录这条链路真实走过的 host。 */
    public static void recordObservedHost(String host) {
        if (host != null && !host.isEmpty()) {
            OBSERVED_HOSTS.add(host.toLowerCase(Locale.ROOT));
        }
    }

    /** 可供选择的目标域名 = 自动识别的域名 + 用户自己添加的域名。 */
    public static List<String> targetOptions() {
        Set<String> options = new LinkedHashSet<>(configuredHosts());
        options.addAll(CUSTOM_TARGETS.get());
        return Collections.unmodifiableList(new ArrayList<>(options));
    }

    public static List<String> customTargets() {
        return CUSTOM_TARGETS.get();
    }

    /** 添加一个自定义目标域名；支持直接粘贴完整 URL，会自动归一化成 host。 */
    public static boolean addCustomTarget(String rawHost) {
        String host = normalizeHost(rawHost);
        if (host == null) {
            return false;
        }
        List<String> current = CUSTOM_TARGETS.get();
        if (current.contains(host)) {
            return false;
        }
        List<String> next = new ArrayList<>(current);
        next.add(host);
        CUSTOM_TARGETS.set(Collections.unmodifiableList(next));
        persistCustomTargets();
        return true;
    }

    public static boolean removeCustomTarget(String rawHost) {
        String host = normalizeHost(rawHost);
        if (host == null) {
            return false;
        }
        List<String> next = new ArrayList<>(CUSTOM_TARGETS.get());
        if (!next.remove(host)) {
            return false;
        }
        CUSTOM_TARGETS.set(Collections.unmodifiableList(next));
        persistCustomTargets();
        // 顺手把"指向这个域名的映射"也还原掉。
        // 否则会留下一条指向"已经不在候选列表里的域名"的规则，用户没有地方把它改回来。
        clearMappingsPointingTo(host);
        return true;
    }

    private static void clearMappingsPointingTo(String targetHost) {
        Map<String, DomainRule> current = RULES.get();
        Map<String, DomainRule> remaining = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, DomainRule> entry : current.entrySet()) {
            if (targetHost.equalsIgnoreCase(entry.getValue().targetHost)) {
                changed = true;
                removePersistedTarget(entry.getKey());
            } else {
                remaining.put(entry.getKey(), entry.getValue());
            }
        }
        if (changed) {
            RULES.set(Collections.unmodifiableMap(remaining));
        }
    }

    // ───────────────────────── 切换规则 ─────────────────────────

    /**
     * 把 {@code sourceHost} 的请求切到 {@code targetHost}。
     *
     * <p>{@code targetHost} 传空、或与 source 相同，表示取消这条映射（回到原始域名）。
     * 配置会持久化，重启后仍然生效。
     */
    public static void setTarget(String sourceHost, String targetHost) {
        String source = normalizeHost(sourceHost);
        if (source == null) {
            return;
        }
        String target = normalizeHost(targetHost);
        Map<String, DomainRule> next = new LinkedHashMap<>(RULES.get());
        if (target == null || target.equals(source)) {
            next.remove(source);
            removePersistedTarget(source);
        } else {
            next.put(source, new DomainRule(source, null, target, -1));
            persistTarget(source, target);
        }
        RULES.set(Collections.unmodifiableMap(next));
    }

    /** 兼容早期 API，语义等同于 {@link #setTarget(String, String)}。 */
    public static void apply(String sourceHost, String targetHost) {
        setTarget(sourceHost, targetHost);
    }

    /** 当前 sourceHost 指向的目标域名；未配置映射时返回 null。 */
    public static String targetOf(String sourceHost) {
        String source = normalizeHost(sourceHost);
        if (source == null) {
            return null;
        }
        DomainRule rule = RULES.get().get(source);
        return rule == null ? null : rule.targetHost;
    }

    /** 当前生效的全部映射（sourceHost → targetHost）。 */
    public static Map<String, String> targets() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (DomainRule rule : RULES.get().values()) {
            snapshot.put(rule.sourceHost, rule.targetHost);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** 取消所有映射并清除持久化配置。 */
    public static void clear() {
        RULES.set(Collections.<String, DomainRule>emptyMap());
        if (applicationContext != null) {
            prefs().edit().clear().apply();
        }
    }

    /** 是否记录网络请求。默认开启，关掉后拦截器直接放行，零开销。 */
    public static boolean isCaptureEnabled() {
        io.github.netlab.capture.CaptureStore store =
                io.github.netlab.capture.CaptureStore.peek();
        return store != null && store.isEnabled();
    }

    public static void setCaptureEnabled(boolean enabled) {
        io.github.netlab.capture.CaptureStore store =
                io.github.netlab.capture.CaptureStore.peek();
        if (store != null) {
            store.setEnabled(enabled);
        }
    }

    /**
     * 按当前规则改写 URL；无命中时**返回原对象**，调用方可用引用比较判断是否命中。
     *
     * <p>除了拦截器内部使用，这个 API 也用于 WebView / 图片加载等不走 OkHttp 的场景，
     * 让它们复用同一套规则。
     */
    public static HttpUrl rewrite(HttpUrl url) {
        return DomainRuleSet.of(RULES.get()).map(url);
    }

    /**
     * 字符串形式的改写，供不走 OkHttp 的链路使用（WebView 的 loadUrl 等）。
     *
     * <p>刻意不用 {@code HttpUrl.parse}：它在 OkHttp 4 是废弃 API、在 OkHttp 5 已被移除，
     * 而这个库要同时兼容两个大版本。这里只做 host 段替换，path / query / fragment / 端口 / userInfo
     * 全部原样保留；非 http(s) 的字符串（如 {@code about:blank}、{@code javascript:}）直接返回。
     */
    public static String rewriteUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = url.length();
        for (int index = authorityStart; index < url.length(); index++) {
            char current = url.charAt(index);
            if (current == '/' || current == '?' || current == '#') {
                authorityEnd = index;
                break;
            }
        }
        String authority = url.substring(authorityStart, authorityEnd);
        int userInfoEnd = authority.lastIndexOf('@');
        int hostStartInAuthority = userInfoEnd >= 0 ? userInfoEnd + 1 : 0;
        String hostAndPort = authority.substring(hostStartInAuthority);
        int portStart = hostAndPort.lastIndexOf(':');
        String host = portStart >= 0 ? hostAndPort.substring(0, portStart) : hostAndPort;

        String target = DomainRuleSet.of(RULES.get()).mapHost(host);
        if (target == null || target.equalsIgnoreCase(host)) {
            return url;
        }
        return url.substring(0, authorityStart + hostStartInAuthority)
                + target
                + url.substring(authorityStart + hostStartInAuthority + host.length());
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    /**
     * 把用户输入归一化成 host。
     *
     * <p>允许 {@code test.example.com}、{@code https://test.example.com/path}、
     * {@code test.example.com:8080} 三种写法。
     */
    public static String normalizeHost(String rawHost) {
        if (rawHost == null) {
            return null;
        }
        String value = rawHost.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("://")) {
            try {
                String host = URI.create(value).getHost();
                return host == null ? null : host.toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {
                return null;
            }
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static Map<String, DomainLink> readConfiguredHosts() {
        Context context = applicationContext;
        if (context == null) {
            return Collections.emptyMap();
        }
        try {
            String raw = ResourceConfig.getString(context, HOSTS_RESOURCE_NAME, "");
            if (raw == null || raw.trim().isEmpty()) {
                return Collections.emptyMap();
            }
            // 插件生成的格式是 "host=link"，link ∈ okhttp / webview / unknown
            Map<String, DomainLink> hosts = new LinkedHashMap<>();
            for (String piece : raw.split(",")) {
                int separator = piece.indexOf('=');
                String rawHost = separator >= 0 ? piece.substring(0, separator) : piece;
                String rawLink = separator >= 0 ? piece.substring(separator + 1) : "";
                String host = normalizeHost(rawHost);
                if (host != null && !hosts.containsKey(host)) {
                    hosts.put(host, parseLink(rawLink));
                }
            }
            return Collections.unmodifiableMap(hosts);
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private static DomainLink parseLink(String rawLink) {
        if ("okhttp".equalsIgnoreCase(rawLink)) {
            return DomainLink.OKHTTP;
        }
        if ("webview".equalsIgnoreCase(rawLink)) {
            return DomainLink.WEBVIEW;
        }
        return DomainLink.UNKNOWN;
    }

    private static void loadPersistedState() {
        SharedPreferences preferences = prefs();
        Map<String, DomainRule> mergedRules = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(TARGET_KEY_PREFIX) || !(entry.getValue() instanceof String)) {
                continue;
            }
            String source = normalizeHost(key.substring(TARGET_KEY_PREFIX.length()));
            String target = normalizeHost((String) entry.getValue());
            if (source != null && target != null && !source.equals(target)) {
                mergedRules.put(source, new DomainRule(source, null, target, -1));
            }
        }
        // 初始化可能晚于第一次调用（ContentProvider 创建顺序不确定），
        // 所以这里是"合并"而不是"覆盖"：内存里已有的规则更新，应当胜出。
        mergedRules.putAll(RULES.get());
        RULES.set(Collections.unmodifiableMap(mergedRules));

        List<String> mergedCustom = new ArrayList<>();
        String rawCustom = preferences.getString(CUSTOM_TARGETS_KEY, "");
        if (rawCustom != null) {
            for (String piece : rawCustom.split(",")) {
                String host = normalizeHost(piece);
                if (host != null && !mergedCustom.contains(host)) {
                    mergedCustom.add(host);
                }
            }
        }
        for (String host : CUSTOM_TARGETS.get()) {
            if (!mergedCustom.contains(host)) {
                mergedCustom.add(host);
            }
        }
        CUSTOM_TARGETS.set(Collections.unmodifiableList(mergedCustom));
    }

    private static void persistTarget(String sourceHost, String targetHost) {
        if (applicationContext == null) {
            return;
        }
        prefs().edit().putString(TARGET_KEY_PREFIX + sourceHost, targetHost).apply();
    }

    private static void removePersistedTarget(String sourceHost) {
        if (applicationContext == null) {
            return;
        }
        prefs().edit().remove(TARGET_KEY_PREFIX + sourceHost).apply();
    }

    private static void persistCustomTargets() {
        if (applicationContext == null) {
            return;
        }
        prefs().edit()
                .putString(CUSTOM_TARGETS_KEY, join(CUSTOM_TARGETS.get()))
                .apply();
    }

    private static SharedPreferences prefs() {
        return applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }
}
