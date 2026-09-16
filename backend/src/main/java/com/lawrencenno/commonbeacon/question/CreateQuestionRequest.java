package com.lawrencenno.commonbeacon.question;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateQuestionRequest(
        @NotBlank @Size(min = 5, max = 200) String title,
        @NotBlank @Size(min = 10, max = 20000) String body) {
    public CreateQuestionRequest {
        title = title == null ? null : title.trim();
        body = body == null ? null : body.trim();
    }
}
