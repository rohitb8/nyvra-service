package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 * <p>Each position remembers the folio or demat account ({@code fipId|accountRef}) that reported it. The same
 * instrument in two folios is therefore two positions, and a position whose holding no longer appears in a
 * later payload of its own account is closed. Positions created before the account was recorded are adopted
 * by the first payload that reports their instrument.
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
        String ref = event.fipId() + "|" + event.accountRef();
        Set<UUID> reported = new HashSet<>();
        int changed = 0;
        for (IngestedHolding h : event.holdings()) {
            if (h.isin() == null || h.isin().isBlank() || h.units() == null || h.units().signum() <= 0) {
                continue;
            }
            Instrument instrument = instruments.findByIsin(h.isin())
                .orElseGet(() -> instruments.save(new Instrument(h.isin(), null, h.name(), assetClass, INR, "IN")));
            reported.add(instrument.getId());
            recordQuote(instrument, h, quoteTime);
            if (applyPosition(event, instrument, h, now, ref)) {
                changed++;
            }
        }
        return changed + closeVanished(event, assetClass, ref, reported, now);
    }

    /**
     * Closes the user's open AA positions from this account that the payload no longer reports.
     *
     * @param event      the ingested holdings, for the owner
     * @param assetClass the asset class the payload covers; other classes are not touched
     * @param ref        the reporting account
     * @param reported   ids of the instruments the payload reported with units
     * @param now        the closing time
     * @return how many positions were closed
     */
    private int closeVanished(HoldingsIngested event, AssetClass assetClass, String ref, Set<UUID> reported,
                              Instant now) {
        int closed = 0;
        for (PortfolioHolding p : holdings.findByUserIdAndClosedAtIsNull(event.userId())) {
            if (p.getSource() == RecordSource.AA && ref.equals(p.getSourceRef()) && p.getAssetClass() == assetClass
                && !reported.contains(p.getInstrumentId())) {
                p.adjust(BigDecimal.ZERO, null, now);
                holdings.save(p);
                closed++;
            }
        }
        return closed;
    }

    /**
     * Sets the user's AA position in the instrument, for the reporting account, to the reported units.
     *
     * @param event      the ingested holdings, for the owner
     * @param instrument the instrument
     * @param h          the reported holding
     * @param now        the time to stamp a new position with
     * @param ref        the reporting account, as {@code fipId|accountRef}
     * @return {@code true} when a position was created, reopened or its units changed
     */
    private boolean applyPosition(HoldingsIngested event, Instrument instrument, IngestedHolding h, Instant now,
                                   String ref) {
        List<PortfolioHolding> existing = holdings.findByUserIdAndInstrumentId(event.userId(), instrument.getId());
        if (existing.stream().anyMatch(p -> p.getSource() != RecordSource.AA && p.isOpen())) {
            return false;
        }
        PortfolioHolding position = existing.stream()
            .filter(p -> p.getSource() == RecordSource.AA && ref.equals(p.getSourceRef())).findFirst()
            .orElseGet(() -> existing.stream()
                .filter(p -> p.getSource() == RecordSource.AA && p.getSourceRef() == null).findFirst().orElse(null));
        if (position == null) {
            holdings.save(new PortfolioHolding(event.userId(), instrument, h.units(), null, RecordSource.AA, now, ref));
            return true;
        }
        if (ref.equals(position.getSourceRef()) && position.isOpen()
            && position.getQuantity().compareTo(h.units()) == 0) {
            return false;
        }
        position.claimFor(ref);
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
