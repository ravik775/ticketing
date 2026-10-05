package com.ticketing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Zero Trust, transport layer. Tomcat already requires a client certificate signed by our CA
 * (server.ssl.client-auth=need). That alone would let ANY workload holding a cluster-issued cert in,
 * so this filter additionally pins the caller identity (certificate CN) to an allow-list: only the
 * gateway may call this service.
 */
class MtlsCallerFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MtlsCallerFilter.class);
    private static final Pattern CN = Pattern.compile("(?:^|,)\\s*CN=([^,]+)", Pattern.CASE_INSENSITIVE);

    private final boolean enabled;
    private final Set<String> allowedCallers;
    private final ObjectMapper mapper;

    MtlsCallerFilter(boolean enabled, Set<String> allowedCallers, ObjectMapper mapper) {
        this.enabled = enabled;
        this.allowedCallers = allowedCallers;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        String caller = callerCommonName(request);
        if (caller == null || !allowedCallers.contains(caller)) {
            log.warn("Rejected call from untrusted workload identity '{}'", caller);
            Problems.write(mapper, response, HttpStatus.FORBIDDEN, "Caller workload identity is not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String callerCommonName(HttpServletRequest request) {
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) {
            return null;
        }
        Matcher m = CN.matcher(chain[0].getSubjectX500Principal().getName());
        return m.find() ? m.group(1).trim() : null;
    }
}
