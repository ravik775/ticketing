package com.ticketing.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
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
                          @Value("${ticketing.security.client-id:ticketing-ui}") String clientId,
                          @Value("${ticketing.security.ssl-bundle:}") String sslBundle) {
        RestTemplateBuilder rest = sslBundle.isBlank() ? builder : builder.sslBundle(sslBundles.getBundle(sslBundle));
        RestTemplate restTemplate = rest.build();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(restTemplate).build();
        decoder.setJwtValidator(validator(issuer, clientId));
        return decoder;
    }

    /**
     * Keycloak signs ID tokens with the same key and issuer as access tokens, and ours carry the
     * same email/groups claims, so signature + issuer alone would accept an ID token as a bearer
     * token. Keycloak marks the token type in {@code typ} ("Bearer" vs "ID") and the client it was
     * issued to in {@code azp}; tokens obtained through any other client of the realm are refused.
     */
    static OAuth2TokenValidator<Jwt> validator(String issuer, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<String>("typ", "Bearer"::equals),
                new JwtClaimValidator<String>("azp", clientId::equals));
    }
}
