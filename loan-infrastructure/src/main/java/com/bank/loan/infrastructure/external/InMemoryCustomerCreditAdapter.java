package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.LoanId;
import com.bank.loan.domain.port.out.CreditCurrencyMismatchException;
import com.bank.loan.domain.port.out.CreditCustomerNotFoundException;
import com.bank.loan.domain.port.out.CustomerCreditService;
import com.bank.loan.infrastructure.config.CustomerCreditClientConfiguration;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory customer credit adapter for local runs and tests, behaving like
 * the customer service: unknown customer is {@link CreditCustomerNotFoundException},
 * another currency is {@link CreditCurrencyMismatchException}, not enough credit
 * is a refusal. The three customers are the ones the monolith's credit stub
 * knows (CUST-12345678, CUST-87654321, CUST-11111111), so the regression
 * suite can seed both sides alike.
 *
 * Enabled only with {@code loan.customer-credit.adapter=in-memory}. Credit is
 * held in the configured ledger currency (CUSTOMER_CREDIT_LEDGER_CURRENCY);
 * there is no default currency. Every deployed environment uses
 * {@link CustomerProfileHttpAdapter}.
 */
@Component
@ConditionalOnProperty(name = "loan.customer-credit.adapter", havingValue = "in-memory")
public class InMemoryCustomerCreditAdapter implements CustomerCreditService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCustomerCreditAdapter.class);

    private final Currency currency;
    private final Map<String, Credit> credit = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryCustomerCreditAdapter(@Value("${loan.customer-credit.ledger-currency:}") String ledgerCurrency) {
        this(CustomerCreditClientConfiguration.ledgerCurrency(ledgerCurrency));
    }

    public InMemoryCustomerCreditAdapter(Currency currency) {
        this.currency = currency;
        credit.put("CUST-12345678", new Credit(new BigDecimal("100000"), BigDecimal.ZERO));
        credit.put("CUST-87654321", new Credit(new BigDecimal("50000"), new BigDecimal("10000")));
        credit.put("CUST-11111111", new Credit(new BigDecimal("25000"), new BigDecimal("20000")));
    }

    @Override
    public boolean hasAvailableCredit(CustomerId customerId, Money amount) {
        Credit found = require(customerId);
        requireCurrency(amount);
        return found.available().compareTo(amount.getAmount()) >= 0;
    }

    private void requireCurrency(Money amount) {
        if (!amount.getCurrency().equals(currency)) {
            throw new CreditCurrencyMismatchException(amount.getCurrency(), currency);
        }
    }

    @Override
    public synchronized CreditDecision reserveCredit(LoanId loanId, CustomerId customerId, Money amount) {
        Credit current = require(customerId);
        if (!hasAvailableCredit(customerId, amount)) {
            return CreditDecision.REFUSED;
        }
        credit.put(customerId.getValue(), new Credit(current.limit(), current.used().add(amount.getAmount())));
        log.debug("Reserved {} for loan {}", amount, loanId.getValue());
        return CreditDecision.ACCEPTED;
    }

    @Override
    public CreditDecision cancelReservation(LoanId loanId, CustomerId customerId, Money amount) {
        return releaseCredit(loanId, customerId, amount);
    }

    @Override
    public synchronized CreditDecision releaseCredit(LoanId loanId, CustomerId customerId, Money amount) {
        Credit current = require(customerId);
        requireCurrency(amount);
        BigDecimal used = current.used().subtract(amount.getAmount()).max(BigDecimal.ZERO);
        credit.put(customerId.getValue(), new Credit(current.limit(), used));
        log.debug("Released {} for loan {}", amount, loanId.getValue());
        return CreditDecision.ACCEPTED;
    }

    @Override
    public Money getAvailableCredit(CustomerId customerId) {
        return Money.of(require(customerId).available(), currency);
    }

    private Credit require(CustomerId customerId) {
        Credit found = credit.get(customerId.getValue());
        if (found == null) {
            throw new CreditCustomerNotFoundException(customerId.getValue());
        }
        return found;
    }

    private record Credit(BigDecimal limit, BigDecimal used) {
        BigDecimal available() {
            return limit.subtract(used);
        }
    }
}
