package io.github.jhanmodi.ledger.money;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The CurrencyCode enum and the currencies table must list the same currencies with the same exponents (ADR-0013). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrencyCodeIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void enumMatchesTheCurrenciesTable() {
        Map<String, Integer> inDatabase = jdbc
                .sql("SELECT code, exponent FROM currencies")
                .query((rs, rowNum) -> Map.entry(rs.getString("code"), rs.getInt("exponent")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Map<String, Integer> inCode =
                Arrays.stream(CurrencyCode.values()).collect(Collectors.toMap(Enum::name, CurrencyCode::exponent));

        assertThat(inDatabase).isEqualTo(inCode);
    }
}
