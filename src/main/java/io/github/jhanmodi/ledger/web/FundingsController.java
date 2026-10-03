package io.github.jhanmodi.ledger.web;

import io.github.jhanmodi.ledger.clients.AuthenticatedClient;
import io.github.jhanmodi.ledger.idempotency.IdempotencyKey;
import io.github.jhanmodi.ledger.idempotency.IdempotentResult;
import io.github.jhanmodi.ledger.transfers.Funding;
import io.github.jhanmodi.ledger.transfers.FundingService;
import io.github.jhanmodi.ledger.web.ApiJson.FundingRequest;
import io.github.jhanmodi.ledger.web.ApiJson.FundingResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Funding one of the calling client's own accounts, with an {@code admin} key (ADR-0018, D1-A). It stands in for an
 * inbound bank deposit until M9. There's no endpoint to read a funding back yet; its effect shows in the account's
 * balance and history, and a retry with the same {@code Idempotency-Key} and body gets the original response again
 * (ADR-0023).
 */
@RestController
@RequestMapping("/v1/fundings")
class FundingsController {

    private final FundingService fundings;

    FundingsController(FundingService fundings) {
        this.fundings = fundings;
    }

    @PostMapping
    ResponseEntity<FundingResponse> create(
            @AuthenticationPrincipal AuthenticatedClient client,
            HttpServletRequest http,
            @RequestHeader(IdempotencyKeyHeader.NAME) @Pattern(regexp = IdempotencyKey.FORMAT_REGEX)
                    String idempotencyKey,
            @Valid @RequestBody FundingRequest request) {
        IdempotentResult<Funding> result =
                fundings.fund(request.toCommand(new IdempotencyKey(idempotencyKey)), Callers.of(client, http));
        return IdempotencyKeyHeader.markIfReplayed(ResponseEntity.status(HttpStatus.CREATED), result)
                .body(FundingResponse.of(result.value()));
    }
}
