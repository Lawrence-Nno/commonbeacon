package com.lawrencenno.commonbeacon.identity;
import java.util.UUID;
public record UserSummary(UUID id, String displayName, UserRole role, AccountState accountState, boolean emailVerified,
                          AccountPolicy.Capabilities capabilities) {
}
