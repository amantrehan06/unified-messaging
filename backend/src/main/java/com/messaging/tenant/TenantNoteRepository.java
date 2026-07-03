package com.messaging.tenant;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantNoteRepository extends JpaRepository<TenantNote, UUID> {
}
