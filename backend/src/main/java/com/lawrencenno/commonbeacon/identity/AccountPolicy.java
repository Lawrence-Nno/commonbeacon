package com.lawrencenno.commonbeacon.identity;

import java.util.UUID;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Shared policy for HTTP sessions, service transactions, and persisted job requesters. */
@Service
public class AccountPolicy {
    public static final long IDENTITY_KEY=736284910252L;
    public record Account(UUID id,String displayName,UserRole role,AccountState state,boolean verified,long epoch,long revision) {}
    public record Capabilities(boolean contribute,boolean moderate,boolean administer,boolean personalData,boolean eraseAccount) {}
    private final JdbcTemplate jdbc;
    private final VerificationRollout rollout;
    public AccountPolicy(JdbcTemplate jdbc,VerificationRollout rollout){this.jdbc=jdbc;this.rollout=rollout;}
    public static void shared(JdbcTemplate jdbc){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock_shared(?)",Boolean.class,IDENTITY_KEY)))
            throw new ApiFailure(409,"IDENTITY_CHANGE_IN_PROGRESS","Your account is changing. Try again.");
    }
    public Account find(UUID id,boolean lock){
        return jdbc.query("SELECT id,display_name,role,account_state,email_verified_at IS NOT NULL AS verified,auth_epoch,auth_revision FROM app_user WHERE id=?"+(lock?" FOR SHARE":""),
            (r,n)->new Account(r.getObject(1,UUID.class),r.getString(2),UserRole.valueOf(r.getString(3)),AccountState.valueOf(r.getString(4)),r.getBoolean(5),r.getLong(6),r.getLong(7)),id)
            .stream().findFirst().orElse(null);
    }
    public boolean credentialed(Account a){return a!=null && (a.state()==AccountState.ACTIVE || a.state()==AccountState.PENDING_VERIFICATION);}
    public boolean full(Account a){return credentialed(a) && a.state()==AccountState.ACTIVE && (a.verified() || rollout.mode()==VerificationRollout.Mode.TRANSITION);}
    public Account current(Authentication authentication,boolean lock){
        if(authentication==null)throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Authentication required");
        if(!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AccountPrincipal principal))
            throw new AccessDeniedException("Stable account authentication required");
        var a=find(principal.id(),lock);
        if(!credentialed(a) || a.epoch()!=principal.epoch())throw new ApiFailure(401,"UNAUTHENTICATED","Please sign in again.");
        return a;
    }
    public void requireFull(Account a,boolean administrator,boolean moderator){
        if(!full(a))throw new ApiFailure(403,"EMAIL_VERIFICATION_REQUIRED","Verify your email before using this action.");
        if(administrator && a.role()!=UserRole.ADMINISTRATOR || moderator && a.role()==UserRole.MEMBER)
            throw new AccessDeniedException("You do not have permission for this action");
    }
    public Capabilities capabilities(Account a){
        boolean full=full(a),privacy=credentialed(a);
        return new Capabilities(full,full && a.role()!=UserRole.MEMBER,full && a.role()==UserRole.ADMINISTRATOR,privacy,privacy);
    }
    public UserSummary summary(Account a){return new UserSummary(a.id(),a.displayName(),a.role(),a.state(),a.verified(),capabilities(a));}
    public boolean exportAllowed(Account a,UUID job,boolean personal){
        boolean allowed=personal?credentialed(a):full(a) && a.role()==UserRole.ADMINISTRATOR;
        if(!allowed)return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM transfer_job WHERE id=? AND requester_id=? AND authorization_revision=? AND kind=?)",Boolean.class,job,a.id(),a.revision(),personal?"PERSONAL_EXPORT":"COMPANY_EXPORT"));
    }
}
