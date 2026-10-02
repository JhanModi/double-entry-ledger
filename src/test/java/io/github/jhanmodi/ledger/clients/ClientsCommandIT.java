package io.github.jhanmodi.ledger.clients;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhanmodi.ledger.OwnerDatabase;
import io.github.jhanmodi.ledger.TestcontainersConfiguration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

/**
 * Runs the command the way {@code LedgerApplication} does in command-line mode: in an application context with no web
 * server (WebEnvironment.NONE). A web context would hide startup problems that only exist without a server.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class ClientsCommandIT {

    @Autowired
    ClientsCommand command;

    @Autowired
    ApiKeyVerifier verifier;

    @Autowired
    OwnerDatabase owner;

    @Test
    void createsAClientAndPrintsAWorkingKeyExactlyOnce(CapturedOutput output) {
        command.run(new DefaultApplicationArguments("clients", "create", "--name=acme", "--scopes=read,write"));

        assertThat(command.getExitCode()).isZero();
        String key = Arrays.stream(output.getOut().split("\\R"))
                .filter(line -> line.startsWith("dbl_"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(verifier.verify(key))
                .hasValueSatisfying(
                        client -> assertThat(client.scopes()).containsExactlyInAnyOrder(Scope.READ, Scope.WRITE));
        assertThat(output.getOut()).containsOnlyOnce(key);
    }

    @Test
    void auditsTheNewClientAndItsKeyAsTheOperator(CapturedOutput output) {
        command.run(new DefaultApplicationArguments("clients", "create", "--name=audited", "--scopes=read"));

        Matcher created = Pattern.compile("Created client (\\S+) \\(audited\\)").matcher(output.getOut());
        assertThat(created.find()).as(output.getOut()).isTrue();
        Matcher key = Pattern.compile("^dbl_([0-9a-f]{16})_", Pattern.MULTILINE).matcher(output.getOut());
        assertThat(key.find()).isTrue();

        assertThat(auditedAs(created.group(1))).isEqualTo("CLIENT_CREATED by OPERATOR_CLI");
        assertThat(auditedAs(key.group(1))).isEqualTo("API_KEY_ISSUED by OPERATOR_CLI");
    }

    @Test
    void rejectsMissingOrUnknownOptionsWithUsage(CapturedOutput output) {
        command.run(new DefaultApplicationArguments("clients", "create", "--name=acme"));
        assertThat(command.getExitCode()).isEqualTo(1);

        command.run(new DefaultApplicationArguments("clients", "create", "--name=acme", "--scopes=superuser"));
        assertThat(command.getExitCode()).isEqualTo(1);

        assertThat(output.getErr())
                .contains("--scopes is required")
                .contains("unknown scope: superuser")
                .contains("usage:");
    }

    @Test
    void staysQuietWhenTheAppStartsNormally(CapturedOutput output) {
        command.run(new DefaultApplicationArguments());

        assertThat(command.getExitCode()).isZero();
        assertThat(output.getOut()).doesNotContain("API key");
    }

    /** The audit row for this target, as "ACTION by ACTOR_TYPE". Read as the owner: the app can't read the log. */
    private String auditedAs(String targetId) {
        return owner.jdbc()
                .sql("SELECT action || ' by ' || actor_type FROM audit_log WHERE target_id = :target")
                .param("target", targetId)
                .query(String.class)
                .single();
    }
}
