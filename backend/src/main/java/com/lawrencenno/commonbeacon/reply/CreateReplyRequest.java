package com.lawrencenno.commonbeacon.reply;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReplyRequest(@NotBlank @Size(min = 1, max = 20000) String body) {
    public CreateReplyRequest { body = body == null ? null : body.trim(); }
}
