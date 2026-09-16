package com.lawrencenno.commonbeacon.board;

import jakarta.validation.constraints.*;

public record CreateBoardRequest(
        @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "Use lowercase letters, numbers, and single hyphens.") String slug,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 2000) String description) {
    public CreateBoardRequest {
        slug = slug == null ? null : slug.trim();
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
    }
}
