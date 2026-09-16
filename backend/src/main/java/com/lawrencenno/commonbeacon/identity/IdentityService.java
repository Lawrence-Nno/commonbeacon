package com.lawrencenno.commonbeacon.identity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class IdentityService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    public IdentityService(UserRepository users, PasswordEncoder encoder) {
        this.users = users; this.encoder = encoder;
    }
    @Transactional
    public UserSummary register(RegistrationRequest request) {
        try {
            return UserSummary.from(users.saveAndFlush(AppUser.member(
                request.email(), request.displayName(), encoder.encode(request.password()))));
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new com.lawrencenno.commonbeacon.shared.ApiFailure(409, "ACCOUNT_CONFLICT", "An account with that email already exists.");
        }
    }
    @Transactional(readOnly = true)
    public UserSummary current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        return users.findByEmail(authentication.getName()).map(UserSummary::from)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
    }
    /** Ownership does not grant an implicit moderator/administrator bypass. */
    @Transactional(readOnly = true)
    public void requireOwner(Authentication authentication, UUID ownerId) {
        if (!current(authentication).id().equals(ownerId)) {
            throw new AccessDeniedException("You do not own this resource");
        }
    }
}
