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
    private final AccountPolicy policy;
    public IdentityService(UserRepository users, PasswordEncoder encoder, AccountPolicy policy) {
        this.users = users; this.encoder = encoder;this.policy=policy;
    }
    @Transactional
    public UserSummary register(RegistrationRequest request) {
        try {
            var user=users.saveAndFlush(AppUser.member(request.email(),request.displayName(),encoder.encode(request.password())));
            return policy.summary(policy.find(user.getId(),false));
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new com.lawrencenno.commonbeacon.shared.ApiFailure(409, "ACCOUNT_CONFLICT", "An account with that email already exists.");
        }
    }
    @Transactional(readOnly = true)
    public UserSummary current(Authentication authentication) {
        return policy.summary(policy.current(authentication,false));
    }
    /** Ownership does not grant an implicit moderator/administrator bypass. */
    @Transactional(readOnly = true)
    public void requireOwner(Authentication authentication, UUID ownerId) {
        policy.requireFull(policy.current(authentication,false),false,false);
        if (!current(authentication).id().equals(ownerId)) {
            throw new AccessDeniedException("You do not own this resource");
        }
    }
}
