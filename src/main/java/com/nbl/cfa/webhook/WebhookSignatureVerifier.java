package com.nbl.cfa.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public WebhookSignatureVerifier(@Value("${github.webhook-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Chưa đặt biến môi trường GITHUB_WEBHOOK_SECRET (webhook secret của GitHub App)");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    ///Kiểm tra chữ ký trên đúng các byte thô GitHub gửi, so sánh theo thời gian không đổi.
    public boolean isValid(byte[] body, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false; // phần hex trong header không hợp lệ
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}