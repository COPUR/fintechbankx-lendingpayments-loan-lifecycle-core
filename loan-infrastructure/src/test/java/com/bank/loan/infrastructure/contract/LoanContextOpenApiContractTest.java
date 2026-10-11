package com.bank.loan.infrastructure.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanContextOpenApiContractTest {

    @Test
    void shouldDefineImplementedLoanEndpoints() throws IOException {
        String spec = loadSpec();

        assertThat(spec).doesNotContain("paths: {}");
        assertThat(spec).contains("\n  /api/v1/loans:\n");
        assertThat(spec).contains("\n  /api/v1/loans/{loanId}:\n");
        assertThat(spec).contains("\n  /api/v1/loans/{loanId}/approve:\n");
        assertThat(spec).contains("\n  /api/v1/loans/{loanId}/reject:\n");
        assertThat(spec).contains("\n  /api/v1/loans/{loanId}/disburse:\n");
        assertThat(spec).contains("\n  /api/v1/loans/{loanId}/payments:\n");
        assertThat(spec).contains("\n  /api/v1/loans/{loanId}/cancel:\n");
    }

    @Test
    void shouldRequireDpopForProtectedOperations() throws IOException {
        String spec = loadSpec();

        assertThat(spec).contains("name: DPoP");
        assertThat(spec).contains("required: true");
        assertThat(spec).contains("/api/v1/loans:");
        assertThat(spec).contains("security:");
    }

    /**
     * Review minor: a disbursement of a loan whose reservation is held for an
     * operator is a 409 with its own code and an example, listed with the
     * stable codes; the 503 stays for a customer service that did not answer.
     */
    @Test
    void disburseDocumentsTheOperatorHoldAsA409() throws IOException {
        String spec = loadSpec();
        String disburse = spec.substring(spec.indexOf("\n  /api/v1/loans/{loanId}/disburse:\n"),
            spec.indexOf("\n  /api/v1/loans/{loanId}/payments:\n"));
        String conflict = disburse.substring(disburse.indexOf("'409':"), disburse.indexOf("'422':"));
        String codes = spec.substring(spec.indexOf("    ErrorResponse:"));

        assertThat(conflict).contains("CREDIT_RESERVATION_HELD_FOR_OPERATOR")
            .contains("code: CREDIT_RESERVATION_HELD_FOR_OPERATOR");
        assertThat(codes.substring(0, codes.indexOf("message:"))).contains("CREDIT_RESERVATION_HELD_FOR_OPERATOR");
    }

    private static String loadSpec() throws IOException {
        List<Path> candidates = List.of(
                Path.of("api/openapi/loan-context.yaml"),
                Path.of("../api/openapi/loan-context.yaml"),
                Path.of("../../api/openapi/loan-context.yaml"),
                Path.of("../../../api/openapi/loan-context.yaml")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }

        throw new IOException("Unable to locate loan-context.yaml");
    }
}
