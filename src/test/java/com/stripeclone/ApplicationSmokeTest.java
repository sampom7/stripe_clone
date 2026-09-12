package com.stripeclone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import com.stripeclone.outbox.OutboxPoller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end, over HTTP, the way somebody integrating would actually use it.
 *
 * <p>The other suites each prove one layer. This one proves the layers are wired to each
 * other: a customer, a card, a payment, an event, a webhook queued for it, and a ledger
 * that still balances at the end.
 */
@AutoConfigureMockMvc
class ApplicationSmokeTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    LedgerService ledger;

    @Autowired
    OutboxPoller outboxPoller;

    private String body(Map<String, ?> fields) throws Exception {
        return json.writeValueAsString(fields);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a full payment, start to finish")
    void the_whole_flow_works_over_http() throws Exception {
        // A merchant to be paid, and some money in the world to pay with.
        ledger.createAccount("acct_external_usd", AccountType.EXTERNAL, USD);
        String merchantAccount = Ids.account();
        ledger.createAccount(merchantAccount, AccountType.MERCHANT, USD);

        // Somebody registers for webhooks first.
        MvcResult endpoint = mvc.perform(post("/v1/webhook_endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "url", "https://merchant.test/webhooks",
                                "enabled_events", List.of("*")))))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(read(endpoint).get("secret").asText()).startsWith("whsec_");

        // Create a customer and fund them.
        MvcResult customer = mvc.perform(post("/v1/customers")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "buyer@example.com", "currency", "usd"))))
                .andExpect(status().isCreated())
                .andReturn();
        String customerId = read(customer).get("id").asText();

        String customerAccount = jdbc
                .sql("SELECT account_id FROM customers WHERE customer_id = :id")
                .param("id", customerId)
                .query(String.class)
                .single();
        ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                "acct_external_usd", customerAccount, Amount.of(50_000, USD), "top up");

        // Add a card and attach it.
        MvcResult card = mvc.perform(post("/v1/payment_methods")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "type", "card",
                                "card", Map.of("number", "4242424242424242",
                                        "exp_month", 12, "exp_year", 2030)))))
                .andExpect(status().isCreated())
                .andReturn();
        String paymentMethodId = read(card).get("id").asText();

        mvc.perform(post("/v1/payment_methods/" + paymentMethodId + "/attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("customer", customerId))))
                .andExpect(status().isOk());

        // Take a payment, holding the funds rather than taking them straight away.
        MvcResult intent = mvc.perform(post("/v1/payment_intents")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "amount", 20_000,
                                "currency", "usd",
                                "customer", customerId,
                                "merchant_account", merchantAccount,
                                "capture_method", "manual"))))
                .andExpect(status().isCreated())
                .andReturn();
        String intentId = read(intent).get("id").asText();

        mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("payment_method", paymentMethodId,
                                "card_number", "4242424242424242"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("requires_capture"))
                .andExpect(jsonPath("$.amount_capturable").value(20_000));

        // Capture part of it; the rest goes back to the customer.
        mvc.perform(post("/v1/payment_intents/" + intentId + "/capture")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("amount_to_capture", 15_000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.amount_received").value(15_000));

        // Refund a bit of that.
        MvcResult charges = mvc.perform(get("/v1/payment_intents/" + intentId + "/charges"))
                .andReturn();
        String chargeId = read(charges).get("data").get(0).get("id").asText();

        mvc.perform(post("/v1/refunds")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("charge", chargeId, "amount", 5_000,
                                "reason", "requested_by_customer"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(5_000));

        // 50_000 start, 15_000 captured, 5_000 refunded.
        assertThat(ledger.balanceOf(customerAccount)).isEqualTo(Amount.of(40_000, USD));
        assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.of(10_000, USD));

        // Events were recorded, and draining the outbox queues webhooks for them.
        mvc.perform(get("/v1/events").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()")
                        .value(org.hamcrest.Matchers.greaterThan(3)));

        outboxPoller.drainOnceInNewTransaction();

        Long queuedDeliveries = jdbc.sql("SELECT count(*) FROM webhook_deliveries")
                .query(Long.class)
                .single();
        assertThat(queuedDeliveries).isPositive();

        // And after all that, the books still balance.
        assertThat(ledger.isBalanced()).isTrue();
        assertThat(ledger.findDriftedAccounts()).isEmpty();
    }

    @Test
    void the_ledger_health_check_reports_a_balanced_ledger() throws Exception {
        mvc.perform(get("/actuator/health/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details.balanced").value(true));
    }

    @Test
    void the_openapi_document_lists_the_endpoints() throws Exception {
        mvc.perform(get("/v1/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/payment_intents']").exists())
                .andExpect(jsonPath("$.paths['/v1/refunds']").exists())
                .andExpect(jsonPath("$.paths['/v1/webhook_endpoints']").exists());
    }
}
