package com.nbl.cfa.approval;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(TelegramClient.class)
public class TelegramApprovalService {

    private final TelegramClient telegram;
    private final String chatId;
    private final Duration timeout;
    private final Clock clock;
    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    public TelegramApprovalService(TelegramClient telegram,
                                   @Value("${cfa.telegram.chat-id:}") String chatId,
                                   @Value("${cfa.telegram.approval-timeout-minutes:30}") long timeoutMinutes) {
        this(telegram, chatId, timeoutMinutes, Clock.systemUTC());
    }

    TelegramApprovalService(TelegramClient telegram, String chatId, long timeoutMinutes, Clock clock) {
        if (timeoutMinutes < 1 || timeoutMinutes > 1440) {
            throw new IllegalArgumentException("Telegram approval timeout phải trong khoảng 1..1440 phút");
        }
        this.telegram = telegram;
        this.chatId = chatId;
        this.timeout = Duration.ofMinutes(timeoutMinutes);
        this.clock = clock;
    }

    public void requestApproval(String taskId, String repo, String summary) {
        Instant expiresAt = Instant.now(clock).plus(timeout);
        pending.put(taskId, new PendingApproval(repo, expiresAt));
        telegram.sendMessage(chatId, "Promote production?\nTask: " + taskId
                + "\nRepo: " + repo + "\n" + summary
                + "\nApprove: /approve " + taskId
                + "\nReject: /reject " + taskId);
    }

    public ApprovalDecision handleCommand(String command, String taskId) {
        PendingApproval request = pending.get(taskId);
        if (request == null) {
            return ApprovalDecision.UNKNOWN;
        }
        if (!Instant.now(clock).isBefore(request.expiresAt())) {
            pending.remove(taskId);
            return ApprovalDecision.EXPIRED;
        }
        if ("/approve".equalsIgnoreCase(command)) {
            pending.remove(taskId);
            return ApprovalDecision.APPROVED;
        }
        if ("/reject".equalsIgnoreCase(command)) {
            pending.remove(taskId);
            return ApprovalDecision.REJECTED;
        }
        return ApprovalDecision.UNKNOWN;
    }

    public enum ApprovalDecision { APPROVED, REJECTED, EXPIRED, UNKNOWN }

    private record PendingApproval(String repo, Instant expiresAt) {
    }
}
