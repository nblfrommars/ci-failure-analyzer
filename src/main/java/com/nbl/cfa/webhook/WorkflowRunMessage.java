package com.nbl.cfa.webhook;

public record WorkflowRunMessage(
        String deliveryId,
        Long installationId,
        String repoFullName,
        long runId,
        int runAttempt,
        String headSha,
        String headBranch,
        Integer pullRequestNumber,
        String status,
        String conclusion,
        String htmlUrl,
        String workflowPath,
        String workflowEvent) {
}
