package com.ticketing.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the edge correlation ID (set by the WAF, forwarded by Kong as {@code X-Correlation-ID}) into the
 * logging context, so every service log line of a request can be found from the ID the user sees.
 * The header is trusted only because the sole caller is the mTLS-pinned gateway; it is validated anyway.
 */
@Configuration
class ObservabilityConfig {

    static final String HEADER = "X-Correlation-ID";
    static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> correlationIdFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String id = request.getHeader(HEADER);
                if (id != null && VALID.matcher(id).matches()) {
                    MDC.put(MDC_KEY, id);
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove(MDC_KEY);
                }
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
