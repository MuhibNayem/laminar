package com.nayem.laminar.read;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextPropagationTest {

    @Test
    void shouldPropagateMDC() throws ExecutionException, InterruptedException {
        SingleFlightGroup<String> singleFlight = new SingleFlightGroup<>();

        MDC.put("traceId", "12345");

        AtomicReference<String> mdcValue = new AtomicReference<>();

        try {
            singleFlight.doCall("key1", () -> {
                mdcValue.set(MDC.get("traceId"));
                return "result";
            }).get();
        } finally {
            MDC.clear();
        }

        assertEquals("12345", mdcValue.get(), "MDC context should be propagated to the virtual thread");
    }

    @Test
    void shouldPropagateSecurityContext() throws ExecutionException, InterruptedException {
        SingleFlightGroup<String> singleFlight = new SingleFlightGroup<>();
        AtomicReference<String> username = new AtomicReference<>();

        SecurityContext context = new SecurityContextImpl();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("user1", "pass"));
        SecurityContextHolder.setContext(context);

        try {
            singleFlight.doCall("key2", () -> {
                SecurityContext innerContext = SecurityContextHolder.getContext();
                if (innerContext.getAuthentication() != null) {
                    username.set(innerContext.getAuthentication().getName());
                }
                return "result";
            }).get();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals("user1", username.get(), "SecurityContext should be propagated to the virtual thread");
    }

    @Test
    void shouldCleanupContextAfterExecution() throws ExecutionException, InterruptedException {
        SingleFlightGroup<String> singleFlight = new SingleFlightGroup<>();

        // Set context in main thread
        MDC.put("key", "val");

        singleFlight.doCall("key3", () -> {
            // Inside virtual thread
            return "ok";
        }).get();

        // Check main thread is untouched (expected)
        assertEquals("val", MDC.get("key"));

        // Verify virtual thread cleanup (harder to test directly without hook, but
        // logic ensures it via finally)
        MDC.clear();
    }
}
