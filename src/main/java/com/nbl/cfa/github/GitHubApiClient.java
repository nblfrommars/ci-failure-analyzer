package com.nbl.cfa.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    public String openTestPullRequest(long installationId, String repoFullName, String baseBranch,
                                      String branch, String filePath, String content,
                                      String title, String body) {
        String baseSha = getRefSha(installationId, repoFullName, baseBranch);
        createBranch(installationId, repoFullName, branch, baseSha);
        putFile(installationId, repoFullName, branch, filePath, content,
                "Add AI-generated Playwright test");

        String url = API + "/repos/" + repoFullName + "/pulls";
        String requestBody = jsonMapper.writeValueAsString(new PullRequestRequest(title, body, branch, baseBranch));
        HttpResponse<String> response = send(authorizedPost(url, installationId, requestBody));
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Mở GitHub PR thất bại: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        PullRequestResponse pr = jsonMapper.readValue(response.body(), PullRequestResponse.class);
        return pr.htmlUrl();
    }

    private String getRefSha(long installationId, String repoFullName, String branch) {
        String url = API + "/repos/" + repoFullName + "/git/ref/heads/" + branch;
        HttpResponse<String> response = send(authorized(url, installationId));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Đọc branch gốc thất bại: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        RefResponse ref = jsonMapper.readValue(response.body(), RefResponse.class);
        return ref.object().sha();
    }

    private void createBranch(long installationId, String repoFullName, String branch, String sha) {
        String url = API + "/repos/" + repoFullName + "/git/refs";
        String requestBody = jsonMapper.writeValueAsString(new RefRequest("refs/heads/" + branch, sha));
        HttpResponse<String> response = send(authorizedPost(url, installationId, requestBody));
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Tạo branch test thất bại: HTTP "
                    + response.statusCode() + " " + response.body());
        }
    }

    private void putFile(long installationId, String repoFullName, String branch,
                         String filePath, String content, String message) {
        String url = API + "/repos/" + repoFullName + "/contents/" + filePath;
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String requestBody = jsonMapper.writeValueAsString(new FileRequest(message, encoded, branch));
        HttpResponse<String> response = send(authorizedPut(url, installationId, requestBody));
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IllegalStateException("Ghi test lên branch thất bại: HTTP "
                    + response.statusCode() + " " + response.body());
        }
    }

    private HttpRequest authorized(String url, long installationId) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + auth.installationToken(installationId))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
    }

    private HttpRequest authorizedPost(String url, long installationId, String body) {
        return authorizedWithBody(url, installationId, body, false);
    }

    private HttpRequest authorizedPut(String url, long installationId, String body) {
        return authorizedWithBody(url, installationId, body, true);
    }

    private HttpRequest authorizedWithBody(String url, long installationId, String body, boolean put) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + auth.installationToken(installationId))
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        var publisher = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return (put ? builder.PUT(publisher) : builder.POST(publisher)).build();
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

    private record RefResponse(RefObject object) {
    }

    private record RefObject(String sha) {
    }

    private record RefRequest(String ref, String sha) {
    }

    private record FileRequest(String message, String content, String branch) {
    }

    private record PullRequestRequest(String title, String body, String head, String base) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PullRequestResponse(@com.fasterxml.jackson.annotation.JsonProperty("html_url") String htmlUrl) {
    }
}
