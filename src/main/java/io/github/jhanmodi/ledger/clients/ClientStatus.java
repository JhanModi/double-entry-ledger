package io.github.jhanmodi.ledger.clients;

/** Whether a client may use the API. A disabled client's keys stop working immediately. */
public enum ClientStatus {
    ACTIVE,
    DISABLED
}
