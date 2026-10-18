package com.bank.loan.infrastructure.external;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer contract: what {@link AccountsLedgerHttpAdapter} sends and reads
 * must exist in the accounts ledger's OpenAPI (accounts-ledger.yaml, copied
 * into test resources from the ledger PR head; the file header names the
 * commit). Fails when the provider renames the path, a body field or an
 * error status this service relies on.
 */
class AccountsLedgerHttpAdapterContractTest {

    private static Map<String, Object> spec;

    @BeforeAll
    static void loadSpec() throws Exception {
        try (InputStream in = AccountsLedgerHttpAdapterContractTest.class.getResourceAsStream("/contracts/accounts-ledger.yaml")) {
            spec = new Yaml().load(in);
        }
    }

    @Test
    void journalEntriesTakeTheBodyTheAdapterSendsAndNoIdempotencyHeader() {
        Map<String, Object> post = operation(AccountsLedgerHttpAdapter.DEFAULT_PATH, "post");

        Map<String, Object> body = resolve(at(post, "requestBody", "content", "application/json", "schema"));
        Set<String> sent = recordFields(AccountsLedgerHttpAdapter.JournalEntryRequest.class);
        assertThat(properties(body)).as("request body").containsExactlyInAnyOrderElementsOf(sent);
        assertThat(sent).as("required fields").containsAll(required(body));
        assertThat(body.get("additionalProperties")).isEqualTo(false);

        Map<String, Object> line = resolve(map(map(map(body.get("properties")).get("lines")).get("items")));
        assertThat(properties(line)).containsExactlyInAnyOrderElementsOf(recordFields(AccountsLedgerHttpAdapter.PostingLine.class));
        assertThat(map(map(line.get("properties")).get("side")).get("enum")).isEqualTo(List.of("DEBIT", "CREDIT"));

        // The reference in the body is the idempotency key; the header belongs to POST /accounts only.
        assertThat(headerParameters(post)).contains(AccountsLedgerHttpAdapter.INTERACTION_ID_HEADER)
            .doesNotContain("x-idempotency-key");
    }

    @Test
    void bookedAndReplayedAnswersCarryWhatTheAdapterReads() {
        Map<String, Object> post = operation(AccountsLedgerHttpAdapter.DEFAULT_PATH, "post");

        for (String status : List.of("201", "200")) {
            Map<String, Object> answer = responseSchema(post, status);
            assertThat(properties(answer)).as(status).containsAll(recordFields(AccountsLedgerHttpAdapter.JournalEntryResponse.class));
            assertThat(required(answer)).as(status + " required").contains("journalEntryId", "bookedAt", "replay");
        }
        assertThat(responses(post)).as("statuses the adapter maps").containsKeys("400", "401", "403", "404", "409", "422");
    }

    @Test
    void refusalsAreTheCodesTheAdapterBranchesOn() {
        Map<String, Object> post = operation(AccountsLedgerHttpAdapter.DEFAULT_PATH, "post");
        String description = String.valueOf(post.get("description")).replaceAll("\\s+", " ");

        assertThat(description).contains("422 INSUFFICIENT_FUNDS").contains("422 ACCOUNT_NOT_OPERATIONAL")
            .contains("same reference with the same lines answers 200").contains("with other lines it is a 409");
        Map<String, Object> error = resolve(Map.of("$ref", "#/components/schemas/ErrorResponse"));
        assertThat(required(error)).contains("code", "message");
    }

    // --- spec navigation -----------------------------------------------------

    private static Map<String, Object> operation(String path, String method) {
        Map<String, Object> paths = map(spec.get("paths"));
        assertThat(paths).as("provider paths").containsKey(path);
        Map<String, Object> item = map(paths.get(path));
        assertThat(item).as(path).containsKey(method);
        return map(item.get(method));
    }

    private static Map<String, Object> responses(Map<String, Object> operation) {
        return map(operation.get("responses")).entrySet().stream()
            .collect(Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue));
    }

    private static Map<String, Object> responseSchema(Map<String, Object> operation, String status) {
        return resolve(at(map(responses(operation).get(status)), "content", "application/json", "schema"));
    }

    private static Set<String> headerParameters(Map<String, Object> operation) {
        return ((List<?>) operation.get("parameters")).stream()
            .map(p -> resolve(map(p)))
            .filter(p -> "header".equals(p.get("in")))
            .map(p -> String.valueOf(p.get("name")).toLowerCase())
            .collect(Collectors.toSet());
    }

    private static Set<String> properties(Map<String, Object> schema) {
        return map(schema.get("properties")).keySet();
    }

    private static List<String> required(Map<String, Object> schema) {
        Object required = schema.get("required");
        return required == null ? List.of() : ((List<?>) required).stream().map(String::valueOf).toList();
    }

    private static Set<String> recordFields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
    }

    private static Map<String, Object> at(Map<String, Object> node, String... keys) {
        Map<String, Object> current = node;
        for (String key : keys) {
            current = map(current.get(key));
        }
        return current;
    }

    private static Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref == null) {
            return node;
        }
        Map<String, Object> current = spec;
        for (String part : String.valueOf(ref).substring(2).split("/")) {
            current = map(current.get(part));
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
