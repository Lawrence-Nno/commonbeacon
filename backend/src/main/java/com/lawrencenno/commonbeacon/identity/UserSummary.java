package com.lawrencenno.commonbeacon.identity;
import java.util.UUID;
public record UserSummary(UUID id, String displayName, UserRole role) {
    static UserSummary from(AppUser user) {
        return new UserSummary(user.getId(), user.getDisplayName(), user.getRole());
    }
}
