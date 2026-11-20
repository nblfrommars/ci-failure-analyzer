package com.nbl.cfa.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrchestratorStateMachineTest {

    private final OrchestratorStateMachine machine = new OrchestratorStateMachine();

    @Test
    void passBranchEndsAtPromotionApproval() {
        OrchestratorRun run = machine.initial("run-142");
        run = machine.transition(run, OrchestratorEvent.START_DEPLOY);
        run = machine.transition(run, OrchestratorEvent.DEPLOY_SUCCEEDED);
        run = machine.transition(run, OrchestratorEvent.TEST_PASSED);

        assertEquals(OrchestratorState.PROMOTE_PENDING, run.state());
    }

    @Test
    void appBugBranchRollsBackThenNeedsHuman() {
        OrchestratorRun run = machine.initial("run-143");
        run = machine.transition(run, OrchestratorEvent.START_DEPLOY);
        run = machine.transition(run, OrchestratorEvent.DEPLOY_SUCCEEDED);
        run = machine.transition(run, OrchestratorEvent.TEST_FAILED);
        run = machine.transition(run, OrchestratorEvent.TRIAGE_APP_BUG);
        run = machine.transition(run, OrchestratorEvent.ROLLBACK_SUCCEEDED);

        assertEquals(OrchestratorState.NEEDS_HUMAN, run.state());
    }

    @Test
    void invalidTransitionIsRejected() {
        OrchestratorRun run = machine.initial("run-144");

        assertThrows(IllegalStateException.class,
                () -> machine.transition(run, OrchestratorEvent.TEST_PASSED));
    }
}
