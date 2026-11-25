package com.newcrisp.identity;

import com.newcrisp.identity.ChatIdentity.Side;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AccessJwt {
    private static final JOSEObjectType TYPE = new JOSEObjectType("at+jwt");
    private final Clock clock;
    private final Duration ttl;
    private final String issuer;
    private final String kid;
    private final RSASSASigner signer;
    private final Map<String, RSASSAVerifier> verifiers;

    AccessJwt(Clock clock, IdentityConfiguration.Settings settings,
            @Value("${NEWCRISP_JWT_KID:initial}") String kid,
            @Value("${NEWCRISP_JWT_PRIVATE_KEY:}") String privateKey,
            @Value("${NEWCRISP_JWT_PREVIOUS_KID:}") String previousKid,
            @Value("${NEWCRISP_JWT_PREVIOUS_PUBLIC_KEY:}") String previousPublicKey,
            @Value("${NEWCRISP_ACCESS_TTL:5m}") Duration ttl) throws GeneralSecurityException, JOSEException {
        this.clock = clock;
        this.issuer = settings.publicOrigin();
        this.kid = keyId(kid);
        this.ttl = ttl;
        if (ttl.compareTo(Duration.ofSeconds(2)) < 0 || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Access TTL must be between 2 and 300 seconds");
        }
        var keys = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateRsa;
        if (privateKey.isBlank()) {
            if (!List.of("127.0.0.1", "localhost").contains(URI.create(issuer).getHost())) {
                throw new IllegalArgumentException("A durable JWT signing key is required outside loopback development");
            }
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            privateRsa = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
        } else {
            privateRsa = (RSAPrivateCrtKey) keys.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
        }
        if (privateRsa.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA signing key is too short");
        signer = new RSASSASigner(privateRsa);
        var publicKey = (RSAPublicKey) keys.generatePublic(new RSAPublicKeySpec(privateRsa.getModulus(), privateRsa.getPublicExponent()));
        Map<String, RSASSAVerifier> allowed = new HashMap<>();
        allowed.put(kid, new RSASSAVerifier(publicKey));
        if (!previousKid.isEmpty() || !previousPublicKey.isEmpty()) {
            if (kid.equals(keyId(previousKid))) throw new IllegalArgumentException("JWT signing key ids must differ");
            var previous = (RSAPublicKey) keys.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(previousPublicKey)));
            if (previous.getModulus().bitLength() < 2048) throw new IllegalArgumentException("Previous RSA key is too short");
            allowed.put(previousKid, new RSASSAVerifier(previous));
        }
        verifiers = Map.copyOf(allowed);
    }

    Token sign(Subject subject, Instant sessionExpiry) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plus(ttl).isBefore(sessionExpiry) ? now.plus(ttl) : sessionExpiry.truncatedTo(ChronoUnit.SECONDS);
        if (!expires.isAfter(clock.instant())) throw IdentityError.unauthorized();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience(subject.side())).subject(subject.id())
                .claim("sid", subject.sid()).claim("tenant", Long.toString(subject.tenantId()))
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).expirationTime(Date.from(expires)).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(TYPE).keyID(kid).build(), claims);
        try { signed.sign(signer); }
        catch (JOSEException failure) { throw new IllegalStateException("JWT signing failed", failure); }
        return new Token(signed.serialize(), expires, subject.sid());
    }

    Access verify(String raw, Side side) {
        if (raw == null || raw.length() > 4096) throw IdentityError.unauthorized();
        try {
            var token = SignedJWT.parse(raw);
            var header = token.getHeader();
            var verifier = header.getKeyID() == null ? null : verifiers.get(header.getKeyID());
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || !TYPE.equals(header.getType())
                    || verifier == null || !token.verify(verifier)) throw IdentityError.unauthorized();
            var claims = token.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer()) || !List.of(audience(side)).equals(claims.getAudience())
                    || claims.getIssueTime() == null || claims.getExpirationTime() == null
                    || claims.getJWTID() == null || !claims.getExpirationTime().after(claims.getIssueTime())
                    || claims.getIssueTime().toInstant().isAfter(clock.instant())
                    || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(clock.instant()))) {
                throw IdentityError.unauthorized();
            }
            Instant expires = claims.getExpirationTime().toInstant();
            if (!clock.instant().isBefore(expires)) throw IdentityError.unauthorized();
            long tenant = Long.parseLong(claims.getStringClaim("tenant"));
            if (tenant < 1) throw IdentityError.unauthorized();
            return new Access(new Subject(side, tenant, canonicalUuid(claims.getSubject()), canonicalUuid(claims.getStringClaim("sid"))), expires);
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException invalid) {
            throw IdentityError.unauthorized();
        }
    }

    private static String audience(Side side) { return side == Side.STAFF ? "newcrisp-staff" : "newcrisp-customer"; }
    private static String keyId(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid JWT key id");
        return value;
    }
    private static String canonicalUuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw IdentityError.unauthorized();
        return value;
    }

    record Subject(Side side, long tenantId, String id, String sid) {}
    record Token(String accessToken, Instant expiresAt, String sessionId) {
        @Override public String toString() { return "Token[expiresAt=" + expiresAt + "]"; }
    }
    record Access(Subject subject, Instant expiresAt) {}
}
