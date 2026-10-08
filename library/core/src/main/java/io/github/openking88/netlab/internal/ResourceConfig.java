package io.github.openking88.netlab.internal;

import android.content.Context;

/**
 * 读取构建期由 Gradle 插件生成的配置资源。
 *
 * <p>为什么要走资源：插件的 `domainSwitch { }` 是**按渠道**生效的，而资源天然就是渠道级的；
 * 这样可以做到"devTest 渠道开抓包、preProduct 渠道关掉"，不需要运行时再写开关代码。
 *
 * <p>用 `getIdentifier` 而不是 R 常量：core 里声明的只是默认值，真实值由宿主渠道覆盖，
 * 这样在"没装插件"或"资源缺失"时也能安全退化成默认值。
 */
public final class ResourceConfig {

    private ResourceConfig() {
    }

    public static String getString(Context context, String name, String defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        try {
            int resId = context.getResources()
                    .getIdentifier(name, "string", context.getPackageName());
            if (resId == 0) {
                return defaultValue;
            }
            String value = context.getString(resId);
            return value == null ? defaultValue : value;
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(Context context, String name, boolean defaultValue) {
        return Boolean.parseBoolean(getString(context, name, String.valueOf(defaultValue)));
    }

    public static int getInt(Context context, String name, int defaultValue) {
        try {
            return Integer.parseInt(getString(context, name, String.valueOf(defaultValue)).trim());
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }
}
