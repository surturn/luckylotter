package com.lucklotter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation ID on every request and every log line it produces
 * (NFR-4).
 *
 * <p>Honours an inbound {@code X-Correlation-Id} so a caller's ID survives into
 * these logs, and echoes it back on the response so a failing request can be
 * traced without guessing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Bounded: an unbounded inbound header would be a log-injection vector. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled; leaving this set would stamp the next
            // request with the previous one's ID.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String inbound) {
        if (inbound == null || inbound.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = inbound.length() > MAX_LENGTH ? inbound.substring(0, MAX_LENGTH) : inbound;
        // Anything else could smuggle newlines into the log and forge entries.
        return trimmed.replaceAll("[^A-Za-z0-9_.:-]", "");
    }
}
