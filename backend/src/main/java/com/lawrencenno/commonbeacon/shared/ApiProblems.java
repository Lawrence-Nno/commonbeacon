package com.lawrencenno.commonbeacon.shared;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiProblems {
    private final ObjectMapper mapper;
    public ApiProblems(ObjectMapper mapper) { this.mapper = mapper; }
    public static ProblemDetail problem(int status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setProperty("code", code);
        problem.setProperty("requestId", UUID.randomUUID().toString());
        return problem;
    }
    public void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        var problem = problem(status, code, detail);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", problem.getProperties().get("requestId").toString());
        mapper.writeValue(response.getWriter(), problem);
    }
}
