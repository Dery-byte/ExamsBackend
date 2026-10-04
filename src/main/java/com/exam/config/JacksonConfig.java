package com.exam.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets any entity be written as JSON even when Hibernate hands back a lazy-loading proxy for it
 * (a lazy link, or an entity first loaded as a reference earlier in the same request).
 * Proxies carry two internal properties, "hibernateLazyInitializer" and "handler", that Jackson
 * cannot write ("Type definition error: ... ByteBuddyInterceptor"). Several fields already skipped
 * them one by one with @JsonIgnoreProperties; this skips them on every proxy. Only proxies are
 * touched: ordinary objects and maps keep every property and key.
 */
@Configuration
public class JacksonConfig {

    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    abstract static class IgnoreHibernateProxyInternals {}

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer ignoreHibernateProxyInternals() {
        return builder -> builder.mixIn(HibernateProxy.class, IgnoreHibernateProxyInternals.class);
    }
}
