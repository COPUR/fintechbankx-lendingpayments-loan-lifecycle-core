package com.bank.loan.infrastructure.messaging;

import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/**
 * The fields this service reads from Payments.Payment.LoanPaymentCompleted.v1
 * (provider svc-pay-initiation-settlement, topic
 * evt.pay.payment.loan-payment-completed.v1). Unknown fields are ignored so
 * additive changes on the provider side do not break the consumer.
 */
record LoanPaymentCompleted(UUID eventId, String paymentId, String loanId, Money actualAmount) {

    static final String TOPIC = "evt.pay.payment.loan-payment-completed.v1";
    static final String EVENT_TYPE = "Payments.Payment.LoanPaymentCompleted.v1";

    static LoanPaymentCompleted parse(ObjectMapper json, String value) {
        JsonNode envelope;
        try {
            envelope = json.readTree(value);
        } catch (Exception notJson) {
            throw new ContractViolationException("Record value is not JSON", notJson);
        }
        if (envelope == null || !envelope.isObject()) {
            throw new ContractViolationException("Record value is not a JSON object");
        }
        if (!EVENT_TYPE.equals(text(envelope, "eventType"))) {
            throw new ContractViolationException("Unexpected eventType " + text(envelope, "eventType"));
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(required(envelope, "eventId"));
        } catch (IllegalArgumentException notUuid) {
            throw new ContractViolationException("eventId is not a UUID", notUuid);
        }
        JsonNode data = envelope.path("data");
        JsonNode amount = data.path("actualAmount");
        try {
            Money actual = Money.of(new BigDecimal(required(amount, "amount")),
                Currency.getInstance(required(amount, "currency")));
            return new LoanPaymentCompleted(eventId, required(data, "paymentId"), required(data, "loanId"), actual);
        } catch (NumberFormatException badAmount) {
            throw new ContractViolationException("actualAmount.amount is not a decimal", badAmount);
        } catch (ContractViolationException violation) {
            throw violation;
        } catch (IllegalArgumentException badCurrency) {
            throw new ContractViolationException("actualAmount.currency is not an ISO 4217 code", badCurrency);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new ContractViolationException("Missing field " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
