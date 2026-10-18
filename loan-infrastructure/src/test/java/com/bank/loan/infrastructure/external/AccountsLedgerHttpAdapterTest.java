package com.bank.loan.infrastructure.external;

import com.bank.loan.domain.port.out.AccountsLedgerUnavailableException;
import com.bank.loan.domain.port.out.JournalEntry;
import com.bank.loan.domain.port.out.JournalEntry.Line;
import com.bank.loan.domain.port.out.JournalEntry.Side;
import com.bank.loan.domain.port.out.JournalEntryReceipt;
import com.bank.loan.domain.port.out.JournalEntryReferenceReusedException;
import com.bank.loan.domain.port.out.JournalEntryRejectedException;
import com.bank.loan.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountsLedgerHttpAdapterTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final String JOURNAL = "http://ledger/api/v1/journal-entries";
    private static final JournalEntry ENTRY = new JournalEntry("LOAN-HTTP-7", AED, "disbursement LOAN-HTTP-7", List.of(
        new Line("ACC-CLEARING-1", Side.DEBIT, Money.of("1250.00", AED)),
        new Line("ACC-CUSTOMER-1", Side.CREDIT, Money.of("1250.00", AED))));
    private static final String EXPECTED_BODY = """
        {"reference":"LOAN-HTTP-7","currency":"AED","narrative":"disbursement LOAN-HTTP-7","lines":[
          {"accountId":"ACC-CLEARING-1","side":"DEBIT","amount":1250.00},
          {"accountId":"ACC-CUSTOMER-1","side":"CREDIT","amount":1250.00}]}
        """;
    private static final String BOOKED = """
        {"journalEntryId":"JE-42","reference":"LOAN-HTTP-7","currency":"AED","lines":[],
         "narrative":"disbursement LOAN-HTTP-7","bookedAt":"2026-10-17T09:00:00Z","replay":false}
        """;
    /** The ledger's error body: code, message (free text, may describe balances), interactionId, timestamp. */
    private static final String REFUSED = """
        {"code":"%s","message":"account ACC-CUSTOMER-1 has 12.34 available","interactionId":"i-1",
         "timestamp":"2026-10-17T09:00:00Z"}
        """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AccountsLedgerHttpAdapter adapter = new AccountsLedgerHttpAdapter(builder.build(), () -> "service-token", 3);

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void aBookingIsPostedWithTheServiceTokenTheInteractionIdAndNoIdempotencyHeader() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-journal");
        server.expect(requestTo(JOURNAL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer service-token"))
            .andExpect(header("x-fapi-interaction-id", "corr-journal"))
            .andExpect(headerDoesNotExist("x-idempotency-key"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json(EXPECTED_BODY, true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BOOKED));

        JournalEntryReceipt receipt = adapter.post(ENTRY);

        assertThat(receipt.journalEntryId()).isEqualTo("JE-42");
        assertThat(receipt.replay()).isFalse();
        assertThat(receipt.bookedAt()).isEqualTo(Instant.parse("2026-10-17T09:00:00Z"));
        server.verify();
    }

    @Test
    void aNullNarrativeIsLeftOutOfTheBody() {
        JournalEntry bare = new JournalEntry("LOAN-HTTP-8", AED, null, ENTRY.lines());
        server.expect(requestTo(JOURNAL))
            .andExpect(content().json("{\"reference\":\"LOAN-HTTP-8\",\"currency\":\"AED\",\"lines\":["
                + "{\"accountId\":\"ACC-CLEARING-1\",\"side\":\"DEBIT\",\"amount\":1250.00},"
                + "{\"accountId\":\"ACC-CUSTOMER-1\",\"side\":\"CREDIT\",\"amount\":1250.00}]}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BOOKED.replace("LOAN-HTTP-7", "LOAN-HTTP-8")));

        adapter.post(bare);
        server.verify();
    }

    @Test
    void theSameReferenceAgainIsAReplayOfTheFirstBooking() {
        server.expect(requestTo(JOURNAL))
            .andRespond(withSuccess(BOOKED.replace("\"replay\":false", "\"replay\":true"), MediaType.APPLICATION_JSON));

        JournalEntryReceipt receipt = adapter.post(ENTRY);

        assertThat(receipt.journalEntryId()).isEqualTo("JE-42");
        assertThat(receipt.replay()).isTrue();
    }

    @Test
    void concurrentUpdateAndDuplicateRequestAreRetriedWithTheSameReference() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("CONCURRENT_UPDATE")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("DUPLICATE_REQUEST")));
        server.expect(requestTo(JOURNAL))
            .andExpect(content().json(EXPECTED_BODY, true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BOOKED));

        assertThat(adapter.post(ENTRY).journalEntryId()).isEqualTo("JE-42");
        server.verify();
    }

    @Test
    void retriesAreBoundedAndThenTheLedgerIsUnavailable() {
        server.expect(ExpectedCount.times(3), requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("CONCURRENT_UPDATE")));

        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("kept reporting CONCURRENT_UPDATE")
            .message().doesNotContain("LOAN-HTTP-7", "ACC-", "1250", "12.34");
        server.verify();
    }

    @Test
    void refusalsAreRejectionsThatCarryOnlyTheCode() {
        for (String code : List.of("INSUFFICIENT_FUNDS", "ACCOUNT_NOT_OPERATIONAL", "UNBALANCED_ENTRY")) {
            server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted(code)));
        }
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("IDEMPOTENCY_KEY_REUSED")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("VALIDATION_ERROR")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("ACCOUNT_NOT_FOUND")));

        for (String code : List.of("INSUFFICIENT_FUNDS", "ACCOUNT_NOT_OPERATIONAL", "UNBALANCED_ENTRY")) {
            assertThatThrownBy(() -> adapter.post(ENTRY))
                .isInstanceOf(JournalEntryRejectedException.class)
                .isNotInstanceOf(AccountsLedgerUnavailableException.class)
                .satisfies(rejected -> assertThat(((JournalEntryRejectedException) rejected).getCode()).isEqualTo(code))
                .hasMessageContaining("422 " + code)
                .message().doesNotContain("LOAN-HTTP-7", "ACC-", "1250", "12.34", "available");
        }
        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(JournalEntryReferenceReusedException.class)
            .hasMessageContaining("409 IDEMPOTENCY_KEY_REUSED")
            .message().doesNotContain("LOAN-HTTP-7", "ACC-", "12.34");
        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(JournalEntryRejectedException.class)
            .hasMessageContaining("400 VALIDATION_ERROR");
        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(JournalEntryRejectedException.class)
            .hasMessageContaining("404 ACCOUNT_NOT_FOUND");
        server.verify();
    }

    @Test
    void outagesRejectedTokensAndUnknownAnswersAreUnavailableNeverASuccess() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("FORBIDDEN")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("SOMETHING_NEW")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("OTHER_CONFLICT")));
        server.expect(requestTo(JOURNAL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("503");
        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("403").message().doesNotContain("12.34");
        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("401");
        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("422 SOMETHING_NEW");
        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessageContaining("409 OTHER_CONFLICT");
        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        server.verify();
    }

    /** A 2xx without a booking id is no proof the entry was booked: fail closed. */
    @Test
    void aSuccessWithoutABookingIsUnavailable() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON).body("{\"reference\":\"LOAN-HTTP-7\",\"replay\":false}"));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON).body("{\"journalEntryId\":\"JE-42\",\"replay\":false}"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> adapter.post(ENTRY))
                .isInstanceOf(AccountsLedgerUnavailableException.class)
                .hasMessageContaining("no usable booking");
        }
        server.verify();
    }

    @Test
    void missingOrFailingServiceTokenIsUnavailableAndNothingIsSent() {
        AccountsLedgerHttpAdapter noToken = new AccountsLedgerHttpAdapter(builder.build(), () -> " ", 1);
        AccountsLedgerHttpAdapter failingToken = new AccountsLedgerHttpAdapter(builder.build(),
            () -> { throw new IllegalStateException("No service token for client registration accounts-ledger"); }, 1);

        assertThatThrownBy(() -> noToken.post(ENTRY))
            .isInstanceOf(AccountsLedgerUnavailableException.class).hasMessageContaining("No service token");
        assertThatThrownBy(() -> failingToken.post(ENTRY))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void interactionIdIsGeneratedWhenTheThreadHasNoneAndThePathIsConfigurable() {
        AccountsLedgerHttpAdapter renamed = new AccountsLedgerHttpAdapter(builder.build(), () -> "t", 1, "/v2/journal");
        server.expect(requestTo("http://ledger/v2/journal"))
            .andExpect(header("x-fapi-interaction-id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BOOKED));

        assertThat(renamed.post(ENTRY).journalEntryId()).isEqualTo("JE-42");
        server.verify();
    }

    @Test
    void aBookingForAnotherReferenceIsUnavailable() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
            .body(BOOKED.replace("\"reference\":\"LOAN-HTTP-7\"", "\"reference\":\"LOAN-OTHER-9\"")));

        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasNoCause()
            .message().doesNotContain("LOAN-HTTP-7", "LOAN-OTHER-9");
    }

    @Test
    void anAnswerInAContentTypeTheAdapterCannotReadIsUnavailableWithNoCause() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED)
            .contentType(MediaType.TEXT_PLAIN).body("account ACC-CUSTOMER-1 has 12.34 available"));

        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasNoCause()
            .message().doesNotContain("ACC-CUSTOMER-1", "12.34");
    }

    @Test
    void aLedgerErrorBodyIsNotKeptAsACause() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("INSUFFICIENT_FUNDS")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(JournalEntryRejectedException.class).hasNoCause();
        assertThatThrownBy(() -> adapter.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class).hasNoCause();
    }

    @Test
    void retriesWaitBetweenAttempts() {
        AccountsLedgerHttpAdapter paced = new AccountsLedgerHttpAdapter(builder.build(), () -> "service-token", 2,
            "/api/v1/journal-entries", Duration.ofMillis(50));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("CONCURRENT_UPDATE")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON).body(BOOKED));

        long startedAt = System.nanoTime();
        paced.post(ENTRY);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(45));
    }

    @Test
    void theWaitDoublesWithEachRetryAndNeverPassesTheCap() {
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(100), 1)).isEqualTo(Duration.ofMillis(100));
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(100), 2)).isEqualTo(Duration.ofMillis(200));
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(100), 3)).isEqualTo(Duration.ofMillis(400));
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofSeconds(1), 4))
            .isEqualTo(AccountsLedgerHttpAdapter.MAX_RETRY_WAIT);
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(100), 1_000))
            .isEqualTo(AccountsLedgerHttpAdapter.MAX_RETRY_WAIT);
    }

    @Test
    void theRetryCountIsCappedAtTheCeilingWhateverTheConfiguration() {
        AccountsLedgerHttpAdapter unbounded = new AccountsLedgerHttpAdapter(builder.build(), () -> "service-token", 1_000);
        server.expect(ExpectedCount.times(AccountsLedgerHttpAdapter.MAX_ATTEMPTS_CEILING), requestTo(JOURNAL))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(REFUSED.formatted("CONCURRENT_UPDATE")));

        assertThatThrownBy(() -> unbounded.post(ENTRY)).isInstanceOf(AccountsLedgerUnavailableException.class);
        server.verify();
    }

    @Test
    void aWaitIsSafeForAnAttemptBelowOneAndForANegativeBackoff() {
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(100), 0)).isEqualTo(Duration.ofMillis(100));
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(100), Integer.MIN_VALUE))
            .isEqualTo(Duration.ofMillis(100));
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofMillis(-5), 1)).isEqualTo(Duration.ZERO);
        assertThat(AccountsLedgerHttpAdapter.waitBeforeRetry(Duration.ofHours(1), 0))
            .isEqualTo(AccountsLedgerHttpAdapter.MAX_RETRY_WAIT);
    }

    @Test
    void aNegativeBackoffIsWaitedAsZeroWhenTheAdapterIsBuilt() {
        assertThat(waitsBeforeOneRetry(Duration.ofMillis(-200))).containsExactly(Duration.ZERO);
    }

    @Test
    void anOversizedBackoffIsWaitedAsTheCapWhenTheAdapterIsBuilt() {
        assertThat(waitsBeforeOneRetry(Duration.ofHours(1))).containsExactly(AccountsLedgerHttpAdapter.MAX_RETRY_WAIT);
    }

    @Test
    void aModestBackoffIsWaitedAsConfiguredWhenTheAdapterIsBuilt() {
        assertThat(waitsBeforeOneRetry(Duration.ofMillis(200))).containsExactly(Duration.ofMillis(200));
    }

    @Test
    void aMalformedAnswerIsUnavailableWithNoCause() {
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON).body("{\"journalEntryId\": ACC-CUSTOMER-1 12.34}"));

        assertThatThrownBy(() -> adapter.post(ENTRY))
            .isInstanceOf(AccountsLedgerUnavailableException.class)
            .hasMessage("Accounts ledger answered with an unreadable journal entry")
            .hasNoCause()
            .message().doesNotContain("ACC-CUSTOMER-1", "12.34");
    }

    /** The waits the adapter takes before its one retry, read through the pause seam instead of sleeping. */
    private List<Duration> waitsBeforeOneRetry(Duration retryBackoff) {
        List<Duration> waits = new ArrayList<>();
        AccountsLedgerHttpAdapter paused = new AccountsLedgerHttpAdapter(builder.build(), () -> "service-token", 2,
            AccountsLedgerHttpAdapter.DEFAULT_PATH, retryBackoff, waits::add);
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON).body(REFUSED.formatted("CONCURRENT_UPDATE")));
        server.expect(requestTo(JOURNAL)).andRespond(withStatus(HttpStatus.CREATED)
            .contentType(MediaType.APPLICATION_JSON).body(BOOKED));

        paused.post(ENTRY);
        return waits;
    }
}
