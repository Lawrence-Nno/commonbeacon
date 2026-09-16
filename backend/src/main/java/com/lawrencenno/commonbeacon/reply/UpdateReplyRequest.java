package com.lawrencenno.commonbeacon.reply;

import jakarta.validation.constraints.*;

public record UpdateReplyRequest(@NotBlank @Size(min = 1, max = 20000) String body,
                                 @NotNull @PositiveOrZero Long expectedVersion) {
    public UpdateReplyRequest { body = body == null ? null : body.trim(); }
}
