package com.ticketing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Only Keycloak ACCESS tokens issued to the UI client are accepted; ID tokens and other clients' tokens are not. */
class JwtValidatorTest {

    private static final String ISSUER = "https://ticketing.localtest.me:8443/auth/realms/ticketing";
    private final OAuth2TokenValidator<Jwt> validator = JwtDecoderConfig.validator(ISSUER, "ticketing-ui");

    private static Jwt token(String typ, String azp) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(ISSUER).issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("email", "alice@ticketing.test");
        if (typ != null) {
            b.claim("typ", typ);
        }
        if (azp != null) {
            b.claim("azp", azp);
        }
        return b.build();
    }

    @Test
    void acceptsAccessTokenOfTheUiClient() {
        assertThat(validator.validate(token("Bearer", "ticketing-ui")).hasErrors()).isFalse();
    }

    @Test
    void rejectsIdToken() {
        assertThat(validator.validate(token("ID", "ticketing-ui")).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenOfAnotherClient() {
        assertThat(validator.validate(token("Bearer", "admin-cli")).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithoutTypeOrClient() {
        assertThat(validator.validate(token(null, "ticketing-ui")).hasErrors()).isTrue();
        assertThat(validator.validate(token("Bearer", null)).hasErrors()).isTrue();
    }
}
