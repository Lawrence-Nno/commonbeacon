package com.lawrencenno.commonbeacon.identity;
import jakarta.validation.constraints.*;
import java.util.Locale;
public record RegistrationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 1, max = 80) String displayName,
        @NotBlank @Size(min = 12, max = 128) String password) {
    public RegistrationRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        displayName = displayName == null ? null : displayName.trim();
    }
}
