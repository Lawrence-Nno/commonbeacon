package com.lawrencenno.commonbeacon.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent account identity. Public registration uses the member factory;
 * API responses use UserSummary to keep credentials private.
 */
@Entity
@Table(name = "app_user")
public class AppUser {
    @Id
    private UUID id;

    @Column(length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_state", nullable = false, length = 24)
    private AccountState accountState = AccountState.ACTIVE;

    public boolean isActive() { return accountState == AccountState.ACTIVE; }

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
        // Required by JPA.
    }

    static AppUser member(String email, String displayName, String passwordHash) {
        var user = new AppUser();
        user.id = UUID.randomUUID();
        user.email = email;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.role = UserRole.MEMBER;
        user.createdAt = Instant.now();
        return user;
    }

    String passwordHash() { return passwordHash; }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
