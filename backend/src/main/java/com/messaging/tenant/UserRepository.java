package com.messaging.tenant;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByAuthProviderAndExternalAuthId(String authProvider, String externalAuthId);
}
