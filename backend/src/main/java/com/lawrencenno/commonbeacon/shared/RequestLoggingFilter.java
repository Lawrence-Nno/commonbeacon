package com.lawrencenno.commonbeacon.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ApiProblems problems;
    public RequestLoggingFilter(ApiProblems problems) { this.problems = problems; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        boolean failed = false;
        try {
            chain.doFilter(request, new jakarta.servlet.http.HttpServletResponseWrapper(response) {
                @Override public void reset() {
                    super.reset();
                    setHeader("X-Request-Id", id);
                }
            });
        } catch (Exception failure) {
            failed = true;
            OperationalLogs.failure(LOG, "http.unexpected_failure", failure, null);
            if (!response.isCommitted()) {
                response.reset();
                response.setHeader("X-Request-Id", id);
                problems.write(response, 500, "INTERNAL_ERROR", "The service could not complete the request.");
            }
        } finally {
            try {
                var log = (failed || response.getStatus() >= 500) ? LOG.atError() : LOG.atInfo();
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                log.addKeyValue("event", "http.request_completed")
                        .addKeyValue("method", java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE")
                                .contains(request.getMethod()) ? request.getMethod() : "OTHER")
                        .addKeyValue("route", route == null ? "unmatched" : route.toString())
                        .addKeyValue("status", response.getStatus())
                        .addKeyValue("failed", failed || response.getStatus() >= 500)
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .log("HTTP request completed");
            } finally {
                if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
            }
        }
    }
}
