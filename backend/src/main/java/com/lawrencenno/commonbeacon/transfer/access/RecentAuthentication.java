package com.lawrencenno.commonbeacon.transfer.access;

import com.lawrencenno.commonbeacon.identity.LoginRateLimiter;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class RecentAuthentication {
    public enum Scope { COMPANY_EXPORT, PERSONAL_EXPORT, IMPORT_UPLOAD, IMPORT_COMMIT, DOWNLOAD }
    public record Issued(String token,Instant expiresAt) { @Override public String toString(){return "Issued[redacted]";} }
    private record Grant(UUID actor,long revision,String session,Scope scope,UUID job,Instant expires) implements Serializable {}
    private static final class Vault implements Serializable { final Map<String,Grant> grants=new HashMap<>(); final Map<String,Grant> tickets=new HashMap<>(); }
    private static final String ATTRIBUTE=RecentAuthentication.class.getName()+".vault";
    private final TransferAccess access;
    private final Clock clock;
    private final LoginRateLimiter actors,addresses;
    private final SecureRandom random=new SecureRandom();
    public RecentAuthentication(TransferAccess access,@Qualifier("transferClock") Clock clock) {
        this.access=access;this.clock=clock;
        actors=new LoginRateLimiter(clock,5,10000,Duration.ofMinutes(15));
        addresses=new LoginRateLimiter(clock,20,10000,Duration.ofMinutes(15));
    }
    private Vault vault(HttpSession session) {
        try {
            synchronized(session) {
                session.getCreationTime();
                var existing=(Vault)session.getAttribute(ATTRIBUTE);
                if(existing==null){existing=new Vault();session.setAttribute(ATTRIBUTE,existing);}return existing;
            }
        }catch(IllegalStateException e){throw required();}
    }
    private static ApiFailure required(){return new ApiFailure(403,"RECENT_AUTH_REQUIRED","Confirm your password again to continue.");}
    private String digest(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw required();
        return HexFormat.of().formatHex(ArchiveCodec.sha256().digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private void prune(Vault vault,TransferAccess.Actor actor,String session) {
        for(var map:List.of(vault.grants,vault.tickets))map.values().removeIf(g->!g.expires().isAfter(clock.instant())
            || !g.actor().equals(actor.id()) || g.revision()!=actor.revision() || !g.session().equals(session));
    }
    public Issued issue(Authentication authentication,HttpSession session,String address,String password,Scope scope) {
        boolean administrator=scope!=Scope.PERSONAL_EXPORT && scope!=Scope.DOWNLOAD;
        var actor=access.current(authentication,administrator);
        boolean actorAllowed=actors.allow(actor.id().toString());boolean addressAllowed=addresses.allow(address);
        if(!actorAllowed || !addressAllowed)throw new ApiFailure(429,"RATE_LIMITED","Too many password confirmations. Try again later.");
        actor=access.confirmPassword(authentication,password,administrator);
        var vault=vault(session);
        synchronized(vault) {
            prune(vault,actor,session.getId());if(vault.grants.size()>=8)throw required();
            String token=token();Instant expires=clock.instant().plusSeconds(300);
            vault.grants.put(digest(token),new Grant(actor.id(),actor.revision(),session.getId(),scope,null,expires));return new Issued(token,expires);
        }
    }
    public void consume(TransferAccess.Actor actor,HttpSession session,Scope scope,String token) {
        var vault=vault(session);synchronized(vault) {
            prune(vault,actor,session.getId());var grant=vault.grants.get(digest(token));
            if(grant==null || grant.scope()!=scope)throw required();vault.grants.remove(digest(token));
        }
    }
    public Issued ticket(TransferAccess.Actor actor,HttpSession session,UUID job,String grant) {
        var vault=vault(session);synchronized(vault) {
            prune(vault,actor,session.getId());if(vault.tickets.size()>=8)throw required();
            consume(actor,session,Scope.DOWNLOAD,grant);
            String token=token();Instant expires=clock.instant().plusSeconds(60);
            vault.tickets.put(digest(token),new Grant(actor.id(),actor.revision(),session.getId(),Scope.DOWNLOAD,job,expires));return new Issued(token,expires);
        }
    }
    public void consumeTicket(TransferAccess.Actor actor,HttpSession session,UUID job,String token) {
        var vault=vault(session);synchronized(vault) {
            prune(vault,actor,session.getId());var ticket=vault.tickets.get(digest(token));
            if(ticket==null || !job.equals(ticket.job()))throw required();vault.tickets.remove(digest(token));
        }
    }
    public boolean sessionAlive(HttpSession session,String expectedId) {
        try {session.getCreationTime();return expectedId.equals(session.getId());}catch(IllegalStateException e){return false;}
    }
}
