package com.lawrencenno.commonbeacon.moderation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record ResolveReportRequest(@NotNull Decision decision,
        @NotBlank @Size(min = 5, max = 2000) String resolutionNote,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @PositiveOrZero Long expectedTargetVersion,
        @PositiveOrZero Long expectedQuestionVersion) {
    public enum Decision { DISMISS, HIDE, ACKNOWLEDGE_HIDDEN }
    public ResolveReportRequest { resolutionNote = resolutionNote == null ? null : resolutionNote.trim(); }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown resolution field"); }
}
