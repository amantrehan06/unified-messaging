package com.messaging.ingestion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByContactIdAndChannelId(UUID contactId, UUID channelId);
}
