package com.newcrisp.identity;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
class EmailVerificationService {
    static final String PURPOSE_SIGNUP = "SIGNUP";
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration EMAIL_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_DAILY_PER_EMAIL = 10;
    private static final int MAX_PER_IP_10M = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final Logger LOG = LoggerFactory.getLogger(EmailVerificationService.class);

    private final SecureRandom random = new SecureRandom();
    private final EmailVerificationStore store;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Clock clock;
    private final String mailFrom;

    EmailVerificationService(EmailVerificationStore store,
                             ObjectProvider<JavaMailSender> mailSenderProvider,
                             Clock clock,
                             @Value("${spring.mail.username:}") String mailFrom) {
        this.store = store;
        this.mailSenderProvider = mailSenderProvider;
        this.clock = clock;
        this.mailFrom = mailFrom == null ? "" : mailFrom.strip();
    }

    void sendSignupCode(String rawEmail, String remoteAddr) {
        String email = normalize(rawEmail);
        String ip = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr.strip();
        if (ip.length() > 64) ip = ip.substring(0, 64);

        LocalDateTime now = LocalDateTime.now(clock);
        if (store.countRecentByEmail(email, PURPOSE_SIGNUP, now.minus(EMAIL_COOLDOWN)) > 0) {
            throw new IdentityError(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CODE_COOLDOWN");
        }
        if (store.countRecentByEmail(email, PURPOSE_SIGNUP, now.minusHours(24)) >= MAX_DAILY_PER_EMAIL) {
            throw new IdentityError(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CODE_DAILY_LIMIT");
        }
        if (store.countRecentByIp(ip, now.minusMinutes(10)) >= MAX_PER_IP_10M) {
            throw new IdentityError(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CODE_RATE_LIMIT");
        }

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || mailFrom.isEmpty()) {
            throw new IdentityError(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_NOT_CONFIGURED");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("【NewCrisp】您的工作空间注册验证码");
        message.setText("您好！\n\n您正在 NewCrisp 免费创建多租户客服工作空间，邮箱验证码为：\n\n"
                + code + "\n\n验证码 5 分钟内有效，请勿向他人泄露。如非本人操作请忽略此邮件。");
        try {
            sender.send(message);
        } catch (MailException ex) {
            LOG.warn("Failed to send signup verification email to {}", email, ex);
            throw new IdentityError(HttpStatus.BAD_GATEWAY, "MAIL_SEND_FAILED");
        }

        store.insert(UUID.randomUUID().toString(), email, PURPOSE_SIGNUP,
                hash(email, PURPOSE_SIGNUP, code), ip, now, now.plus(CODE_TTL));
    }

    void verifyAndConsumeSignupCode(String rawEmail, String rawCode) {
        if (rawCode == null || !rawCode.strip().matches("\\d{6}")) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE");
        }
        String email = normalize(rawEmail);
        String code = rawCode.strip();
        LocalDateTime now = LocalDateTime.now(clock);
        var record = store.latestActive(email, PURPOSE_SIGNUP, now)
                .orElseThrow(() -> new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE"));
        if (record.attempts() >= MAX_ATTEMPTS) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE");
        }
        if (!MessageDigest.isEqual(hash(email, PURPOSE_SIGNUP, code), record.codeHash())) {
            store.incrementAttempts(record.id());
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE");
        }
        if (!store.consume(record.id(), now, MAX_ATTEMPTS)) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE");
        }
    }

    static byte[] hash(String email, String purpose, String code) {
        return IdentityTokens.digest("email-code:" + purpose + ":" + email + ":" + code);
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
