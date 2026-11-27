package com.newcrisp.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
class ArithmeticCaptchaService {
    static final Duration TTL = Duration.ofMinutes(3);
    private static final int MAX_CHALLENGES = 5000;
    private static final String[] PALETTE = {"#203b37", "#276b58", "#2d5a4c", "#4b5563", "#805a22"};

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();

    ArithmeticCaptchaService(Clock clock) {
        this.clock = clock;
    }

    CaptchaView issue() {
        Instant now = clock.instant();
        if (challenges.size() >= MAX_CHALLENGES) {
            challenges.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
            if (challenges.size() >= MAX_CHALLENGES) {
                challenges.clear();
            }
        }
        Expression expr = nextExpression();
        String id = UUID.randomUUID().toString();
        challenges.put(id, new Challenge(expr.answer(), now.plus(TTL)));
        String svg = renderSvg(expr.text());
        String dataUrl = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return new CaptchaView(id, dataUrl, (int) TTL.toSeconds());
    }

    boolean verifyAndConsume(String captchaId, String presentedAnswer) {
        if (captchaId == null || captchaId.isBlank() || presentedAnswer == null || presentedAnswer.isBlank()) {
            return false;
        }
        Challenge challenge = challenges.remove(captchaId.strip());
        if (challenge == null || !challenge.expiresAt().isAfter(clock.instant())) {
            return false;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(presentedAnswer.strip());
        } catch (NumberFormatException bad) {
            return false;
        }
        return parsed == challenge.answer();
    }

    Expression nextExpression() {
        int op = random.nextInt(4);
        return switch (op) {
            case 0 -> {
                int a = 1 + random.nextInt(20);
                int b = 1 + random.nextInt(20);
                yield new Expression(a + " + " + b + " = ?", a + b);
            }
            case 1 -> {
                int a = 5 + random.nextInt(26);
                int b = 1 + random.nextInt(a);
                yield new Expression(a + " - " + b + " = ?", a - b);
            }
            case 2 -> {
                int a = 2 + random.nextInt(8);
                int b = 2 + random.nextInt(8);
                yield new Expression(a + " × " + b + " = ?", a * b);
            }
            default -> {
                // Reverse construction guarantees divisor >= 2 (never 0) and exact integer quotient
                int divisor = 2 + random.nextInt(8);
                int quotient = 1 + random.nextInt(9);
                int dividend = divisor * quotient;
                yield new Expression(dividend + " ÷ " + divisor + " = ?", quotient);
            }
        };
    }

    private String renderSvg(String expression) {
        var sb = new StringBuilder(1024);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"156\" height=\"46\" viewBox=\"0 0 156 46\">");
        sb.append("<rect width=\"156\" height=\"46\" rx=\"8\" fill=\"#f0f5ee\"/>");
        for (int i = 0; i < 4; i++) {
            int x1 = random.nextInt(40);
            int y1 = 6 + random.nextInt(34);
            int cx = 45 + random.nextInt(66);
            int cy = 4 + random.nextInt(38);
            int x2 = 115 + random.nextInt(40);
            int y2 = 6 + random.nextInt(34);
            String color = PALETTE[random.nextInt(PALETTE.length)];
            sb.append("<path d=\"M").append(x1).append(' ').append(y1)
                    .append(" Q").append(cx).append(' ').append(cy)
                    .append(' ').append(x2).append(' ').append(y2)
                    .append("\" stroke=\"").append(color)
                    .append("\" stroke-opacity=\"0.22\" stroke-width=\"1.4\" fill=\"none\"/>");
        }
        for (int i = 0; i < 18; i++) {
            int cx = 6 + random.nextInt(144);
            int cy = 5 + random.nextInt(36);
            String color = PALETTE[random.nextInt(PALETTE.length)];
            sb.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy)
                    .append("\" r=\"1.2\" fill=\"").append(color).append("\" fill-opacity=\"0.28\"/>");
        }
        String compact = expression.replace(" ", "");
        int step = Math.max(15, 132 / Math.max(1, compact.length()));
        int x = 12;
        for (int i = 0; i < compact.length(); i++) {
            char ch = compact.charAt(i);
            int y = 29 + random.nextInt(7) - 3;
            int rot = random.nextInt(25) - 12;
            String color = PALETTE[random.nextInt(PALETTE.length)];
            sb.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" font-family=\"Georgia, 'Times New Roman', serif\" font-size=\"21\" font-weight=\"700\" fill=\"")
                    .append(color).append("\" transform=\"rotate(").append(rot).append(' ').append(x).append(' ').append(y).append(")\">")
                    .append(escapeXml(ch)).append("</text>");
            x += step;
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private static String escapeXml(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            default -> String.valueOf(c);
        };
    }

    record Expression(String text, int answer) {}
    private record Challenge(int answer, Instant expiresAt) {}
    record CaptchaView(String captchaId, String imageDataUrl, int expiresInSeconds) {}
}
