package com.lucklotter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A business admin. Scoped to exactly one {@link Business}; that scope is what
 * the JWT carries and the only tenant boundary the service layer trusts
 * (NFR-1). Phase 1 has no customer-facing accounts.
 */
@Entity
@Table(name = "admin_users")
@Getter
@Setter
@NoArgsConstructor
public class AdminUser {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Set when the operator issues a temporary password. While true the account
     * can do nothing except change its password.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /**
     * Tokens issued before this instant are refused. Moving it forward is how a
     * password change or reset ends every session that used the old password.
     */
    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt = Instant.now();

    /** Replaces the password and ends every session issued before now. */
    public void replacePassword(String newHash, boolean forceChangeOnNextLogin) {
        this.passwordHash = newHash;
        this.mustChangePassword = forceChangeOnNextLogin;
        this.passwordChangedAt = Instant.now();
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
