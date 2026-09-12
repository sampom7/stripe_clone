package com.stripeclone.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripeclone.PostgresTestBase;
import com.stripeclone.outbox.EventType;
import com.stripeclone.outbox.OutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebhookApiTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    OutboxService outbox;

    private String body(Map<String, ?> fields) throws Exception {
        return json.writeValueAsString(fields);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult register(List<String> events) throws Exception {
        return mvc.perform(post("/v1/webhook_endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "url", "https://example.test/hook",
                                "enabled_events", events,
                                "description", "test"))))
                .andReturn();
    }

    @Test
    void registering_returns_the_secret_exactly_once() throws Exception {
        MvcResult created = register(List.of("charge.succeeded"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode createdBody = read(created);
        assertThat(createdBody.get("id").asText()).startsWith("we_");
        assertThat(createdBody.get("secret").asText()).startsWith("whsec_");

        // Fetching it again does not hand the secret back. Lose it and you roll the
        // endpoint, same as Stripe.
        mvc.perform(get("/v1/webhook_endpoints/" + createdBody.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.object").value("webhook_endpoint"))
                .andExpect(jsonPath("$.status").value("enabled"));
    }

    @Test
    void a_url_that_is_not_http_is_rejected() throws Exception {
        mvc.perform(post("/v1/webhook_endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "url", "ftp://example.test/hook",
                                "enabled_events", List.of("charge.succeeded")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void an_empty_event_list_is_rejected() throws Exception {
        mvc.perform(post("/v1/webhook_endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "url", "https://example.test/hook",
                                "enabled_events", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void an_endpoint_can_be_disabled_and_enabled_again() throws Exception {
        String id = read(register(List.of("*"))).get("id").asText();

        mvc.perform(post("/v1/webhook_endpoints/" + id + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));

        mvc.perform(post("/v1/webhook_endpoints/" + id + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("enabled"));
    }

    @Test
    void an_endpoint_can_be_deleted() throws Exception {
        String id = read(register(List.of("*"))).get("id").asText();

        mvc.perform(delete("/v1/webhook_endpoints/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mvc.perform(get("/v1/webhook_endpoints/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void an_unknown_endpoint_is_404() throws Exception {
        mvc.perform(get("/v1/webhook_endpoints/we_nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("resource_missing"));
    }

    @Test
    @DisplayName("the event log is readable, which is how a receiver catches up")
    void events_can_be_listed_and_retrieved() throws Exception {
        String eventId = outbox.publish(EventType.CHARGE_SUCCEEDED, "ch_api",
                Map.of("id", "ch_api", "amount", 2000));

        mvc.perform(get("/v1/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.object").value("event"))
                .andExpect(jsonPath("$.type").value("charge.succeeded"))
                .andExpect(jsonPath("$.data.object.id").value("ch_api"));

        mvc.perform(get("/v1/events").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value(eventId));
    }

    @Test
    void an_unknown_event_is_404() throws Exception {
        mvc.perform(get("/v1/events/evt_nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("resource_missing"));
    }
}
