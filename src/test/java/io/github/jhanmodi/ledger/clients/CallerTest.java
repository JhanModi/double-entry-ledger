package io.github.jhanmodi.ledger.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jhanmodi.ledger.audit.Actor;
import io.github.jhanmodi.ledger.audit.RequestId;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CallerTest {

    private static final ClientId CLIENT = new ClientId(UUID.randomUUID());
    private static final RequestId REQUEST = new RequestId(UUID.randomUUID());

    @Test
    void requireScopePassesOnlyForAScopeTheKeyHas() {
        Caller writer = caller(EnumSet.of(Scope.READ, Scope.WRITE));

        assertThatCode(() -> writer.requireScope(Scope.WRITE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> writer.requireScope(Scope.ADMIN))
                .isInstanceOf(ScopeRequiredException.class)
                .hasMessage("this operation needs a key with the admin scope");
    }

    @Test
    void asAnAuditActorItIsTheKeyInThisRequestFromThisAddress() {
        Caller caller = caller(EnumSet.of(Scope.WRITE));

        assertThat(caller.auditActor())
                .isEqualTo(new Actor.ApiKey(CLIENT.value(), "0123456789abcdef", REQUEST, "203.0.113.5"));
    }

    private static Caller caller(EnumSet<Scope> scopes) {
        return new Caller(new AuthenticatedClient(CLIENT, "0123456789abcdef", scopes), REQUEST, "203.0.113.5");
    }
}
