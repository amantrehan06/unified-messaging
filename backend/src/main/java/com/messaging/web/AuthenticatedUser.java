package com.messaging.web;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID tenantId, String email, String role) {
}
