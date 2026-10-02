package io.github.jhanmodi.ledger;

import io.github.jhanmodi.ledger.clients.ClientsCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Callers authenticate only with API keys (ADR-0016). Without this exclusion, Spring Boot would also create a default
// username-and-password user and print its generated password to the log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(LedgerApplication.class);
        if (ClientsCommand.isInvokedBy(args)) {
            // A one-off command: start without a web server, run it, and exit with its exit code.
            application.setWebApplicationType(WebApplicationType.NONE);
            System.exit(SpringApplication.exit(application.run(args)));
        }
        application.run(args);
    }
}
