package com.rohit.nyvra.portfolio;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.ingestion.HoldingsIngested;
import com.rohit.nyvra.ingestion.IngestedHolding;
import com.rohit.nyvra.ingestion.PayloadType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes {@link HoldingsIngested} and brings the user's portfolio in line with what the provider reported.
 * Each holding is matched to an instrument by ISIN (created on first sight), and the user's AA-sourced
 * position in it is created or set to the reported units; the reported price is recorded as a quote so the
 * holding can be valued. Net worth needs no hook, since it re-records the user's snapshot whenever a holding
 * changes. This lives in the portfolio module because ingestion reaches downstream modules by events only
 * (ARCHITECTURE.md rule 1).
 *
 * <p>Idempotent: the position is set to the reported units rather than incremented, a quote is written at most
 * once per instrument and day, and re-handling an event changes nothing. A position the user entered by hand
 * is never touched. The event is handled after the publisher commits, in a transaction of its own, and a
 * failure is logged without touching the fetch.
 *
 * <p>Limits: a holding that disappears from a later payload is not closed (a payload cannot be tied to an
 * account the portfolio knows), and the same instrument held in two folios is one position that follows
 * whichever payload arrived last.
 */
@Component
public class IngestedHoldingsWriter {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(IngestedHoldingsWriter.class);

    /** Currency of every ingested instrument. */
    private static final String INR = "INR";

    /** Instrument persistence. */
    private final InstrumentRepository instruments;

    /** Holding persistence. */
    private final PortfolioHoldingRepository holdings;

    /** Price quote persistence. */
    private final PriceQuoteRepository quotes;

    /** Runs {@link #apply} in a transaction of its own; a template because a self-call would bypass a proxy. */
    private final TransactionTemplate transaction;

    /**
     * Creates the writer.
     *
     * @param instruments instrument persistence
     * @param holdings    holding persistence
     * @param quotes      price quote persistence
     * @param txManager   transaction manager for the handler's own transaction
     */
    public IngestedHoldingsWriter(InstrumentRepository instruments, PortfolioHoldingRepository holdings,
                                  PriceQuoteRepository quotes, PlatformTransactionManager txManager) {
        this.instruments = instruments;
        this.holdings = holdings;
        this.quotes = quotes;
        this.transaction = new TransactionTemplate(txManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Event entry point: runs {@link #apply} after the publisher commits (or at once when there is no
     * surrounding transaction) and never lets a failure reach the publisher.
     *
     * @param event the holdings of one folio or demat account
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHoldingsIngested(HoldingsIngested event) {
        try {
            transaction.executeWithoutResult(status -> apply(event));
        } catch (RuntimeException e) {
            log.error("Applying ingested holdings of fetch {} failed", event.fetchSessionId(), e);
        }
    }

    /**
     * Creates or updates the user's positions for the reported holdings. Must run inside a transaction.
     *
     * @param event the holdings of one folio or demat account
     * @return how many positions were created or changed
     */
    int apply(HoldingsIngested event) {
        AssetClass assetClass = assetClass(event.payloadType());
        if (assetClass == null) {
            log.warn("Ignoring holdings of unsupported type {} in fetch {}", event.payloadType(),
                event.fetchSessionId());
            return 0;
        }
        Instant now = Instant.now();
        Instant quoteTime = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        int changed = 0;
        for (IngestedHolding h : event.holdings()) {
            if (h.isin() == null || h.isin().isBlank() || h.units() == null || h.units().signum() <= 0) {
                continue;
            }
            Instrument instrument = instruments.findByIsin(h.isin())
                .orElseGet(() -> instruments.save(new Instrument(h.isin(), null, h.name(), assetClass, INR, "IN")));
            recordQuote(instrument, h, quoteTime);
            if (applyPosition(event, instrument, h, now)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Sets the user's AA position in the instrument to the reported units.
     *
     * @param event      the ingested holdings, for the owner
     * @param instrument the instrument
     * @param h          the reported holding
     * @param now        the time to stamp a new position with
     * @return {@code true} when a position was created, reopened or its units changed
     */
    private boolean applyPosition(HoldingsIngested event, Instrument instrument, IngestedHolding h, Instant now) {
        List<PortfolioHolding> existing = holdings.findByUserIdAndInstrumentId(event.userId(), instrument.getId());
        if (existing.stream().anyMatch(p -> p.getSource() != RecordSource.AA && p.isOpen())) {
            return false;
        }
        PortfolioHolding position = existing.stream().filter(p -> p.getSource() == RecordSource.AA).findFirst()
            .orElse(null);
        if (position == null) {
            holdings.save(new PortfolioHolding(event.userId(), instrument, h.units(), null, RecordSource.AA, now));
            return true;
        }
        if (position.isOpen() && position.getQuantity().compareTo(h.units()) == 0) {
            return false;
        }
        position.adjust(h.units(), null, now);
        holdings.save(position);
        return true;
    }

    /**
     * Records the reported price as a quote at the start of today (UTC), unless one already exists for it.
     *
     * @param instrument the instrument
     * @param h          the reported holding; nothing is recorded without a price
     * @param quoteTime  the quote time, shared by every holding of the event
     */
    private void recordQuote(Instrument instrument, IngestedHolding h, Instant quoteTime) {
        if (h.price() == null) {
            return;
        }
        PriceQuoteId id = new PriceQuoteId(instrument.getId(), quoteTime);
        if (!quotes.existsById(id)) {
            quotes.save(new PriceQuote(instrument.getId(), quoteTime, h.price(), RecordSource.AA));
        }
    }

    /**
     * Maps an ingestion payload type to the asset class its holdings are stored as.
     *
     * @param payloadType the payload type
     * @return the asset class, or {@code null} for payload types that carry no holdings
     */
    private static AssetClass assetClass(PayloadType payloadType) {
        return switch (payloadType) {
            case MUTUAL_FUND -> AssetClass.MUTUAL_FUND;
            case EQUITIES -> AssetClass.EQUITY;
            default -> null;
        };
    }
}
