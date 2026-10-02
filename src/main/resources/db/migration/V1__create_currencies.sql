-- Currencies the ledger supports, with their ISO 4217 exponent (number of decimal places in the minor unit).
-- Accounts reference this table from M3 onward. See ADR-0002.
--
-- TEXT with a CHECK instead of CHAR(3): Postgres pads CHAR values with spaces, which makes comparisons surprising.
CREATE TABLE currencies (
    code     TEXT     PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
    exponent SMALLINT NOT NULL CHECK (exponent BETWEEN 0 AND 4)
);

INSERT INTO currencies (code, exponent) VALUES
    ('USD', 2),
    ('EUR', 2),
    ('JPY', 0),
    ('KWD', 3);
