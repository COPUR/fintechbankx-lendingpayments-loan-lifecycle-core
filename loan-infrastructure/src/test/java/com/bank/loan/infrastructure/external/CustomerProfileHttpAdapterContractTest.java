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
 * Consumer contract: what {@link CustomerProfileHttpAdapter} sends and reads
 * must exist in the customer service's OpenAPI (customer-context.yaml, copied
 * into test resources from the customer PR head; the file header names the
 * commit). Fails when the provider renames a path, a body field or an error
 * status this service relies on.
 */
class CustomerProfileHttpAdapterContractTest {

    private static Map<String, Object> spec;

    @BeforeAll
    static void loadSpec() throws Exception {
        try (InputStream in = CustomerProfileHttpAdapterContractTest.class.getResourceAsStream("/contracts/customer-context.yaml")) {
            spec = new Yaml().load(in);
        }
    }

    @Test
    void creditPositionIsAGetWhoseResponseCarriesEveryFieldTheAdapterReads() {
        Map<String, Object> get = operation(CustomerProfileHttpAdapter.Paths.DEFAULT.creditPosition(), "get");

        Map<String, Object> schema = responseSchema(get, "200");
        assertThat(properties(schema)).containsAll(recordFields(CustomerProfileHttpAdapter.CreditPosition.class));
        assertThat(required(schema)).contains("currency", "availableCredit");
        assertThat(responses(get)).containsKeys("200", "403", "404");
    }

    @Test
    void reserveAndReleaseTakeTheBodyAndKeyTheAdapterSends() {
        for (String path : List.of(CustomerProfileHttpAdapter.Paths.DEFAULT.reserve(),
                CustomerProfileHttpAdapter.Paths.DEFAULT.release())) {
            Map<String, Object> post = operation(path, "post");

            Map<String, Object> body = resolve(at(post, "requestBody", "content", "application/json", "schema"));
            Set<String> sent = recordFields(CustomerProfileHttpAdapter.CreditMovementRequest.class);
            assertThat(properties(body)).as(path + " request body").containsAll(sent);
            assertThat(sent).as(path + " required fields").containsAll(required(body));

            assertThat(headerParameters(post)).as(path + " headers")
                .contains(CustomerProfileHttpAdapter.IDEMPOTENCY_KEY_HEADER, CustomerProfileHttpAdapter.INTERACTION_ID_HEADER);

            // 200 is the credit position (CustomerCreditResponse), parsed into the same record as GET .../credit
            Map<String, Object> answer = responseSchema(post, "200");
            assertThat(properties(answer)).as(path + " 200")
                .containsAll(recordFields(CustomerProfileHttpAdapter.CreditPosition.class));
            assertThat(required(answer)).as(path + " 200 required").contains("currency", "availableCredit");
            assertThat(responses(post)).as(path + " statuses the adapter maps").containsKeys("400", "403", "404", "409");
        }
        assertThat(responses(operation(CustomerProfileHttpAdapter.Paths.DEFAULT.reserve(), "post")))
            .as("422 INSUFFICIENT_CREDIT on reserve").containsKey("422");
    }

    @Test
    void errorsCarryTheCodeTheAdapterBranchesOn() {
        Map<String, Object> error = resolve(Map.of("$ref", "#/components/schemas/ErrorResponse"));
        assertThat(required(error)).contains("code");
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
