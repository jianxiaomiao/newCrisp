package com.newcrisp.identity;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityConfiguration.Settings.class)
class IdentityConfiguration {
    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder staffPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @ConfigurationProperties("newcrisp.identity")
    record Settings(String publicOrigin, String platformKeySha256, String nexusVerifyUrl,
                    String nexusVerifyKey, Duration nexusVerificationTtl) {
        Settings {
            requireOrigin(publicOrigin);
            if (!platformKeySha256.isEmpty() && !platformKeySha256.matches("[a-fA-F0-9]{64}")) {
                throw new IllegalArgumentException("Invalid platform credential digest");
            }
            if (!nexusVerifyUrl.isEmpty() || !nexusVerifyKey.isEmpty()) {
                URI uri = trustedHttpUri(nexusVerifyUrl);
                if (!"/api/internal/support/chat/session-status".equals(uri.getRawPath())
                        || !IdentityTokens.valid(nexusVerifyKey)) {
                    throw new IllegalArgumentException("Invalid Nexus session verification configuration");
                }
            }
            if (nexusVerificationTtl.isNegative() || nexusVerificationTtl.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Nexus verification TTL must be between 0 and 60 seconds");
            }
        }

        byte[] platformDigest() {
            return HexFormat.of().parseHex(platformKeySha256);
        }
    }

    static String requireOrigin(String value) {
        URI uri = trustedHttpUri(value);
        if (!uri.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("Origin must contain only a scheme, host and optional port");
        }
        return value;
    }

    private static URI trustedHttpUri(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Expected HTTPS or a loopback HTTP endpoint");
        }
        return uri;
    }
}
