package com.stripeclone.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.stripeclone.money.Currency.INR;
import static com.stripeclone.money.Currency.JPY;
import static com.stripeclone.money.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountTest {

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void adds_and_subtracts_within_a_currency() {
            assertThat(Amount.of(1000, USD).plus(Amount.of(250, USD)))
                    .isEqualTo(Amount.of(1250, USD));
            assertThat(Amount.of(1000, USD).minus(Amount.of(250, USD)))
                    .isEqualTo(Amount.of(750, USD));
        }

        @Test
        void allows_negative_results_because_entries_are_signed() {
            assertThat(Amount.of(100, USD).minus(Amount.of(300, USD)))
                    .isEqualTo(Amount.of(-200, USD));
        }

        @Test
        void refuses_to_mix_currencies() {
            assertThatThrownBy(() -> Amount.of(1000, USD).plus(Amount.of(1000, INR)))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("USD")
                    .hasMessageContaining("INR");
        }

        @Test
        void throws_on_overflow_rather_than_wrapping_to_a_negative_balance() {
            Amount huge = Amount.of(Long.MAX_VALUE, USD);
            assertThatThrownBy(() -> huge.plus(Amount.of(1, USD)))
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("Overflow");
        }

        @Test
        void throws_on_multiplication_overflow() {
            assertThatThrownBy(() -> Amount.of(Long.MAX_VALUE, USD).times(2))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    @DisplayName("allocate splits money without losing or inventing units")
    class Allocate {

        @Test
        void distributes_an_indivisible_remainder_to_the_leading_parts() {
            List<Amount> parts = Amount.of(100, USD).allocate(3);

            assertThat(parts).containsExactly(
                    Amount.of(34, USD), Amount.of(33, USD), Amount.of(33, USD));
        }

        @ParameterizedTest
        @ValueSource(longs = {1, 7, 99, 100, 101, 1_000_003, 999_999_999})
        void parts_always_sum_back_to_the_original(long minorUnits) {
            for (int parts = 1; parts <= 7; parts++) {
                long sum = Amount.of(minorUnits, USD).allocate(parts).stream()
                        .mapToLong(Amount::minorUnits)
                        .sum();

                assertThat(sum)
                        .as("%d split %d ways", minorUnits, parts)
                        .isEqualTo(minorUnits);
            }
        }

        @Test
        void splits_a_negative_amount_into_negative_parts() {
            List<Amount> parts = Amount.of(-100, USD).allocate(3);

            assertThat(parts).containsExactly(
                    Amount.of(-34, USD), Amount.of(-33, USD), Amount.of(-33, USD));
            assertThat(parts.stream().mapToLong(Amount::minorUnits).sum()).isEqualTo(-100);
        }

        @Test
        void rejects_a_non_positive_number_of_parts() {
            assertThatThrownBy(() -> Amount.of(100, USD).allocate(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("weighted allocation, for fee splits")
    class WeightedAllocate {

        @Test
        void divides_in_proportion_to_the_weights() {
            List<Amount> parts = Amount.of(1000, USD).allocateByWeights(70, 30);

            assertThat(parts).containsExactly(Amount.of(700, USD), Amount.of(300, USD));
        }

        @Test
        void gives_the_truncation_remainder_away_rather_than_dropping_it() {
            // 100 split 1:1:1 truncates to 33 each, leaving 1 unit that must go somewhere.
            List<Amount> parts = Amount.of(100, USD).allocateByWeights(1, 1, 1);

            assertThat(parts.stream().mapToLong(Amount::minorUnits).sum()).isEqualTo(100);
            assertThat(parts).containsExactly(
                    Amount.of(34, USD), Amount.of(33, USD), Amount.of(33, USD));
        }

        @Test
        void rejects_weights_that_sum_to_zero() {
            assertThatThrownBy(() -> Amount.of(100, USD).allocateByWeights(0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejects_negative_weights() {
            assertThatThrownBy(() -> Amount.of(100, USD).allocateByWeights(5, -5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("currency exponents")
    class Exponents {

        @Test
        void a_zero_decimal_currency_has_no_subdivision() {
            assertThat(JPY.minorUnitsPerMajor()).isEqualTo(1);
            assertThat(Amount.of(1000, JPY)).hasToString("1000 JPY");
        }

        @Test
        void a_two_decimal_currency_formats_with_two_places() {
            assertThat(USD.minorUnitsPerMajor()).isEqualTo(100);
            assertThat(Amount.of(1000, USD)).hasToString("10.00 USD");
            assertThat(Amount.of(1, USD)).hasToString("0.01 USD");
        }

        @Test
        void formats_a_negative_sub_unit_amount_with_its_sign() {
            assertThat(Amount.of(-5, USD)).hasToString("-0.05 USD");
        }

        @Test
        void rejects_an_unknown_currency_code() {
            assertThatThrownBy(() -> Currency.of("XYZ"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XYZ");
        }
    }
}
