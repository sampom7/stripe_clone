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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class PaymentMethodApiTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    LedgerService ledger;

    private static final String EXTERNAL = "acct_external_usd";
    private String customerId;
    private String customerAccount;
    private String merchantAccount;

    @BeforeEach
    void seed() throws Exception {
        ledger.createAccount(EXTERNAL, AccountType.EXTERNAL, USD);

        MvcResult created = mvc.perform(post("/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "buyer@example.com", "currency", "usd"))))
                .andExpect(status().isCreated())
                .andReturn();
        customerId = read(created).get("id").asText();

        customerAccount = jdbc.sql("SELECT account_id FROM customers WHERE customer_id = :id")
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

    private String createCard(String number) throws Exception {
        MvcResult result = mvc.perform(post("/v1/payment_methods")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "type", "card",
                                "card", Map.of(
                                        "number", number,
                                        "exp_month", 12,
                                        "exp_year", 2030,
                                        "cvc", "123")))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asText();
    }

    private String createIntent(long amount) throws Exception {
        MvcResult result = mvc.perform(post("/v1/payment_intents")
                        .header("Idempotency-Key", Ids.generate("key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "amount", amount,
                                "currency", "usd",
                                "customer", customerId,
                                "merchant_account", merchantAccount))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asText();
    }

    @Nested
    @DisplayName("creating payment methods")
    class Creating {

        @Test
        void a_card_comes_back_in_stripes_shape() throws Exception {
            mvc.perform(post("/v1/payment_methods")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "type", "card",
                                    "card", Map.of(
                                            "number", "4242424242424242",
                                            "exp_month", 12,
                                            "exp_year", 2030)))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("pm_")))
                    .andExpect(jsonPath("$.object").value("payment_method"))
                    .andExpect(jsonPath("$.type").value("card"))
                    .andExpect(jsonPath("$.card.brand").value("visa"))
                    .andExpect(jsonPath("$.card.last4").value("4242"))
                    .andExpect(jsonPath("$.card.exp_month").value(12));
        }

        @Test
        void the_card_number_is_never_stored() throws Exception {
            createCard("4242424242424242");

            Long matches = jdbc.sql("""
                    SELECT count(*) FROM payment_methods
                     WHERE last4 = '4242424242424242'
                        OR brand = '4242424242424242'
                        OR fingerprint = '4242424242424242'
                    """)
                    .query(Long.class)
                    .single();

            assertThat(matches).isZero();
        }

        @Test
        void an_invalid_number_is_rejected_before_anything_else() throws Exception {
            mvc.perform(post("/v1/payment_methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "type", "card",
                                    "card", Map.of(
                                            "number", "4242424242424243",
                                            "exp_month", 12,
                                            "exp_year", 2030)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
        }

        @Test
        void a_bad_expiry_month_is_rejected() throws Exception {
            mvc.perform(post("/v1/payment_methods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "type", "card",
                                    "card", Map.of(
                                            "number", "4242424242424242",
                                            "exp_month", 13,
                                            "exp_year", 2030)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.message")
                            .value(org.hamcrest.Matchers.containsString("exp_month")));
        }
    }

    @Nested
    @DisplayName("attach and detach")
    class Attaching {

        @Test
        void attaching_puts_the_method_on_the_customer() throws Exception {
            String pmId = createCard("4242424242424242");

            mvc.perform(post("/v1/payment_methods/" + pmId + "/attach")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("customer", customerId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customer").value(customerId));

            mvc.perform(get("/v1/payment_methods").param("customer", customerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.object").value("list"))
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(pmId));
        }

        @Test
        void detaching_takes_it_off_again() throws Exception {
            String pmId = createCard("4242424242424242");
            mvc.perform(post("/v1/payment_methods/" + pmId + "/attach")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("customer", customerId))));

            mvc.perform(post("/v1/payment_methods/" + pmId + "/detach"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customer").doesNotExist());

            mvc.perform(get("/v1/payment_methods").param("customer", customerId))
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        void attaching_to_an_unknown_customer_is_404() throws Exception {
            String pmId = createCard("4242424242424242");

            mvc.perform(post("/v1/payment_methods/" + pmId + "/attach")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("customer", "cus_nope"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("resource_missing"));
        }

        @Test
        void an_unknown_payment_method_is_404() throws Exception {
            mvc.perform(get("/v1/payment_methods/pm_nope"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("resource_missing"));
        }
    }

    @Nested
    @DisplayName("paying with a card")
    class Paying {

        @Test
        void an_approved_card_completes_the_payment() throws Exception {
            String pmId = createCard("4242424242424242");
            String intentId = createIntent(2000);

            mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "payment_method", pmId,
                                    "card_number", "4242424242424242"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("succeeded"));

            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.of(2000, USD));
        }

        @ParameterizedTest
        @DisplayName("each declining card gives its documented code and moves no money")
        @CsvSource({
                "4000000000000002, card_declined,    generic_decline",
                "4000000000009995, card_declined,    insufficient_funds",
                "4000000000009987, card_declined,    lost_card",
                "4000000000009979, card_declined,    stolen_card"
        })
        void declines_report_the_right_code(String number, String code, String declineCode)
                throws Exception {

            String pmId = createCard(number);
            String intentId = createIntent(2000);

            mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "payment_method", pmId,
                                    "card_number", number))))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.error.type").value("card_error"))
                    .andExpect(jsonPath("$.error.code").value(code))
                    .andExpect(jsonPath("$.error.decline_code").value(declineCode));

            assertThat(ledger.balanceOf(merchantAccount)).isEqualTo(Amount.zero(USD));
            assertThat(ledger.balanceOf(customerAccount)).isEqualTo(Amount.of(100_000, USD));
            assertThat(ledger.isBalanced()).isTrue();
        }

        @ParameterizedTest
        @DisplayName("non-decline card errors report their own code")
        @CsvSource({
                "4000000000000069, expired_card",
                "4000000000000127, incorrect_cvc",
                "4000000000000119, processing_error"
        })
        void other_card_errors_report_their_code(String number, String code) throws Exception {
            String pmId = createCard(number);
            String intentId = createIntent(2000);

            mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "payment_method", pmId,
                                    "card_number", number))))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.error.code").value(code));
        }

        @Test
        void a_declined_intent_can_still_be_paid_with_a_good_card() throws Exception {
            String badPm = createCard("4000000000000002");
            String goodPm = createCard("4242424242424242");
            String intentId = createIntent(2000);

            mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("payment_method", badPm,
                                    "card_number", "4000000000000002"))))
                    .andExpect(status().isPaymentRequired());

            // The intent is untouched, so a second attempt works.
            mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("payment_method", goodPm,
                                    "card_number", "4242424242424242"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("succeeded"));
        }

        @Test
        void an_approved_card_with_no_money_behind_it_is_still_declined() throws Exception {
            // The network says yes, the ledger says no. Two different failures.
            String pmId = createCard("4242424242424242");
            String intentId = createIntent(500_000);

            mvc.perform(post("/v1/payment_intents/" + intentId + "/confirm")
                            .header("Idempotency-Key", Ids.generate("key"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("payment_method", pmId,
                                    "card_number", "4242424242424242"))))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.error.decline_code").value("insufficient_funds"));

            assertThat(ledger.isBalanced()).isTrue();
        }
    }
}
