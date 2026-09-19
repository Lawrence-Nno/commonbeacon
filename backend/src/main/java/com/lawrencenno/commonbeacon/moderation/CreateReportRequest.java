package com.lawrencenno.commonbeacon.moderation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateReportRequest(UUID questionId, UUID replyId,
        @NotBlank @Size(min = 5, max = 2000) String reason) {
    public CreateReportRequest { reason = reason == null ? null : reason.trim(); }

    // Reject forged metadata on this DTO without changing legacy request policies.
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown report field");
    }
}
