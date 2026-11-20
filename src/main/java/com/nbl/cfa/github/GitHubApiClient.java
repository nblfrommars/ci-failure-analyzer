package com.nbl.cfa.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gọi GitHub REST API: lấy danh sách job của một lần chạy workflow và tải log từng job.
 */
@Component
public class GitHubApiClient {

    private static final String API = "https://api.github.com";

    private final GitHubAppAuth auth;
    private final JsonMapper jsonMapper;
    // Không tự động theo redirect: log job trả về 302 sang link tải tạm, link đó không được kèm token.
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GitHubApiClient(GitHubAppAuth auth, JsonMapper jsonMapper) {
        this.auth = auth;
        this.jsonMapper = jsonMapper;
    }

    public List<Job> listJobs(long installationId, String repoFullName, long runId, int runAttempt) {
        String url = API + "/repos/" + repoFullName + "/actions/runs/" + runId
                + "/attempts/" + runAttempt + "/jobs?per_page=100";
        HttpResponse<String> response = send(authorized(url, installationId));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Lấy danh sách job thất bại: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        return jsonMapper.readValue(response.body(), JobsResponse.class).jobs();
    }

    public String downloadJobLog(long installationId, String repoFullName, long jobId) {
        String url = API + "/repos/" + repoFullName + "/actions/jobs/" + jobId + "/logs";
        HttpResponse<String> response = send(authorized(url, installationId));

        if (response.statusCode() == 302) {
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Redirect log không có Location"));
            response = send(HttpRequest.newBuilder(URI.create(location)).GET().build());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tải log job " + jobId + " thất bại: HTTP "
                    + response.statusCode());
        }
        return response.body();
    }

    private HttpRequest authorized(String url, long installationId) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + auth.installationToken(installationId))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Job(long id, String name, String conclusion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobsResponse(List<Job> jobs) {
    }
}