package com.nbl.cfa.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Xác thực GitHub App: ký JWT (RS256) bằng private key, đổi lấy installation token và cache lại.
 */
@Component
public class GitHubAppAuth {

    private static final String API = "https://api.github.com";

    private final String appId;
    private final String privateKeyPath;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<Long, CachedToken> cache = new ConcurrentHashMap<>();
    private volatile PrivateKey privateKey;

    public GitHubAppAuth(@Value("${github.app-id:}") String appId,
                         @Value("${github.private-key-path:}") String privateKeyPath,
                         JsonMapper jsonMapper) {
        this.appId = appId;
        this.privateKeyPath = privateKeyPath;
        this.jsonMapper = jsonMapper;
    }

    public String installationToken(long installationId) {
        CachedToken cached = cache.get(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cached.token();
        }
        CachedToken fresh = requestInstallationToken(installationId);
        cache.put(installationId, fresh);
        return fresh.token();
    }

    private CachedToken requestInstallationToken(long installationId) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(API + "/app/installations/" + installationId + "/access_tokens"))
                .header("Authorization", "Bearer " + createJwt())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Lấy installation token thất bại: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        TokenResponse parsed = jsonMapper.readValue(response.body(), TokenResponse.class);
        return new CachedToken(parsed.token(), Instant.parse(parsed.expiresAt()));
    }

    private String createJwt() {
        if (appId.isBlank()) {
            throw new IllegalStateException("Thiếu github.app-id (biến GITHUB_APP_ID)");
        }
        try {
            long now = Instant.now().getEpochSecond();
            String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64(("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540)
                    + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(loadPrivateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + b64(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Không ký được JWT cho GitHub App", e);
        }
    }

    private PrivateKey loadPrivateKey() throws Exception {
        PrivateKey key = privateKey;
        if (key != null) {
            return key;
        }
        if (privateKeyPath.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu github.private-key-path (biến GITHUB_APP_PRIVATE_KEY_PATH)");
        }
        String pem = Files.readString(Path.of(privateKeyPath));
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(
                    "Key đang ở định dạng PKCS#1, cần đổi sang PKCS#8 (BEGIN PRIVATE KEY)");
        }
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        privateKey = key;
        return key;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Lỗi mạng khi gọi GitHub", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị ngắt khi gọi GitHub", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String token, @JsonProperty("expires_at") String expiresAt) {
    }
}