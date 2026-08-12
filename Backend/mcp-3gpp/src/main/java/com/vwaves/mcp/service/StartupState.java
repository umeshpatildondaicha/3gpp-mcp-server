package com.vwaves.mcp.service;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class StartupState {
    private final AtomicReference<String> phase = new AtomicReference<>("initializing");
    private volatile boolean ready;

    public String phase() {
        return phase.get();
    }

    public void phase(String value) {
        phase.set(value);
    }

    public boolean ready() {
        return ready;
    }

    public void ready(boolean value) {
        this.ready = value;
    }
}
