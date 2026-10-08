package io.github.openking88.netlab.internal;

import android.content.Context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.openking88.netlab.DomainSwitch;

/**
 * 接口说明表：编译期由 Gradle 插件从源码注释里提取，运行期按「接口全名#方法名」查表。
 *
 * <p>为什么要这张表：KDoc/Javadoc 不会被编译进字节码，运行期拿不到；
 * 而拦截器能通过 Retrofit 的 Invocation 拿到"哪个接口的哪个方法"，
 * 两者一拼，就能把混淆过的路径换成一句人话。
 */
public final class ApiDescriptions {

    private static final String RESOURCE_NAME = "domain_switch_api_descriptions";

    private static volatile Map<String, String> cache;

    private ApiDescriptions() {
    }

    /** 初始化时清缓存：插件生成的资源是按渠道覆盖的，换渠道要重读。 */
    public static void invalidate() {
        cache = null;
    }

    public static String lookup(String serviceName, String methodName) {
        if (serviceName == null || methodName == null) {
            return null;
        }
        Map<String, String> table = cache;
        if (table == null) {
            table = load();
            cache = table;
        }
        return table.get(serviceName + "#" + methodName);
    }

    private static Map<String, String> load() {
        Context context = DomainSwitch.applicationContext();
        if (context == null) {
            return Collections.emptyMap();
        }
        String raw = ResourceConfig.getString(context, RESOURCE_NAME, "");
        if (raw.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> table = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String entry = line.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            table.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return Collections.unmodifiableMap(table);
    }
}
