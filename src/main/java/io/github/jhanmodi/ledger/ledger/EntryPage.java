package io.github.jhanmodi.ledger.ledger;

import java.util.List;
import java.util.Optional;

/**
 * One page of an account's history, newest first. Pass {@code nextCursor} back to get the following page; it's empty
 * on the last page.
 */
public record EntryPage(List<EntryView> entries, Optional<EntryId> nextCursor) {

    public EntryPage {
        entries = List.copyOf(entries);
    }
}
