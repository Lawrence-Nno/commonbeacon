package com.lawrencenno.commonbeacon.moderation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record RestoreContentRequest(@NotBlank @Size(min = 5, max = 2000) String reason,
        @NotNull @PositiveOrZero Long expectedTargetVersion, @PositiveOrZero Long expectedQuestionVersion) {
    public RestoreContentRequest { reason = reason == null ? null : reason.trim(); }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown restoration field"); }
}
