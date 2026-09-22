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
    public record Actor(UUID id,String role,long revision) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    public TransferAccess(JdbcTemplate jdbc,PasswordEncoder passwords) {this.jdbc=jdbc;this.passwords=passwords;}
    @Transactional(timeout=5)
    public Actor current(Authentication authentication,boolean administrator) {
        if(authentication==null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            throw new ApiFailure(401,"UNAUTHENTICATED","Please sign in to continue.");
        var actors=jdbc.query("SELECT id,role,auth_revision FROM app_user WHERE email=? AND account_state='ACTIVE' FOR SHARE",
            (r,n)->new Actor(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)),authentication.getName());
        if(actors.isEmpty())throw new ApiFailure(401,"UNAUTHENTICATED","Please sign in to continue.");
        var actor=actors.getFirst();
        if(administrator && !actor.role().equals("ADMINISTRATOR"))throw new ApiFailure(403,"FORBIDDEN","You do not have permission for this action.");
        return actor;
    }
    @Transactional(timeout=5)
    public Actor confirmPassword(Authentication authentication,String password,boolean administrator) {
        var actor=current(authentication,administrator);
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=?",String.class,actor.id());
        if(!passwords.matches(password,hash))throw new ApiFailure(403,"INVALID_CREDENTIALS","Password confirmation failed.");
        return actor;
    }
}
