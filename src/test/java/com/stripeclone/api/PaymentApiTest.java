package com.stripeclone.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripeclone.PostgresTestBase;
import com.stripeclone.common.Ids;
import com.stripeclone.ledger.AccountType;
import com.stripeclone.ledger.LedgerService;
import com.stripeclone.ledger.TransactionKind;
import com.stripeclone.money.Amount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PaymentApiTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    LedgerService ledger;

    private static final String EXTERNAL = "acct_external_usd";
    private String customerId;
    private String merchantAccount;

    @BeforeEach
    void seed() throws Exception {
        ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);

        MvcResult created = mvc.perform(post("/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "buyer@example.com",
                                "name", "A Buyer", "currency", "usd"))))
                .andExpect(status().isCreated())
                .andReturn();

        customerId = read(created).get("id").asText();

        String customerAccount = jdbc
                .sql("SELECT account_id FROM customers WHERE customer_id = :id")
                .param("id", customerId)
                .query(String.class)
                .single();
        ledger.transfer(Ids.transaction(), TransactionKind.FUNDING,
                EXTERNAL, customerAccount, Amount.of(100_000, USD), "funding");

        merchantAccount = Ids.account();
        ledger.createAccount(merchantAccount, AccountType.MERCHANT, USD);
    }

    private String body(Map<String, ?> fields) throws Exception {
        return json.writeValueAsString(fields);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult createIntent(long amount, String captureMethod) throws Exception {
        return mvc.perform(post("/v1/payment_intents")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "amount", amount,
                                "currency", "usd",
                                "customer", customerId,
                                "merchant_account", merchantAccount,
                                "capture_method", captureMethod))))
                .andReturn();
    }

    @Nested
    @DisplayName("object shapes")
    class Shapes {

        @Test
        void a_payment_intent_looks_like_stripe() throws Exception {
            mvc.perform(post("/v1/payment_intents")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "amount", 2000,
                                    "currency", "usd",
                                    "customer", customerId,
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("pi_")))
                    .andExpect(jsonPath("$.object").value("payment_intent"))
                    .andExpect(jsonPath("$.amount").value(2000))
                    .andExpect(jsonPath("$.currency").value("usd"))
                    .andExpect(jsonPath("$.status").value("requires_confirmation"))
                    .andExpect(jsonPath("$.capture_method").value("automatic"))
                    .andExpect(jsonPath("$.amount_received").value(0));
        }

        @Test
        void a_list_is_wrapped_not_bare() throws Exception {
            createIntent(1000, "automatic");
            createIntent(2000, "automatic");

            mvc.perform(get("/v1/payment_intents"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.object").value("list"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.has_more").value(false))
                    .andExpect(jsonPath("$.url").value("/v1/payment_intents"));
        }

        @Test
        void has_more_is_true_when_a_page_is_full() throws Exception {
            for (int i = 0; i < 4; i++) {
                createIntent(1000 + i, "automatic");
            }

            mvc.perform(get("/v1/payment_intents").param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.has_more").value(true));
        }

        @Test
        void paging_with_starting_after_walks_backwards_without_repeats() throws Exception {
            for (int i = 0; i < 5; i++) {
                createIntent(1000 + i, "automatic");
            }

            MvcResult first = mvc.perform(get("/v1/payment_intents").param("limit", "2"))
                    .andReturn();
            JsonNode firstPage = read(first).get("data");
            String cursor = firstPage.get(1).get("id").asText();

            MvcResult second = mvc.perform(get("/v1/payment_intents")
                            .param("limit", "2")
                            .param("starting_after", cursor))
                    .andReturn();
            JsonNode secondPage = read(second).get("data");

            assertThat(secondPage.get(0).get("id").asText())
                    .isNotEqualTo(firstPage.get(0).get("id").asText())
                    .isNotEqualTo(firstPage.get(1).get("id").asText());
        }
    }

    @Nested
    @DisplayName("the payment flow over HTTP")
    class Flow {

        @Test
        void confirm_moves_the_money_and_reports_succeeded() throws Exception {
            String id = read(createIntent(2000, "automatic")).get("id").asText();

            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("succeeded"))
                    .andExpect(jsonPath("$.amount_received").value(2000));

            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.of(2000, USD));
        }

        @Test
        void manual_capture_takes_two_calls() throws Exception {
            String id = read(createIntent(5000, "manual")).get("id").asText();

            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("requires_capture"))
                    .andExpect(jsonPath("$.amount_capturable").value(5000));

            mvc.perform(post("/v1/payment_intents/" + id + "/capture")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("amount_to_capture", 3000))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("succeeded"))
                    .andExpect(jsonPath("$.amount_received").value(3000))
                    .andExpect(jsonPath("$.amount_capturable").value(0));

            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.of(3000, USD));
        }

        @Test
        void cancel_releases_the_hold() throws Exception {
            String id = read(createIntent(5000, "manual")).get("id").asText();
            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                    .header("Idempotency-Key", Ids.generate("key")));

            mvc.perform(post("/v1/payment_intents/" + id + "/cancel")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("cancellation_reason", "requested_by_customer"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("canceled"))
                    .andExpect(jsonPath("$.cancellation_reason").value("requested_by_customer"));
        }

        @Test
        void a_refund_comes_back_in_stripes_shape() throws Exception {
            String id = read(createIntent(2000, "automatic")).get("id").asText();
            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                    .header("Idempotency-Key", Ids.generate("key")));

            MvcResult charges = mvc.perform(get("/v1/payment_intents/" + id + "/charges"))
                    .andExpect(status().isOk())
                    .andReturn();
            String chargeId = read(charges).get("data").get(0).get("id").asText();
            assertThat(chargeId).startsWith("ch_");

            mvc.perform(post("/v1/refunds")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("charge", chargeId, "amount", 500,
                                    "reason", "requested_by_customer"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("re_")))
                    .andExpect(jsonPath("$.object").value("refund"))
                    .andExpect(jsonPath("$.amount").value(500))
                    .andExpect(jsonPath("$.status").value("succeeded"));
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        void a_missing_intent_is_404_with_resource_missing() throws Exception {
            mvc.perform(get("/v1/payment_intents/pi_nope"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                    .andExpect(jsonPath("$.error.code").value("resource_missing"));
        }

        @Test
        void a_declined_payment_is_402_with_a_decline_code() throws Exception {
            // Asking for more than the customer has.
            String id = read(createIntent(500_000, "automatic")).get("id").asText();

            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key")))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.error.type").value("card_error"))
                    .andExpect(jsonPath("$.error.code").value("card_declined"))
                    .andExpect(jsonPath("$.error.decline_code").value("insufficient_funds"));
        }

        @Test
        void a_bad_transition_is_400_not_500() throws Exception {
            String id = read(createIntent(2000, "automatic")).get("id").asText();
            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                    .header("Idempotency-Key", Ids.generate("key")));

            mvc.perform(post("/v1/payment_intents/" + id + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("payment_intent_unexpected_state"));
        }

        @Test
        void a_missing_required_field_is_400_naming_the_field() throws Exception {
            mvc.perform(post("/v1/payment_intents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("currency", "usd",
                                    "customer", customerId,
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                    .andExpect(jsonPath("$.error.code").value("parameter_invalid"))
                    .andExpect(jsonPath("$.error.message")
                            .value(org.hamcrest.Matchers.containsString("amount")));
        }

        @Test
        void a_zero_amount_is_rejected() throws Exception {
            mvc.perform(post("/v1/payment_intents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("amount", 0, "currency", "usd",
                                    "customer", customerId,
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
        }

        @Test
        void malformed_json_is_400_not_500() throws Exception {
            mvc.perform(post("/v1/payment_intents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
        }

        @Test
        void an_unknown_customer_is_404() throws Exception {
            mvc.perform(post("/v1/payment_intents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("amount", 1000, "currency", "usd",
                                    "customer", "cus_nope",
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("resource_missing"));
        }
    }

    @Nested
    @DisplayName("idempotency over HTTP")
    class Idempotent {

        @Test
        void the_same_key_and_body_returns_the_same_object() throws Exception {
            String key = Ids.generate("key");
            String payload = body(Map.of("amount", 2000, "currency", "usd",
                    "customer", customerId, "merchant_account", merchantAccount));

            MvcResult first = mvc.perform(post("/v1/payment_intents")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated())
                    .andReturn();

            MvcResult second = mvc.perform(post("/v1/payment_intents")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated())
                    .andReturn();

            assertThat(read(second).get("id").asText())
                    .isEqualTo(read(first).get("id").asText());
        }

        @Test
        void the_same_key_with_a_different_body_is_422() throws Exception {
            String key = Ids.generate("key");

            mvc.perform(post("/v1/payment_intents")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("amount", 2000, "currency", "usd",
                                    "customer", customerId,
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isCreated());

            mvc.perform(post("/v1/payment_intents")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("amount", 9999, "currency", "usd",
                                    "customer", customerId,
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.type").value("idempotency_error"));
        }

        @Test
        void a_request_without_a_key_still_works() throws Exception {
            mvc.perform(post("/v1/payment_intents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("amount", 1500, "currency", "usd",
                                    "customer", customerId,
                                    "merchant_account", merchantAccount))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.amount").value(1500));
        }
    }
}
