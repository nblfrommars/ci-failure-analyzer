package com.nbl.cfa.orchestrator;

public enum OrchestratorState {
    RECEIVED,
    DEPLOYING,
    TESTING,
    TRIAGING,
    ROLLING_BACK,
    HEALING,
    NEEDS_HUMAN,
    PROMOTE_PENDING,
    COMPLETED,
    FAILED
}
