package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.AuthenticatedClient;
import io.github.jhanmodi.ledger.ledger.Account;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.ledger.AccountService;
import io.github.jhanmodi.ledger.ledger.EntryId;
import io.github.jhanmodi.ledger.ledger.LedgerQueries;
import io.github.jhanmodi.ledger.web.ApiJson.AccountResponse;
import io.github.jhanmodi.ledger.web.ApiJson.EntryPageJson;
import io.github.jhanmodi.ledger.web.ApiJson.OpenAccountRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer accounts, as seen by the client that owns them. Every lookup goes through
 * {@link LedgerQueries#accountOwnedBy}, so another client's account answers 404, exactly like one that doesn't exist.
 */
@RestController
@RequestMapping("/v1/accounts")
class AccountsController {

    private final AccountService accounts;
    private final LedgerQueries queries;

    AccountsController(AccountService accounts, LedgerQueries queries) {
        this.accounts = accounts;
        this.queries = queries;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(
            @AuthenticationPrincipal AuthenticatedClient client, @Valid @RequestBody OpenAccountRequest request) {
        // The owner always comes from the API key, never from the request body.
        AccountId id = accounts.openCustomerAccount(client.clientId(), request.currency());
        return ResponseEntity.created(URI.create("/v1/accounts/" + id)).body(describe(client, id));
    }

    @GetMapping("/{accountId}")
    AccountResponse get(@AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID accountId) {
        return describe(client, new AccountId(accountId));
    }

    @GetMapping("/{accountId}/entries")
    EntryPageJson entries(
            @AuthenticationPrincipal AuthenticatedClient client,
            @PathVariable UUID accountId,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        Account account = queries.accountOwnedBy(client.clientId(), new AccountId(accountId));
        return EntryPageJson.of(
                queries.history(account.id(), Optional.ofNullable(cursor).map(EntryId::new), limit));
    }

    private AccountResponse describe(AuthenticatedClient client, AccountId id) {
        Account account = queries.accountOwnedBy(client.clientId(), id);
        return AccountResponse.of(account, queries.balance(account.id()));
    }
}
