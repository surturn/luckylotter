package com.lucklotter.repo;

import com.lucklotter.domain.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    Optional<AdminUser> findByEmailIgnoreCaseAndActiveTrue(String email);
}
