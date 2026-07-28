package com.lucklotter.repo;

import com.lucklotter.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {
}
