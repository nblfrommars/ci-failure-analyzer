package com.nbl.cfa.webhook;

import com.nbl.cfa.messaging.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/webhooks")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final JsonMapper jsonMapper;
    private final RabbitTemplate rabbitTemplate;

    public GitHubWebhookController(WebhookSignatureVerifier verifier,
                                   JsonMapper jsonMapper,
                                   RabbitTemplate rabbitTemplate) {
        this.verifier = verifier;
        this.jsonMapper = jsonMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostMapping("/github")
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body) {

        if (!verifier.isValid(body, signature)) {
            log.warn("Webhook bị từ chối: chữ ký không hợp lệ hoặc thiếu");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }
        if ("ping".equals(event)) {
            return ResponseEntity.ok("pong");
        }
        if (!"workflow_run".equals(event)) {
            return ResponseEntity.accepted().body("ignored event");
        }

        WorkflowRunEvent payload;
        try {
            payload = jsonMapper.readValue(new String(body, StandardCharsets.UTF_8), WorkflowRunEvent.class);
        } catch (RuntimeException e) {
            log.warn("Payload workflow_run không đọc được (delivery={})", deliveryId, e);
            return ResponseEntity.badRequest().body("invalid payload");
        }

        if (!"completed".equals(payload.action())
                || payload.workflowRun() == null
                || payload.repository() == null) {
            return ResponseEntity.accepted().body("ignored action");
        }

        WorkflowRunEvent.WorkflowRun run = payload.workflowRun();
        List<WorkflowRunEvent.PullRequest> prs = run.pullRequests();
        Integer prNumber = (prs == null || prs.isEmpty()) ? null : prs.get(0).number();
        Long installationId = payload.installation() == null ? null : payload.installation().id();

        WorkflowRunMessage message = new WorkflowRunMessage(
                deliveryId,
                installationId,
                payload.repository().fullName(),
                run.id(),
                run.runAttempt(),
                run.headSha(),
                run.headBranch(),
                prNumber,
                run.status(),
                run.conclusion(),
                run.htmlUrl(),
                run.path(),
                run.event());

        rabbitTemplate.convertAndSend(RabbitConfig.WORKFLOW_RUN_QUEUE, jsonMapper.writeValueAsString(message));
        log.info("Đã đưa vào hàng đợi: repo={} run={} attempt={} conclusion={}",
                message.repoFullName(), message.runId(), message.runAttempt(), message.conclusion());

        return ResponseEntity.accepted().body("queued");
    }
}
