package com.vwaves.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * StartupState is the tiny handshake between the bootstrap thread and the
 * readiness probe: a phase string for humans and a ready flag for k8s. The
 * contract is just its initial values and that writes are observed by reads.
 */
class StartupStateTest {

    @Test
    void startsInInitializingPhaseAndNotReady() {
        StartupState state = new StartupState();
        assertEquals("initializing", state.phase());
        assertFalse(state.ready());
    }

    @Test
    void phaseReflectsTheLatestWrite() {
        StartupState state = new StartupState();
        state.phase("loading-model");
        assertEquals("loading-model", state.phase());
        state.phase("opening-kb");
        assertEquals("opening-kb", state.phase());
    }

    @Test
    void readyFlagCanBeRaisedAndLowered() {
        StartupState state = new StartupState();
        state.ready(true);
        assertTrue(state.ready());
        state.ready(false);
        assertFalse(state.ready());
    }

    @Test
    void phaseAndReadyAreIndependent() {
        StartupState state = new StartupState();
        state.phase("ready");
        assertFalse(state.ready(), "setting the phase text must not flip the ready flag");
        state.ready(true);
        assertEquals("ready", state.phase());
        assertTrue(state.ready());
    }
}
