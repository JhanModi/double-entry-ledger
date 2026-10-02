package io.github.jhanmodi.ledger.audit;

/** What an audited actor did. Matches the {@code audit_log_action_known} constraint (V5). */
public enum AuditAction {
    /** A client opened a customer account. The target is the account's id. */
    ACCOUNT_OPENED,
    /** A client moved money between two of its accounts. The target is the transfer's id. */
    TRANSFER_CREATED,
    /** A client funded one of its accounts. The target is the funding's id. */
    FUNDING_CREATED,
    /** The operator created an API client. The target is the client's id. */
    CLIENT_CREATED,
    /** The operator issued an API key. The target is the key's public key id, never its secret. */
    API_KEY_ISSUED
}
