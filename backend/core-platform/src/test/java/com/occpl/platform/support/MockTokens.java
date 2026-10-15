package com.occpl.platform.support;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Registers fake bearer tokens on a mocked {@link JwtDecoder}; everything after decoding is real. */
public final class MockTokens {

    private final JwtDecoder decoder;

    public MockTokens(JwtDecoder decoder) {
        this.decoder = decoder;
        willThrow(new BadJwtException("unknown test token")).given(decoder).decode(anyString());
    }

    /** Token value == name; subject "sub-" + email; verified email. */
    public void register(String token, String email, boolean mfa) {
        willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject("sub-" + email)
                .claim("email", email).claim("email_verified", true).claim("name", token)
                .claim("amr", mfa ? List.of("pwd", "otp") : List.of("pwd"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build())
                .given(decoder).decode(token);
    }

    /** A client-credentials token of a product service: Keycloak puts the client in {@code client_id}. */
    public void registerService(String token, String clientId) {
        willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject("service-account-" + clientId)
                .claim("client_id", clientId).claim("azp", clientId)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build())
                .given(decoder).decode(token);
    }
}
