package com.lawrencenno.commonbeacon.board;

import jakarta.validation.constraints.*;

public record UpdateBoardRequest(
        @Size(min = 1, max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "Use lowercase letters, numbers, and single hyphens.") String slug,
        @Size(min = 1, max = 120) @Pattern(regexp = "(?s).*\\S.*", message = "Must not be blank.") String name,
        @Size(min = 1, max = 2000) @Pattern(regexp = "(?s).*\\S.*", message = "Must not be blank.") String description,
        Boolean archived,
        @NotNull @PositiveOrZero Long expectedVersion) {
    public UpdateBoardRequest {
        slug = slug == null ? null : slug.trim();
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
    }
}
