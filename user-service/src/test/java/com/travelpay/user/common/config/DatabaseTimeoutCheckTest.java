package com.travelpay.user.common.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTimeoutCheckTest {

    @Test
    void transactionLimitsShorterThanTheSocketReadLimitStart() {
        assertDoesNotThrow(() -> check("7", 5, 6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void aSocketTimeoutWithoutAReadBoundStopsStartup(String seconds) {
        var error = assertThrows(IllegalStateException.class, () -> check(seconds, 5, 5));

        assertTrue(error.getMessage().contains("socketTimeout"));
        assertTrue(error.getMessage().contains("상한이 없"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void anExplicitCancelSignalTimeoutWithoutABoundStopsStartup(String seconds) {
        var environment = defaults().withProperty(
                "spring.datasource.hikari.data-source-properties.cancelSignalTimeout", seconds);

        var error = assertThrows(IllegalStateException.class, () -> check(environment));

        assertTrue(error.getMessage().contains("cancelSignalTimeout"));
        assertTrue(error.getMessage().contains("상한"));
    }

    // 같거나 길면 소켓 읽기 상한보다 먼저 SQL 취소를 시도할 여유가 없으므로 시작하지 않음
    @ParameterizedTest
    @CsvSource({"7,5,password-change", "5,7,admin", "10,5,password-change", "5,10,admin"})
    void aTransactionLimitNotShorterThanTheSocketReadLimitStopsStartup(int passwordSeconds, int adminSeconds,
            String key) {
        var error = assertThrows(IllegalStateException.class, () -> check("7", passwordSeconds, adminSeconds));

        assertTrue(error.getMessage().contains(key), error.getMessage());
    }

    @Test
    void withoutASocketReadLimitThereIsNothingToCompare() {
        assertDoesNotThrow(() -> new DatabaseTimeoutCheck(new MockEnvironment(),
                new PasswordChangeProperties(Duration.ofSeconds(30)), new AdminProperties(Duration.ofSeconds(30))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"socketTimeout=30", "cancelSignalTimeout=0", "options=-c%20lock_timeout%3D0",
            "options", "socketTimeout=7&socketTimeout=0", "options=-c+search_path%3Duser_service"})
    void timeoutPolicyCannotBeOverriddenInTheDatasourceUrl(String parameter) {
        var url = "jdbc:postgresql://db.example/user?sslmode=require&currentSchema=user_service&password=url-secret&"
                + parameter;
        var environment = defaults().withProperty("USER_DB_URL", url)
                .withProperty("spring.datasource.url", "${USER_DB_URL}");

        var error = assertThrows(IllegalStateException.class, () -> check(environment));

        assertTrue(error.getMessage().contains(parameter.split("=")[0]));
        assertFalse(error.getMessage().contains("url-secret"));
        assertFalse(error.getMessage().contains("db.example"));
    }

    @Test
    void sslAndSchemaParametersRemainAllowedWithTheDefaultTimeouts() {
        var environment = defaults().withProperty("spring.datasource.url",
                "jdbc:postgresql://db.example/user?sslmode=require&currentSchema=user_service");

        assertDoesNotThrow(() -> check(environment));
    }

    @Test
    void aHikariJdbcUrlOverrideCannotBypassTheTimeoutPolicy() {
        var environment = defaults()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example/user")
                .withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:postgresql://db.example/user?socketTimeout=30");

        var error = assertThrows(IllegalStateException.class, () -> check(environment));

        assertTrue(error.getMessage().contains("socketTimeout"));
    }

    @Test
    void checksTheEffectiveHikariUrlRatherThanAnOverriddenDatasourceUrl() {
        var environment = defaults()
                .withProperty("spring.datasource.url", "jdbc:postgresql://unused.example/user?options=-c+lock_timeout%3D0")
                .withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:postgresql://db.example/user?currentSchema=user_service");

        assertDoesNotThrow(() -> check(environment));
    }

    @Test
    void driverParameterNamesAreCaseSensitiveAndAreNotUrlDecoded() {
        var environment = defaults().withProperty("spring.datasource.url",
                "jdbc:postgresql://db.example/user?SocketTimeout=30&socket%54imeout=30");

        assertDoesNotThrow(() -> check(environment));
    }

    @Test
    void aDatabaseNameWithoutAQueryIsNotTreatedAsUrlParameters() {
        var environment = defaults().withProperty("spring.datasource.url",
                "jdbc:postgresql:catalog&options=part-of-the-database-name");

        assertDoesNotThrow(() -> check(environment));
    }

    @Test
    void valuesContainingPolicyKeyNamesAndServiceParametersAreNotInspected() {
        var environment = defaults().withProperty("spring.datasource.url",
                "jdbc:postgresql://db.example/user?currentSchema=socketTimeout%3D0&service=not-a-real-service");

        assertDoesNotThrow(() -> check(environment));
    }

    private static MockEnvironment defaults() {
        return new MockEnvironment().withProperty(DatabaseTimeoutCheck.SOCKET_TIMEOUT, "7")
                .withProperty("spring.datasource.hikari.data-source-properties.cancelSignalTimeout", "1");
    }

    private static void check(MockEnvironment environment) {
        new DatabaseTimeoutCheck(environment, new PasswordChangeProperties(Duration.ofSeconds(5)),
                new AdminProperties(Duration.ofSeconds(5)));
    }

    private static void check(String socketTimeout, int passwordSeconds, int adminSeconds) {
        new DatabaseTimeoutCheck(new MockEnvironment().withProperty(DatabaseTimeoutCheck.SOCKET_TIMEOUT, socketTimeout),
                new PasswordChangeProperties(Duration.ofSeconds(passwordSeconds)),
                new AdminProperties(Duration.ofSeconds(adminSeconds)));
    }
}
