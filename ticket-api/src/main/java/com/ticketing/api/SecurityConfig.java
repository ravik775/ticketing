package com.ticketing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.core.TenantPolicyService;
import com.ticketing.core.TenantUsageMeter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Zero Trust at the service: even though Kong already verified the JWT, this service verifies it
 * again, checks the caller's mTLS identity, resolves tenant + roles, enforces per-tenant quotas and
 * method security.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Management endpoints live on a separate, plain-HTTP port (9090) used only by the kubelet probes and
     * Prometheus; a NetworkPolicy limits who can reach it. Only health and metrics are exposed, read-only,
     * no business data.
     */
    @Bean
    @Order(1)
    SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtDecoder jwtDecoder,
                                            ObjectMapper mapper,
                                            TenantPolicyService tenantPolicies,
                                            TenantUsageMeter tenantUsage,
                                            @Value("${ticketing.mtls.enabled:true}") boolean mtlsEnabled,
                                            @Value("${ticketing.mtls.allowed-callers:kong-gateway}") String allowedCallers)
            throws Exception {
        Set<String> callers = Arrays.stream(allowedCallers.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        http.csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/api/**").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwtDecoder)))
                // Filters are created with `new` (not @Bean) so Boot does not also register them globally.
                .addFilterBefore(new MtlsCallerFilter(mtlsEnabled, callers, mapper), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantContextFilter(mapper), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TenantQuotaFilter(tenantPolicies, tenantUsage, mapper), TenantContextFilter.class);
        return http.build();
    }
}
