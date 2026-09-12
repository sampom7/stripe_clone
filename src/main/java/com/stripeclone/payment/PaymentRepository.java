package com.stripeclone.payment;

import com.stripeclone.money.Amount;
import com.stripeclone.money.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------- payment intents

    public void insertIntent(PaymentIntent intent) {
        jdbc.sql("""
                INSERT INTO payment_intents
                       (payment_intent_id, customer_id, customer_account, merchant_account,
                        hold_account, amount, amount_capturable, amount_received,
                        currency, status, capture_method, description)
                VALUES (:id, :customerId, :customerAccount, :merchantAccount,
                        :holdAccount, :amount, :capturable, :received,
                        :currency, :status, :captureMethod, :description)
                """)
                .param("id", intent.paymentIntentId())
                .param("customerId", intent.customerId())
                .param("customerAccount", intent.customerAccount())
                .param("merchantAccount", intent.merchantAccount())
                .param("holdAccount", intent.holdAccount())
                .param("amount", intent.amount().minorUnits())
                .param("capturable", intent.amountCapturable().minorUnits())
                .param("received", intent.amountReceived().minorUnits())
                .param("currency", intent.currency().name())
                .param("status", intent.status().wireValue())
                .param("captureMethod", intent.captureMethod().wireValue())
                .param("description", intent.description())
                .update();
    }

    public Optional<PaymentIntent> findIntent(String paymentIntentId) {
        return jdbc.sql(SELECT_INTENT + " WHERE payment_intent_id = :id")
                .param("id", paymentIntentId)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    /**
     * Reads an intent and locks its row.
     *
     * <p>Capture and cancel both read the status, decide, then write. Without the lock two
     * concurrent captures could both read {@code requires_capture} and both proceed.
     */
    public Optional<PaymentIntent> findIntentForUpdate(String paymentIntentId) {
        return jdbc.sql(SELECT_INTENT + " WHERE payment_intent_id = :id FOR UPDATE")
                .param("id", paymentIntentId)
                .query(PaymentRepository::mapIntent)
                .optional();
    }

    public List<PaymentIntent> listIntents(int limit, String startingAfter) {
        if (startingAfter == null) {
            return jdbc.sql(SELECT_INTENT + " ORDER BY id DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(PaymentRepository::mapIntent)
                    .list();
        }
        return jdbc.sql(SELECT_INTENT + """
                 WHERE id < (SELECT id FROM payment_intents WHERE payment_intent_id = :after)
                 ORDER BY id DESC
                 LIMIT :limit
                """)
                .param("after", startingAfter)
                .param("limit", limit)
                .query(PaymentRepository::mapIntent)
                .list();
    }

    public void updateIntentStatus(String paymentIntentId, PaymentIntentStatus status) {
        jdbc.sql("""
                UPDATE payment_intents
                   SET status = :status, updated_at = now()
                 WHERE payment_intent_id = :id
                """)
                .param("id", paymentIntentId)
                .param("status", status.wireValue())
                .update();
    }

    public void updateIntentAmounts(
            String paymentIntentId,
            PaymentIntentStatus status,
            Amount capturable,
            Amount received) {

        jdbc.sql("""
                UPDATE payment_intents
                   SET status = :status,
                       amount_capturable = :capturable,
                       amount_received = :received,
                       updated_at = now()
                 WHERE payment_intent_id = :id
                """)
                .param("id", paymentIntentId)
                .param("status", status.wireValue())
                .param("capturable", capturable.minorUnits())
                .param("received", received.minorUnits())
                .update();
    }

    public void setHoldAccount(String paymentIntentId, String holdAccount) {
        jdbc.sql("""
                UPDATE payment_intents
                   SET hold_account = :holdAccount, updated_at = now()
                 WHERE payment_intent_id = :id
                """)
                .param("id", paymentIntentId)
                .param("holdAccount", holdAccount)
                .update();
    }

    public void setCancellation(String paymentIntentId, String reason) {
        jdbc.sql("""
                UPDATE payment_intents
                   SET status = 'canceled',
                       cancellation_reason = :reason,
                       amount_capturable = 0,
                       updated_at = now()
                 WHERE payment_intent_id = :id
                """)
                .param("id", paymentIntentId)
                .param("reason", reason)
                .update();
    }

    // ---------------------------------------------------------------- charges

    public void insertCharge(Charge charge) {
        jdbc.sql("""
                INSERT INTO charges
                       (charge_id, payment_intent_id, amount, amount_refunded,
                        currency, status, ledger_txn_id)
                VALUES (:id, :intentId, :amount, :refunded, :currency, :status, :txnId)
                """)
                .param("id", charge.chargeId())
                .param("intentId", charge.paymentIntentId())
                .param("amount", charge.amount().minorUnits())
                .param("refunded", charge.amountRefunded().minorUnits())
                .param("currency", charge.amount().currency().name())
                .param("status", charge.status().wireValue())
                .param("txnId", charge.ledgerTxnId())
                .update();
    }

    public Optional<Charge> findCharge(String chargeId) {
        return jdbc.sql(SELECT_CHARGE + " WHERE charge_id = :id")
                .param("id", chargeId)
                .query(PaymentRepository::mapCharge)
                .optional();
    }

    public Optional<Charge> findChargeForUpdate(String chargeId) {
        return jdbc.sql(SELECT_CHARGE + " WHERE charge_id = :id FOR UPDATE")
                .param("id", chargeId)
                .query(PaymentRepository::mapCharge)
                .optional();
    }

    public List<Charge> findChargesForIntent(String paymentIntentId) {
        return jdbc.sql(SELECT_CHARGE + " WHERE payment_intent_id = :intentId ORDER BY id")
                .param("intentId", paymentIntentId)
                .query(PaymentRepository::mapCharge)
                .list();
    }

    public void updateChargeRefunded(String chargeId, Amount amountRefunded, Charge.ChargeStatus status) {
        jdbc.sql("""
                UPDATE charges
                   SET amount_refunded = :refunded, status = :status
                 WHERE charge_id = :id
                """)
                .param("id", chargeId)
                .param("refunded", amountRefunded.minorUnits())
                .param("status", status.wireValue())
                .update();
    }

    // ---------------------------------------------------------------- refunds

    public void insertRefund(Refund refund) {
        jdbc.sql("""
                INSERT INTO refunds
                       (refund_id, charge_id, amount, currency, reason, status, ledger_txn_id)
                VALUES (:id, :chargeId, :amount, :currency, :reason, :status, :txnId)
                """)
                .param("id", refund.refundId())
                .param("chargeId", refund.chargeId())
                .param("amount", refund.amount().minorUnits())
                .param("currency", refund.amount().currency().name())
                .param("reason", refund.reason())
                .param("status", refund.status().wireValue())
                .param("txnId", refund.ledgerTxnId())
                .update();
    }

    public Optional<Refund> findRefund(String refundId) {
        return jdbc.sql(SELECT_REFUND + " WHERE refund_id = :id")
                .param("id", refundId)
                .query(PaymentRepository::mapRefund)
                .optional();
    }

    public List<Refund> findRefundsForCharge(String chargeId) {
        return jdbc.sql(SELECT_REFUND + " WHERE charge_id = :chargeId ORDER BY id")
                .param("chargeId", chargeId)
                .query(PaymentRepository::mapRefund)
                .list();
    }

    // -------------------------------------------------------------- customers

    public void insertCustomer(Customer customer) {
        jdbc.sql("""
                INSERT INTO customers (customer_id, email, name, account_id)
                VALUES (:id, :email, :name, :accountId)
                """)
                .param("id", customer.customerId())
                .param("email", customer.email())
                .param("name", customer.name())
                .param("accountId", customer.accountId())
                .update();
    }

    public Optional<Customer> findCustomer(String customerId) {
        return jdbc.sql("""
                SELECT customer_id, email, name, account_id, created_at
                  FROM customers
                 WHERE customer_id = :id
                """)
                .param("id", customerId)
                .query((ResultSet rs, int rowNum) -> new Customer(
                        rs.getString("customer_id"),
                        rs.getString("email"),
                        rs.getString("name"),
                        rs.getString("account_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    // -------------------------------------------------------- payment methods

    public void insertPaymentMethod(PaymentMethod method) {
        jdbc.sql("""
                INSERT INTO payment_methods
                       (payment_method_id, customer_id, type, brand, last4,
                        exp_month, exp_year, fingerprint)
                VALUES (:id, :customerId, :type, :brand, :last4,
                        :expMonth, :expYear, :fingerprint)
                """)
                .param("id", method.paymentMethodId())
                .param("customerId", method.customerId())
                .param("type", method.type())
                .param("brand", method.brand())
                .param("last4", method.last4())
                .param("expMonth", method.expMonth())
                .param("expYear", method.expYear())
                .param("fingerprint", method.fingerprint())
                .update();
    }

    public Optional<PaymentMethod> findPaymentMethod(String paymentMethodId) {
        return jdbc.sql("""
                SELECT payment_method_id, customer_id, type, brand, last4,
                       exp_month, exp_year, fingerprint, created_at
                  FROM payment_methods
                 WHERE payment_method_id = :id
                """)
                .param("id", paymentMethodId)
                .query(PaymentRepository::mapPaymentMethod)
                .optional();
    }

    public List<PaymentMethod> findPaymentMethodsForCustomer(String customerId) {
        return jdbc.sql("""
                SELECT payment_method_id, customer_id, type, brand, last4,
                       exp_month, exp_year, fingerprint, created_at
                  FROM payment_methods
                 WHERE customer_id = :customerId
                 ORDER BY id DESC
                """)
                .param("customerId", customerId)
                .query(PaymentRepository::mapPaymentMethod)
                .list();
    }

    public void attachPaymentMethod(String paymentMethodId, String customerId) {
        jdbc.sql("""
                UPDATE payment_methods
                   SET customer_id = :customerId
                 WHERE payment_method_id = :id
                """)
                .param("id", paymentMethodId)
                .param("customerId", customerId)
                .update();
    }

    public void detachPaymentMethod(String paymentMethodId) {
        jdbc.sql("""
                UPDATE payment_methods
                   SET customer_id = NULL
                 WHERE payment_method_id = :id
                """)
                .param("id", paymentMethodId)
                .update();
    }

    public void setIntentPaymentMethod(String paymentIntentId, String paymentMethodId) {
        jdbc.sql("""
                UPDATE payment_intents
                   SET payment_method_id = :paymentMethodId, updated_at = now()
                 WHERE payment_intent_id = :id
                """)
                .param("id", paymentIntentId)
                .param("paymentMethodId", paymentMethodId)
                .update();
    }

    public Optional<String> findIntentPaymentMethod(String paymentIntentId) {
        return jdbc.sql("""
                SELECT payment_method_id FROM payment_intents
                 WHERE payment_intent_id = :id AND payment_method_id IS NOT NULL
                """)
                .param("id", paymentIntentId)
                .query(String.class)
                .optional();
    }

    private static PaymentMethod mapPaymentMethod(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentMethod(
                rs.getString("payment_method_id"),
                rs.getString("customer_id"),
                rs.getString("type"),
                rs.getString("brand"),
                rs.getString("last4"),
                rs.getObject("exp_month", Integer.class),
                rs.getObject("exp_year", Integer.class),
                rs.getString("fingerprint"),
                rs.getTimestamp("created_at").toInstant());
    }

    // ----------------------------------------------------------------- mapping

    private static final String SELECT_INTENT = """
            SELECT payment_intent_id, customer_id, customer_account, merchant_account,
                   hold_account, amount, amount_capturable, amount_received,
                   currency, status, capture_method, description, cancellation_reason,
                   created_at, updated_at
              FROM payment_intents
            """;

    private static final String SELECT_CHARGE = """
            SELECT charge_id, payment_intent_id, amount, amount_refunded,
                   currency, status, ledger_txn_id, created_at
              FROM charges
            """;

    private static final String SELECT_REFUND = """
            SELECT refund_id, charge_id, amount, currency, reason, status,
                   ledger_txn_id, created_at
              FROM refunds
            """;

    private static PaymentIntent mapIntent(ResultSet rs, int rowNum) throws SQLException {
        Currency currency = Currency.of(rs.getString("currency"));
        return new PaymentIntent(
                rs.getString("payment_intent_id"),
                rs.getString("customer_id"),
                rs.getString("customer_account"),
                rs.getString("merchant_account"),
                rs.getString("hold_account"),
                Amount.of(rs.getLong("amount"), currency),
                Amount.of(rs.getLong("amount_capturable"), currency),
                Amount.of(rs.getLong("amount_received"), currency),
                PaymentIntentStatus.fromWire(rs.getString("status")),
                CaptureMethod.fromWire(rs.getString("capture_method")),
                rs.getString("description"),
                rs.getString("cancellation_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Charge mapCharge(ResultSet rs, int rowNum) throws SQLException {
        Currency currency = Currency.of(rs.getString("currency"));
        return new Charge(
                rs.getString("charge_id"),
                rs.getString("payment_intent_id"),
                Amount.of(rs.getLong("amount"), currency),
                Amount.of(rs.getLong("amount_refunded"), currency),
                Charge.ChargeStatus.fromWire(rs.getString("status")),
                rs.getString("ledger_txn_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Refund mapRefund(ResultSet rs, int rowNum) throws SQLException {
        Currency currency = Currency.of(rs.getString("currency"));
        return new Refund(
                rs.getString("refund_id"),
                rs.getString("charge_id"),
                Amount.of(rs.getLong("amount"), currency),
                rs.getString("reason"),
                Refund.RefundStatus.fromWire(rs.getString("status")),
                rs.getString("ledger_txn_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
