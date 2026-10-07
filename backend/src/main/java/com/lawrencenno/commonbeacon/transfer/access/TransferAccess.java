package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferAccess {
    public record Actor(UUID id,String role,long revision,long epoch) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final com.lawrencenno.commonbeacon.identity.AccountPolicy policy;
    public TransferAccess(JdbcTemplate jdbc,PasswordEncoder passwords,com.lawrencenno.commonbeacon.identity.AccountPolicy policy) {this.jdbc=jdbc;this.passwords=passwords;this.policy=policy;}
    @Transactional(timeout=5)
    public Actor current(Authentication authentication,boolean administrator) {
        if(authentication==null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            throw new ApiFailure(401,"UNAUTHENTICATED","Please sign in to continue.");
        com.lawrencenno.commonbeacon.identity.AccountPolicy.shared(jdbc);
        var account=policy.current(authentication,true);
        if(administrator)policy.requireFull(account,true,false);
        return new Actor(account.id(),account.role().name(),account.revision(),account.epoch());
    }
    @Transactional(timeout=5)
    public Actor confirmPassword(Authentication authentication,String password,boolean administrator) {
        var actor=current(authentication,administrator);
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,actor.id());
        if(!passwords.matches(password,hash))throw new ApiFailure(403,"INVALID_CREDENTIALS","Password confirmation failed.");
        return actor;
    }
}
