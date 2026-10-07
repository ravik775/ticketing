package com.ticketing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Only Keycloak ACCESS tokens issued to the known clients are accepted; ID tokens and other clients' tokens
 * are not. The MCP client's tokens must also be issued for this API ({@code aud}); the web UI is exempt.
 */
class JwtValidatorTest {

    private static final String ISSUER = "https://ticketing.localtest.me:8443/auth/realms/ticketing";
    private final OAuth2TokenValidator<Jwt> validator = JwtDecoderConfig.validator(
            ISSUER, Set.of("ticketing-ui", "ticketing-mcp"), "ticketing-api", Set.of("ticketing-ui"));

    private static Jwt token(String typ, String azp, String... audience) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuer(ISSUER).issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("email", "alice@ticketing.test");
        if (typ != null) {
            b.claim("typ", typ);
        }
        if (azp != null) {
            b.claim("azp", azp);
        }
        if (audience.length > 0) {
            b.audience(List.of(audience));
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
        assertThat(validator.validate(token("Bearer", "admin-cli", "ticketing-api")).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithoutTypeOrClient() {
        assertThat(validator.validate(token(null, "ticketing-ui")).hasErrors()).isTrue();
        assertThat(validator.validate(token("Bearer", null)).hasErrors()).isTrue();
    }

    @Test
    void mcpClientTokenMustBeIssuedForThisApi() {
        assertThat(validator.validate(token("Bearer", "ticketing-mcp", "ticketing-api")).hasErrors()).isFalse();
        assertThat(validator.validate(token("Bearer", "ticketing-mcp")).hasErrors()).isTrue();
        assertThat(validator.validate(token("Bearer", "ticketing-mcp", "another-api")).hasErrors()).isTrue();
    }
}
