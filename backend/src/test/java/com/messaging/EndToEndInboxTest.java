package com.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.channel.Channel;
import com.messaging.channel.ChannelRepository;
import com.messaging.channel.UnipileClient;
import com.messaging.ingestion.ContactRepository;
import com.messaging.ingestion.ConversationRepository;
import com.messaging.ingestion.InboundEventRepository;
import com.messaging.ingestion.MessageRepository;
import com.messaging.outbound.OutboundMessageRepository;
import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantContext;
import com.messaging.tenant.TenantRepository;
import com.messaging.web.JwtService;

/**
 * Full end-to-end integration test: webhook in -> pipeline -> read API -> reply -> idempotent resend.
 *
 * Everything runs for real (Spring context, Testcontainers Postgres, real worker, real read API,
 * real send path). Only Unipile's external HTTP API is mocked.
 *
 * Proves the FULLY ASSEMBLED pipeline works: ledger -> worker -> store -> read -> reply -> idempotent send.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndToEndInboxTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private TenantRepository tenantRepo;
    @Autowired private ChannelRepository channelRepo;
    @Autowired private InboundEventRepository eventRepo;
    @Autowired private ContactRepository contactRepo;
    @Autowired private ConversationRepository conversationRepo;
    @Autowired private MessageRepository messageRepo;
    @Autowired private OutboundMessageRepository outboundRepo;

    @MockitoBean private UnipileClient unipileClient;

    @Value("${unipile.webhook-secret}")
    private String webhookSecret;

    private final ObjectMapper mapper = new ObjectMapper();

    private UUID tenantId;
    private UUID userId;
    private String jwt;
    private String externalAccountId;

    @BeforeEach
    void setUp() {
        // Clean in FK order
        outboundRepo.deleteAll();
        messageRepo.deleteAll();
        conversationRepo.deleteAll();
        contactRepo.deleteAll();
        eventRepo.deleteAll();

        // Seed: tenant + user + connected WhatsApp channel
        Tenant tenant = tenantRepo.save(new Tenant("Acme Corp"));
        tenantId = tenant.getId();
        userId = UUID.randomUUID();
        jwt = jwtService.mint(userId, tenantId, "owner@acme.com", "owner");

        externalAccountId = "unipile-acc-" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.set(tenantId);
        try {
            Channel channel = new Channel(tenantId, "whatsapp");
            channel.setExternalAccountId(externalAccountId);
            channel.setStatus("connected");
            channelRepo.save(channel);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The full MVP loop in one test:
     * 1. Inject inbound message webhook
     * 2. Verify pipeline ran (event processed, contact + conversation + message created)
     * 3. Read API: conversation appears in list, message appears in thread
     * 4. Reply: outbound message stored, Unipile called with correct args
     * 5. Resend with same idempotency key: exactly one Unipile send
     */
    @Test
    void fullLoop_inbound_read_reply_idempotent() throws Exception {
        String providerMessageId = "msg-" + UUID.randomUUID().toString().substring(0, 8);
        String senderPhone = "+14155551234";
        String senderName = "Alice Customer";
        String inboundBody = "Hi, I'd like a quote for 500 units";
        Instant inboundTimestamp = Instant.now();

        // ---- Step 1: inject inbound message via webhook ----

        String webhookPayload = mapper.writeValueAsString(mapper.createObjectNode()
                .put("id", providerMessageId)
                .put("account_id", externalAccountId)
                .put("sender_id", senderPhone)
                .put("sender_name", senderName)
                .put("channel_type", "whatsapp")
                .put("provider_name", "whatsapp")
                .put("text", inboundBody)
                .put("timestamp", inboundTimestamp.toString()));

        mockMvc.perform(post("/api/unipile/message")
                        .param("secret", webhookSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk());

        // Give the async event listener a moment to process
        Thread.sleep(500);

        // ---- Step 2: verify full pipeline ran ----

        // Event: received -> processed
        TenantContext.set(tenantId);
        try {
            var events = eventRepo.findAll();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getProcessingStatus()).isEqualTo("processed");
            assertThat(events.get(0).getTenantId()).isEqualTo(tenantId);

            // Contact created with display name
            var contacts = contactRepo.findAll();
            assertThat(contacts).hasSize(1);
            assertThat(contacts.get(0).getExternalIdentity()).isEqualTo(senderPhone);
            assertThat(contacts.get(0).getDisplayName()).isEqualTo(senderName);
            assertThat(contacts.get(0).getChannelType()).isEqualTo("whatsapp");

            // Conversation created
            var conversations = conversationRepo.findAll();
            assertThat(conversations).hasSize(1);
            assertThat(conversations.get(0).getState()).isEqualTo("AI_HANDLING");

            // Message stored with provider_timestamp
            var messages = messageRepo.findAll();
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getDirection()).isEqualTo("inbound");
            assertThat(messages.get(0).getAuthor()).isEqualTo("contact");
            assertThat(messages.get(0).getBody()).isEqualTo(inboundBody);
            assertThat(messages.get(0).getProviderMessageId()).isEqualTo(providerMessageId);
        } finally {
            TenantContext.clear();
        }

        // ---- Step 3: read API - conversation list ----

        MvcResult listResult = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].contact.displayName").value(senderName))
                .andExpect(jsonPath("$.items[0].channelType").value("whatsapp"))
                .andExpect(jsonPath("$.items[0].state").value("AI_HANDLING"))
                .andExpect(jsonPath("$.items[0].lastMessagePreview").value(inboundBody))
                .andReturn();

        // Extract conversation ID for thread + reply
        JsonNode listJson = mapper.readTree(listResult.getResponse().getContentAsString());
        String conversationId = listJson.get("items").get(0).get("id").asText();

        // ---- Step 4: read API - thread messages ----

        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].direction").value("inbound"))
                .andExpect(jsonPath("$.items[0].author").value("contact"))
                .andExpect(jsonPath("$.items[0].body").value(inboundBody));

        // ---- Step 5: reply ----

        String replyBody = "Thanks Alice! Let me get that quote ready for you.";
        String idempotencyKey = "e2e-idem-" + UUID.randomUUID();
        String outboundProviderMsgId = "sent-" + UUID.randomUUID().toString().substring(0, 8);

        when(unipileClient.sendMessage(eq(externalAccountId), eq(senderPhone), eq(replyBody)))
                .thenReturn(outboundProviderMsgId);

        mockMvc.perform(post("/api/conversations/" + conversationId + "/reply")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapper.createObjectNode()
                                .put("body", replyBody)
                                .put("idempotencyKey", idempotencyKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("outbound"))
                .andExpect(jsonPath("$.author").value("human"))
                .andExpect(jsonPath("$.body").value(replyBody));

        // Verify Unipile was called with correct args
        verify(unipileClient, times(1)).sendMessage(externalAccountId, senderPhone, replyBody);

        // Thread now has both messages in order
        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].direction").value("inbound"))
                .andExpect(jsonPath("$.items[0].body").value(inboundBody))
                .andExpect(jsonPath("$.items[1].direction").value("outbound"))
                .andExpect(jsonPath("$.items[1].body").value(replyBody));

        // ---- Step 6: resend same idempotency key - no double send ----

        mockMvc.perform(post("/api/conversations/" + conversationId + "/reply")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapper.createObjectNode()
                                .put("body", replyBody)
                                .put("idempotencyKey", idempotencyKey))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("outbound"))
                .andExpect(jsonPath("$.body").value(replyBody));

        // Still exactly ONE Unipile send (the second call returned the existing message)
        verify(unipileClient, times(1)).sendMessage(externalAccountId, senderPhone, replyBody);

        // Thread still has exactly two messages (no duplicate)
        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        // ---- Step 7: auth enforcement ----

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().is3xxRedirection()); // OAuth redirect for unauthenticated

        // Cross-tenant RLS isolation is proven in OutboundPipelineTest and
        // IngestionPipelineTest using a non-superuser app_user role. The
        // Testcontainers superuser bypasses RLS, so we don't re-test it here.
    }
}
