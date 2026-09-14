-- Locally cached native-asset metadata: how to name an asset and how to scale it.
--
-- Bounded by the number of distinct assets appearing in FluidTokens loans -- tens to hundreds, not
-- unbounded -- so there is deliberately NO eviction job. If that assumption ever breaks the table
-- grows slowly and visibly rather than failing quietly.
--
-- "source" is not decoration. It is what lets the UI tell a decimals value the registry actually
-- published from the absence of one, so an unknown asset renders its raw base-unit amount instead of
-- being silently mis-scaled. decimals is therefore NULLABLE on purpose: zero is a real scale and must
-- never stand in for "we do not know".
CREATE TABLE IF NOT EXISTS token_metadata (
    unit        VARCHAR(120) NOT NULL,
    ticker      VARCHAR(64),
    name        VARCHAR(256),
    decimals    INTEGER,
    source      VARCHAR(16)  NOT NULL,
    fetched_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_token_metadata PRIMARY KEY (unit)
);
