package com.lawrencenno.commonbeacon.shared;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

class RequestLoggingTest {
    @RestController static class Endpoints {
        @GetMapping("/items/{id}") String get(@PathVariable String id) {
            if (id.equals("fail")) throw new IllegalStateException("secret-password", new IllegalArgumentException("private-sql"));
            return "ok";
        }
    }

    @Test void correlatesSuccessAndErrorsWithoutLeakingInputAndPreservesFrameworkErrors() throws Exception {
        var mapper = new ObjectMapper();
        var mvc = MockMvcBuilders.standaloneSetup(new Endpoints()).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestLoggingFilter(new ApiProblems(mapper))).build();
        var logger = (Logger) LoggerFactory.getLogger("com.lawrencenno.commonbeacon.shared");
        var events = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        events.start(); logger.addAppender(events);
        MDC.put("requestId", "outer-context");
        try {
            var ok = mvc.perform(get("/items/private-id?token=private-token").header("X-Request-Id", "untrusted-id"))
                    .andExpect(status().isOk()).andReturn();
            var failed = mvc.perform(get("/items/fail")).andExpect(status().isInternalServerError()).andReturn();
            var id = failed.getResponse().getHeader("X-Request-Id");
            assertThat(mapper.readTree(failed.getResponse().getContentAsString()).path("requestId").asText()).isEqualTo(id);
            assertThat(ok.getResponse().getHeader("X-Request-Id")).isNotEqualTo(id).isNotEqualTo("untrusted-id");
            assertThat(events.list).filteredOn(e -> id.equals(e.getMDCPropertyMap().get("requestId"))).hasSize(2);
            String text = events.list.stream().map(e -> e.getFormattedMessage() + e.getKeyValuePairs()).reduce("", String::concat);
            assertThat(text).contains("/items/{id}", "IllegalStateException", "IllegalArgumentException", "Endpoints.get")
                    .doesNotContain("secret-password", "private-sql", "private-id", "private-token", "untrusted-id");
            assertThat(events.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
            mvc.perform(post("/items/1")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET"));
            mvc.perform(get("/missing")).andExpect(status().isNotFound());
            assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        } finally { MDC.remove("requestId"); logger.detachAppender(events); events.stop(); }
    }

    @Test void handlesFailuresBeforeMvcAndClearsThreadContext() throws Exception {
        var response = new MockHttpServletResponse();
        new RequestLoggingFilter(new ApiProblems(new ObjectMapper())).doFilter(new MockHttpServletRequest(), response,
                (request, reply) -> { throw new jakarta.servlet.ServletException("private-filter-input"); });
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id")).doesNotContain("private-filter-input");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test void retainsCorrelationWhenAHandlerResetsTheResponse() throws Exception {
        var response = new MockHttpServletResponse();
        new RequestLoggingFilter(new ApiProblems(new ObjectMapper())).doFilter(new MockHttpServletRequest(), response,
                (request, reply) -> { reply.reset(); new ApiProblems(new ObjectMapper()).write(
                        (jakarta.servlet.http.HttpServletResponse) reply, 503, "TRANSFER_UNAVAILABLE", "Unavailable"); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test void neverAppendsAnErrorBodyToACommittedDownload() throws Exception {
        var response = new MockHttpServletResponse();
        new RequestLoggingFilter(new ApiProblems(new ObjectMapper())).doFilter(new MockHttpServletRequest(), response,
                (request, reply) -> {
                    reply.getOutputStream().write(new byte[]{1, 2, 3}); reply.flushBuffer();
                    throw new java.io.IOException("private-path");
                });
        assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(MDC.get("requestId")).isNull();
    }
}
