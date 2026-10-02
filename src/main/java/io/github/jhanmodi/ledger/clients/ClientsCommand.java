package io.github.jhanmodi.ledger.clients;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Command-line mode that creates a client and its first API key, then exits (ADR-0016):
 *
 * <pre>clients create --name=acme --scopes=read,write</pre>
 *
 * There's deliberately no HTTP endpoint for this. That leaves no network surface to attack and no bootstrap secret to
 * guard. Whoever can run the application with database access can create clients; nobody else can.
 */
@Component
public class ClientsCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final String USAGE =
            "usage: clients create --name=<name> --scopes=<comma-separated: read,write,admin>";

    private final ClientService clients;
    private int exitCode;

    public ClientsCommand(ClientService clients) {
        this.clients = clients;
    }

    /** Whether these program arguments ask for this command, in which case the app runs it instead of serving HTTP. */
    public static boolean isInvokedBy(String... args) {
        return args.length >= 2 && "clients".equals(args[0]) && "create".equals(args[1]);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isInvokedBy(args.getSourceArgs())) {
            return;
        }
        try {
            String name = singleOption(args, "name");
            Set<Scope> scopes = parseScopes(singleOption(args, "scopes"));
            ClientId client = clients.createClient(name);
            IssuedApiKey key = clients.issueKey(client, scopes);
            System.out.println("Created client " + client + " (" + name + ") with scopes " + scopes);
            System.out.println("API key, shown only this once. Store it somewhere safe now:");
            System.out.println(key.plaintext());
            exitCode = 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static String singleOption(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required, exactly once");
        }
        return values.getFirst();
    }

    private static Set<Scope> parseScopes(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .map(Scope::fromValue)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Scope.class)));
    }
}
