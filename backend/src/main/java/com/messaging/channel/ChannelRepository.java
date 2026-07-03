package com.messaging.channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    List<Channel> findByTenantId(UUID tenantId);

    Optional<Channel> findByExternalAccountId(String externalAccountId);

    Optional<Channel> findByTenantIdAndTypeAndStatus(UUID tenantId, String type, String status);
}
