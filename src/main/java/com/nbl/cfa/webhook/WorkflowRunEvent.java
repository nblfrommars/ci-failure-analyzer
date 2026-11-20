package com.nbl.cfa.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WorkflowRunEvent(
        String action,
        @JsonProperty("workflow_run") WorkflowRun workflowRun,
        Repository repository,
        Installation installation) {

    public record WorkflowRun(
            long id,
            @JsonProperty("run_attempt") int runAttempt,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("head_branch") String headBranch,
            String status,
            String conclusion,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("pull_requests") List<PullRequest> pullRequests) {
    }

    public record PullRequest(int number) {
    }

    public record Repository(@JsonProperty("full_name") String fullName) {
    }

    public record Installation(long id) {
    }
}