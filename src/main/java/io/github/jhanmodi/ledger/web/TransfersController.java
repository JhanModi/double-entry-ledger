package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.AuthenticatedClient;
import io.github.jhanmodi.ledger.transfers.IdempotencyKey;
import io.github.jhanmodi.ledger.transfers.Transfer;
import io.github.jhanmodi.ledger.transfers.TransferId;
import io.github.jhanmodi.ledger.transfers.TransferService;
import io.github.jhanmodi.ledger.web.ApiJson.TransferRequest;
import io.github.jhanmodi.ledger.web.ApiJson.TransferResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfers between two accounts of the calling client (ADR-0018). Both accounts are looked up owner-scoped, so another
 * client's account answers 404 exactly like a missing one.
 *
 * <p>Not safe to retry in the full sense until M6: a retry with the same {@code Idempotency-Key} never moves money
 * twice, but it gets a 409 naming the original transfer, not the original response (ADR-0019).
 */
@RestController
@RequestMapping("/v1/transfers")
class TransfersController {

    private final TransferService transfers;

    TransfersController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    ResponseEntity<TransferResponse> create(
            @AuthenticationPrincipal AuthenticatedClient client,
            HttpServletRequest http,
            @RequestHeader(IdempotencyKeyHeader.NAME) @Pattern(regexp = IdempotencyKey.FORMAT_REGEX)
                    String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        Transfer transfer =
                transfers.transfer(request.toCommand(new IdempotencyKey(idempotencyKey)), Callers.of(client, http));
        return ResponseEntity.created(URI.create("/v1/transfers/" + transfer.id()))
                .body(TransferResponse.of(transfer));
    }

    @GetMapping("/{transferId}")
    TransferResponse get(@AuthenticationPrincipal AuthenticatedClient client, @PathVariable UUID transferId) {
        return TransferResponse.of(transfers.find(client.clientId(), new TransferId(transferId)));
    }
}
