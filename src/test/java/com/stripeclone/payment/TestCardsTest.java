package com.stripeclone.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCardsTest {

    @ParameterizedTest
    @DisplayName("each published test number produces its documented outcome")
    @CsvSource({
            "4242424242424242, APPROVED",
            "5555555555554444, APPROVED",
            "378282246310005,  APPROVED",
            "4000000000000002, DECLINED",
            "4000000000009995, INSUFFICIENT_FUNDS",
            "4000000000009987, LOST_CARD",
            "4000000000009979, STOLEN_CARD",
            "4000000000000069, EXPIRED_CARD",
            "4000000000000127, INCORRECT_CVC",
            "4000000000000119, PROCESSING_ERROR"
    })
    void outcomes_match_stripes_documentation(String number, TestCards.Outcome expected) {
        assertThat(TestCards.outcomeFor(number)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unknown number is declined rather than approved")
    void unknown_numbers_default_to_declined() {
        // Defaulting the other way would mean a typo in a test silently passes.
        assertThat(TestCards.outcomeFor("4111111111111111"))
                .isEqualTo(TestCards.Outcome.DECLINED);
        assertThat(TestCards.isKnown("4111111111111111")).isFalse();
    }

    @ParameterizedTest
    @DisplayName("brand comes from the number's prefix")
    @CsvSource({
            "4242424242424242, visa",
            "5555555555554444, mastercard",
            "378282246310005,  amex",
            "6011111111111117, discover"
    })
    void brands_are_derived_from_the_prefix(String number, String brand) {
        assertThat(TestCards.brandOf(number)).isEqualTo(brand);
    }

    @Test
    void last4_is_the_trailing_four_digits() {
        assertThat(TestCards.last4("4242424242424242")).isEqualTo("4242");
        assertThat(TestCards.last4("4000 0000 0000 9995")).isEqualTo("9995");
    }

    @Test
    void spaces_and_dashes_are_ignored() {
        assertThat(TestCards.outcomeFor("4242 4242 4242 4242"))
                .isEqualTo(TestCards.Outcome.APPROVED);
        assertThat(TestCards.outcomeFor("4242-4242-4242-4242"))
                .isEqualTo(TestCards.Outcome.APPROVED);
    }

    @Test
    void a_short_number_has_no_last4() {
        assertThatThrownBy(() -> TestCards.last4("42"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @DisplayName("every test number passes the Luhn check")
    @ValueSource(strings = {
            "4242424242424242", "5555555555554444", "378282246310005",
            "6011111111111117", "4000000000000002", "4000000000009995",
            "4000000000009987", "4000000000009979", "4000000000000069",
            "4000000000000127", "4000000000000119"
    })
    void test_numbers_are_luhn_valid(String number) {
        assertThat(TestCards.passesLuhn(number)).isTrue();
    }

    @Test
    void luhn_rejects_a_transposed_digit() {
        assertThat(TestCards.passesLuhn("4242424242424243")).isFalse();
        assertThat(TestCards.passesLuhn("not-a-number")).isFalse();
        assertThat(TestCards.passesLuhn("42")).isFalse();
    }

    @Test
    void the_same_number_always_fingerprints_the_same() {
        assertThat(TestCards.fingerprint("4242424242424242"))
                .isEqualTo(TestCards.fingerprint("4242 4242 4242 4242"))
                .isNotEqualTo(TestCards.fingerprint("5555555555554444"));
    }

    @Test
    void declines_carry_a_code_and_approvals_do_not() {
        assertThat(TestCards.Outcome.APPROVED.isApproved()).isTrue();
        assertThat(TestCards.Outcome.APPROVED.code()).isNull();

        assertThat(TestCards.Outcome.INSUFFICIENT_FUNDS.code()).isEqualTo("card_declined");
        assertThat(TestCards.Outcome.INSUFFICIENT_FUNDS.declineCode())
                .isEqualTo("insufficient_funds");
        assertThat(TestCards.Outcome.EXPIRED_CARD.code()).isEqualTo("expired_card");
    }
}
