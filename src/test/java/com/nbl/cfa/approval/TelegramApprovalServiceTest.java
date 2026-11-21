package com.nbl.cfa.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TelegramApprovalServiceTest {

    private static final Instant START = Instant.parse("2026-11-22T00:00:00Z");

    @Test
    void sendsApprovalRequestAndAcceptsApproveCommand() {
        TelegramClient telegram = org.mockito.Mockito.mock(TelegramClient.class);
        TelegramApprovalService service = service(telegram, 30, START);

        service.requestApproval("task-1", "nblfrommars/ci-sandbox", "E2E passed");

        verify(telegram).sendMessage("chat-1", "Promote production?\nTask: task-1\nRepo: nblfrommars/ci-sandbox\nE2E passed"
                + "\nApprove: /approve task-1\nReject: /reject task-1");
        assertEquals(TelegramApprovalService.ApprovalDecision.APPROVED,
                service.handleCommand("/approve", "task-1"));
    }

    @Test
    void rejectsRequestAndDoesNotAcceptItAgain() {
        TelegramClient telegram = org.mockito.Mockito.mock(TelegramClient.class);
        TelegramApprovalService service = service(telegram, 30, START);

        service.requestApproval("task-2", "demo/repo", "ready");

        assertEquals(TelegramApprovalService.ApprovalDecision.REJECTED,
                service.handleCommand("/reject", "task-2"));
        assertEquals(TelegramApprovalService.ApprovalDecision.UNKNOWN,
                service.handleCommand("/approve", "task-2"));
    }

    @Test
    void returnsUnknownForUnknownCommandOrTask() {
        TelegramClient telegram = org.mockito.Mockito.mock(TelegramClient.class);
        TelegramApprovalService service = service(telegram, 30, START);

        service.requestApproval("task-3", "demo/repo", "ready");

        assertEquals(TelegramApprovalService.ApprovalDecision.UNKNOWN,
                service.handleCommand("/maybe", "task-3"));
        assertEquals(TelegramApprovalService.ApprovalDecision.UNKNOWN,
                service.handleCommand("/approve", "missing"));
    }

    @Test
    void expiresApprovalAfterConfiguredTimeout() {
        TelegramClient telegram = org.mockito.Mockito.mock(TelegramClient.class);
        MutableClock clock = new MutableClock(START);
        TelegramApprovalService service = new TelegramApprovalService(telegram, "chat-1", 5, clock);

        service.requestApproval("task-4", "demo/repo", "ready");
        clock.advance(Duration.ofMinutes(5));

        assertEquals(TelegramApprovalService.ApprovalDecision.EXPIRED,
                service.handleCommand("/approve", "task-4"));
    }

    private static TelegramApprovalService service(TelegramClient telegram, long timeoutMinutes, Instant now) {
        return new TelegramApprovalService(telegram, "chat-1", timeoutMinutes,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
