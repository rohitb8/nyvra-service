-- V27 — Portfolio extras: daily FX rates against INR, used to value foreign-currency holdings.
-- inr_per_unit is how many rupees one unit of `currency` buys. Reference data shared by all users; rows are
-- written by an administrator or the price feed and are never edited by ordinary users.
CREATE TABLE fx_rate (
    currency     CHAR(3)       NOT NULL CHECK (currency <> 'INR'),
    rate_date    DATE          NOT NULL,
    inr_per_unit NUMERIC(19,6) NOT NULL CHECK (inr_per_unit > 0),
    source       TEXT          NOT NULL CHECK (source IN ('AA', 'MANUAL', 'DERIVED', 'GMAIL')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (currency, rate_date)
);
