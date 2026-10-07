package com.lawrencenno.commonbeacon.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** A stable identity and login-time epoch. Email is only a credential lookup input. */
public final class AccountPrincipal extends User {
    private final UUID id;
    private final long epoch;
    public AccountPrincipal(UUID id,long epoch,String password,UserRole role,boolean fullAccess) {
        super(id.toString(),password,List.of(new SimpleGrantedAuthority(fullAccess?"ROLE_"+role.name():"ROLE_LIMITED")));
        this.id=id;this.epoch=epoch;
    }
    public UUID id(){return id;}
    public long epoch(){return epoch;}
}
