package com.ticketing.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Validates Keycloak access tokens: RS256 signature (keys fetched from the JWKS endpoint), expiry,
 * the issuer, and that the token is an ACCESS token issued to our client. The issuer is the PUBLIC
 * URL users logged in through; the JWKS is fetched from the in-cluster Keycloak address over TLS,
 * trusting the cluster CA (SSL bundle).
 */
@Configuration
@Profile("!test")
class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(RestTemplateBuilder builder,
                          SslBundles sslBundles,
                          @Value("${ticketing.security.issuer}") String issuer,
                          @Value("${ticketing.security.jwk-set-uri}") String jwkSetUri,
                          @Value("${ticketing.security.client-ids:ticketing-ui,ticketing-mcp}") Set<String> clientIds,
                          @Value("${ticketing.security.audience:ticketing-api}") String audience,
                          @Value("${ticketing.security.audience-exempt-clients:ticketing-ui}") Set<String> audienceExempt,
                          @Value("${ticketing.security.ssl-bundle:}") String sslBundle) {
        RestTemplateBuilder rest = sslBundle.isBlank() ? builder : builder.sslBundle(sslBundles.getBundle(sslBundle));
        RestTemplate restTemplate = rest.build();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(restTemplate).build();
        decoder.setJwtValidator(validator(issuer, clientIds, audience, audienceExempt));
        return decoder;
    }

    /**
     * Keycloak signs ID tokens with the same key and issuer as access tokens, and ours carry the
     * same email/groups claims, so signature + issuer alone would accept an ID token as a bearer
     * token. Keycloak marks the token type in {@code typ} ("Bearer" vs "ID") and the client it was
     * issued to in {@code azp}; tokens obtained through any client not on the list are refused.
     *
     * <p>Audience: a token must name this API in {@code aud} (RFC 8707 / MCP authorization: a token issued
     * for another resource cannot be replayed here). Clients on the exempt list are allowed without it; that
     * is the web UI, whose tokens have never carried {@code aud} (documented gap, docs/08). The MCP client
     * ({@code ticketing-mcp}) is not exempt.
     */
    static OAuth2TokenValidator<Jwt> validator(String issuer, Set<String> clientIds, String audience,
                                               Set<String> audienceExempt) {
        OAuth2TokenValidator<Jwt> audienceCheck = jwt -> {
            String azp = jwt.getClaimAsString("azp");   // may be absent: then the azp validator rejects the token
            return (azp != null && audienceExempt.contains(azp))
                        || (jwt.getAudience() != null && jwt.getAudience().contains(audience))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                "The token was not issued for this API (aud must contain " + audience + ")", null));
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<String>("typ", "Bearer"::equals),
                new JwtClaimValidator<String>("azp", azp -> azp != null && clientIds.contains(azp)),
                audienceCheck);
    }
}
