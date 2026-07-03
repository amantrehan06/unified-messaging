package com.messaging.channel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.messaging.channel.UnipileClient.HostedAuthRequest;

@WireMockTest
class HttpUnipileClientTest {

    private static final String API_KEY = "test-api-key";

    @Test
    void createHostedAuthLink_sendsApiKeyAndFields(WireMockRuntimeInfo wmInfo) {
        stubFor(post("/api/v1/hosted/accounts/link")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object": "HostedAuthURL", "url": "https://account.unipile.com/abc123"}
                                """)));

        HttpUnipileClient client = new HttpUnipileClient(wmInfo.getHttpBaseUrl(), API_KEY);

        HostedAuthRequest request = HostedAuthRequest.create(
                "create",
                List.of("WHATSAPP"),
                "2026-07-03T12:00:00Z",
                "tenant-uuid-here",
                "http://localhost:5173/channels?status=success",
                "http://localhost:5173/channels?status=failure",
                "http://localhost:8080/api/unipile/notify?secret=s3cret"
        );

        String url = client.createHostedAuthLink(request);

        assertThat(url).isEqualTo("https://account.unipile.com/abc123");

        verify(postRequestedFor(urlEqualTo("/api/v1/hosted/accounts/link"))
                .withHeader("X-API-KEY", equalTo(API_KEY)));
    }

    @Test
    void createHostedAuthLink_includesNameAsTenantId(WireMockRuntimeInfo wmInfo) {
        String tenantId = "550e8400-e29b-41d4-a716-446655440000";

        stubFor(post("/api/v1/hosted/accounts/link")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object": "HostedAuthURL", "url": "https://account.unipile.com/link"}
                                """)));

        HttpUnipileClient client = new HttpUnipileClient(wmInfo.getHttpBaseUrl(), API_KEY);

        HostedAuthRequest request = HostedAuthRequest.create(
                "create", List.of("WHATSAPP"), "2026-07-03T12:00:00Z", tenantId,
                null, null, "http://localhost:8080/api/unipile/notify?secret=s"
        );

        client.createHostedAuthLink(request);

        verify(postRequestedFor(urlEqualTo("/api/v1/hosted/accounts/link"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing("\"name\":\"" + tenantId + "\"")));
    }

    @Test
    void createHostedAuthLink_reconnectIncludesAccountId(WireMockRuntimeInfo wmInfo) {
        stubFor(post("/api/v1/hosted/accounts/link")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"object": "HostedAuthURL", "url": "https://account.unipile.com/recon"}
                                """)));

        HttpUnipileClient client = new HttpUnipileClient(wmInfo.getHttpBaseUrl(), API_KEY);

        HostedAuthRequest request = HostedAuthRequest.reconnect(
                "2026-07-03T12:00:00Z", "tenant-id",
                null, null,
                "http://localhost:8080/api/unipile/notify?secret=s",
                "existing-account-id"
        );

        String url = client.createHostedAuthLink(request);
        assertThat(url).isEqualTo("https://account.unipile.com/recon");

        verify(postRequestedFor(urlEqualTo("/api/v1/hosted/accounts/link"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing("\"reconnect_account\":\"existing-account-id\""))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing("\"type\":\"reconnect\"")));
    }

    @Test
    void createHostedAuthLink_serverError_throws(WireMockRuntimeInfo wmInfo) {
        stubFor(post("/api/v1/hosted/accounts/link")
                .willReturn(aResponse().withStatus(500)));

        HttpUnipileClient client = new HttpUnipileClient(wmInfo.getHttpBaseUrl(), API_KEY);

        HostedAuthRequest request = HostedAuthRequest.create(
                "create", List.of("WHATSAPP"), "2026-07-03T12:00:00Z", "tenant",
                null, null, "http://localhost:8080/api/unipile/notify?secret=s"
        );

        assertThatThrownBy(() -> client.createHostedAuthLink(request))
                .isInstanceOf(Exception.class);
    }

    @Test
    void createHostedAuthLink_malformedResponse_throws(WireMockRuntimeInfo wmInfo) {
        stubFor(post("/api/v1/hosted/accounts/link")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\": \"HostedAuthURL\"}")));

        HttpUnipileClient client = new HttpUnipileClient(wmInfo.getHttpBaseUrl(), API_KEY);

        HostedAuthRequest request = HostedAuthRequest.create(
                "create", List.of("WHATSAPP"), "2026-07-03T12:00:00Z", "tenant",
                null, null, "http://localhost:8080/api/unipile/notify?secret=s"
        );

        assertThatThrownBy(() -> client.createHostedAuthLink(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("hosted auth URL");
    }
}
