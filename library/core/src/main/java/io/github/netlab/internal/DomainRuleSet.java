package io.github.netlab.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.github.netlab.DomainRule;
import okhttp3.HttpUrl;

/**
 * 不可变规则集合。
 *
 * <p>幂等性保证：key 只允许是"基线域名"。目标域名不会被再匹配一次，
 * 因此同一个请求即使经过多个改写环节也不会被反复改写。
 */
public final class DomainRuleSet {

    public static final DomainRuleSet EMPTY = new DomainRuleSet(Collections.<String, DomainRule>emptyMap());

    private final Map<String, DomainRule> rules;

    private DomainRuleSet(Map<String, DomainRule> rules) {
        this.rules = rules;
    }

    public static DomainRuleSet of(Map<String, DomainRule> rules) {
        if (rules.isEmpty()) {
            return EMPTY;
        }
        Map<String, DomainRule> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, DomainRule> entry : rules.entrySet()) {
            normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return new DomainRuleSet(Collections.unmodifiableMap(normalized));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * 无命中时**返回原对象**，调用方据此判断是否发生了改写，避免无意义地重建 Request。
     */
    public HttpUrl map(HttpUrl url) {
        DomainRule rule = rules.get(url.host().toLowerCase(Locale.ROOT));
        if (rule == null) {
            return url;
        }
        return rule.rewrite(url);
    }

    /**
     * 只做 host 级别的查表，供不走 OkHttp 的链路（WebView 等）复用同一套规则。
     *
     * @return 命中的目标 host；没有命中返回 {@code null}
     */
    public String mapHost(String host) {
        if (host == null) {
            return null;
        }
        DomainRule rule = rules.get(host.toLowerCase(Locale.ROOT));
        return rule == null ? null : rule.targetHost;
    }
}
