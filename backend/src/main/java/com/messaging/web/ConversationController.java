package com.messaging.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.messaging.ingestion.Contact;
import com.messaging.ingestion.ContactRepository;
import com.messaging.ingestion.Conversation;
import com.messaging.ingestion.ConversationRepository;
import com.messaging.ingestion.Message;
import com.messaging.ingestion.MessageRepository;
import com.messaging.outbound.OutboundService;

@RestController
public class ConversationController {

    private final ConversationRepository conversationRepo;
    private final ContactRepository contactRepo;
    private final MessageRepository messageRepo;
    private final OutboundService outboundService;

    public ConversationController(ConversationRepository conversationRepo,
                                  ContactRepository contactRepo,
                                  MessageRepository messageRepo,
                                  OutboundService outboundService) {
        this.conversationRepo = conversationRepo;
        this.contactRepo = contactRepo;
        this.messageRepo = messageRepo;
        this.outboundService = outboundService;
    }

    @GetMapping("/api/conversations")
    @Transactional(readOnly = true)
    public PaginatedResponse<ConversationDto> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "sinceCursor", required = false) String sinceCursor) {
        // RLS handles tenant scoping via TenantAwareTransactionManager
        List<Conversation> conversations = conversationRepo.findAllByOrderByLastMessageAtDesc();

        List<ConversationDto> dtos = conversations.stream()
                .map(c -> toDto(c))
                .toList();

        // Cursor = last conversation's lastMessageAt + id (for future incremental fetch)
        String cursor = null;
        if (!conversations.isEmpty()) {
            Conversation last = conversations.getLast();
            cursor = last.getLastMessageAt() + "|" + last.getId();
        }

        return new PaginatedResponse<>(dtos, cursor);
    }

    @GetMapping("/api/conversations/{id}/messages")
    @Transactional(readOnly = true)
    public PaginatedResponse<MessageDto> messages(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestParam(name = "sinceCursor", required = false) String sinceCursor) {
        List<Message> messages = messageRepo.findByConversationIdOrderByProviderTimestamp(id);

        List<MessageDto> dtos = messages.stream()
                .map(m -> new MessageDto(
                        m.getId().toString(),
                        m.getDirection(),
                        m.getAuthor(),
                        m.getBody(),
                        m.getProviderTimestamp().toString()
                ))
                .toList();

        String cursor = null;
        if (!messages.isEmpty()) {
            Message last = messages.getLast();
            cursor = last.getProviderTimestamp() + "|" + last.getId();
        }

        return new PaginatedResponse<>(dtos, cursor);
    }

    @PostMapping("/api/conversations/{id}/reply")
    public MessageDto reply(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody ReplyRequest request) {
        String idempotencyKey = request.idempotencyKey() != null
                ? request.idempotencyKey()
                : UUID.randomUUID().toString();

        Message message = outboundService.sendReply(user.tenantId(), id, request.body(), idempotencyKey);

        return new MessageDto(
                message.getId().toString(),
                message.getDirection(),
                message.getAuthor(),
                message.getBody(),
                message.getProviderTimestamp().toString()
        );
    }

    private ConversationDto toDto(Conversation c) {
        Contact contact = contactRepo.findById(c.getContactId()).orElse(null);
        String displayName = contact != null ? contact.getDisplayName() : "Unknown";
        String channelType = contact != null ? contact.getChannelType() : "unknown";
        String contactId = contact != null ? contact.getId().toString() : "";

        // Get last message as preview
        List<Message> msgs = messageRepo.findByConversationIdOrderByProviderTimestamp(c.getId());
        String lastPreview = "";
        if (!msgs.isEmpty()) {
            Message lastMsg = msgs.getLast();
            lastPreview = lastMsg.getBody() != null ? lastMsg.getBody() : "";
            if (lastPreview.length() > 100) {
                lastPreview = lastPreview.substring(0, 100) + "...";
            }
        }

        return new ConversationDto(
                c.getId().toString(),
                new ContactDto(contactId, displayName != null ? displayName : "Unknown", channelType),
                channelType,
                c.getState(),
                c.getStateReason(),
                List.of(), // labels not implemented in M3
                c.getLastMessageAt() != null ? c.getLastMessageAt().toString() : c.getCreatedAt().toString(),
                lastPreview,
                false // unread not tracked yet in M3
        );
    }

    record ReplyRequest(String body, String idempotencyKey) {}

    record ContactDto(String id, String displayName, String channelType) {}

    record ConversationDto(
            String id,
            ContactDto contact,
            String channelType,
            String state,
            String stateReason,
            List<Object> labels,
            String lastMessageAt,
            String lastMessagePreview,
            boolean unread
    ) {}

    record MessageDto(
            String id,
            String direction,
            String author,
            String body,
            String createdAt
    ) {}

    record PaginatedResponse<T>(List<T> items, String cursor) {}
}
