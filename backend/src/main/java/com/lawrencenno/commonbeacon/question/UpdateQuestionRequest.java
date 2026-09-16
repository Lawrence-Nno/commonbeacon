package com.lawrencenno.commonbeacon.question;

import jakarta.validation.constraints.*;

public record UpdateQuestionRequest(
        @NotBlank @Size(min = 5, max = 200) String title,
        @NotBlank @Size(min = 10, max = 20000) String body,
        @NotNull @PositiveOrZero Long expectedVersion) {
    public UpdateQuestionRequest {
        title = title == null ? null : title.trim();
        body = body == null ? null : body.trim();
    }
}
