package com.bank.loan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTestDatabaseTest {

    @Test
    void ciWithoutADatabaseFailsInsteadOfSkipping() {
        assertThatThrownBy(() -> PostgresTestDatabase.requireInCi("true", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TEST_DB_URL");
        assertThatThrownBy(() -> PostgresTestDatabase.requireInCi("TRUE", " ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ciWithADatabaseAndLocalRunsPass() {
        assertThatCode(() -> PostgresTestDatabase.requireInCi("true", "jdbc:postgresql://db:5432/test")).doesNotThrowAnyException();
        assertThatCode(() -> PostgresTestDatabase.requireInCi(null, null)).doesNotThrowAnyException();
    }
}
