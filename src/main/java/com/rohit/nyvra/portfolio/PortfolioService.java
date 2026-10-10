package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.dto.CreateHoldingRequest;
import com.rohit.nyvra.portfolio.dto.CreateInstrumentRequest;
import com.rohit.nyvra.portfolio.dto.HoldingResponse;
import com.rohit.nyvra.portfolio.dto.InstrumentResponse;
import com.rohit.nyvra.portfolio.dto.PortfolioSummaryResponse;
import com.rohit.nyvra.portfolio.dto.PortfolioSummaryResponse.AllocationSlice;
import com.rohit.nyvra.portfolio.dto.QuoteResponse;
import com.rohit.nyvra.portfolio.dto.RecordQuoteRequest;
import com.rohit.nyvra.portfolio.dto.UpdateHoldingRequest;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portfolio use cases for the signed-in user. Holding lookups are scoped to the caller, so another user's
 * holding is indistinguishable from a missing one (404, never 403). Instruments and price quotes are shared
 * reference data and are not user-scoped.
 *
 * <p>Business rules enforced here: holdings are INR only in v1 ({@code UNSUPPORTED_CURRENCY}), one open
 * holding per instrument ({@code HOLDING_ALREADY_EXISTS}), ISINs are unique ({@code INSTRUMENT_ISIN_EXISTS}),
 * quotes cannot be dated in the future ({@code QUOTE_IN_FUTURE}), and holdings that did not come from the
 * user are read-only ({@code HOLDING_READ_ONLY}).
 */
@Service
public class PortfolioService {

    /** The only holding currency supported in v1. */
    private static final String SUPPORTED_CURRENCY = Money.INR;

    /** Clock skew tolerated before a timestamp counts as being in the future. */
    private static final long FUTURE_SKEW_SECONDS = 60;

    /** Holding properties a client may sort by. */
    private static final Set<String> HOLDING_SORTABLE = Set.of("openedAt", "assetClass", "createdAt");

    /** Instrument persistence. */
    private final InstrumentRepository instruments;
    /** Holding persistence. */
    private final PortfolioHoldingRepository holdings;
    /** Price quote persistence. */
    private final PriceQuoteRepository quotes;
    /** Resolves the signed-in user that holding queries are scoped to. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param instruments the instrument repository
     * @param holdings the holding repository
     * @param quotes the price quote repository
     * @param currentUser the signed-in user resolver
     */
    public PortfolioService(InstrumentRepository instruments, PortfolioHoldingRepository holdings,
                            PriceQuoteRepository quotes, CurrentUserService currentUser) {
        this.instruments = instruments;
        this.holdings = holdings;
        this.quotes = quotes;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ instruments

    /**
     * Searches the shared instrument catalogue.
     *
     * @param q          case-insensitive text matched against symbol, name and ISIN; {@code null} or blank for all
     * @param assetClass only this asset class; {@code null} for all
     * @param page       zero-based page index
     * @param size       page size
     * @return one page of instruments ordered by name, then symbol
     */
    @Transactional(readOnly = true)
    public PageResponse<InstrumentResponse> searchInstruments(String q, AssetClass assetClass, int page, int size) {
        Specification<Instrument> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (assetClass != null) {
                where.add(cb.equal(root.get("assetClass"), assetClass));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.strip().toLowerCase()) + "%";
                where.add(cb.or(
                    cb.like(cb.lower(root.get("symbol")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("isin")), pattern, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page, size,
            Sort.by(Sort.Order.asc("name"), Sort.Order.asc("symbol"), Sort.Order.asc("id")));
        return PageResponse.of(instruments.findAll(spec, pageable), InstrumentResponse::from);
    }

    /**
     * Returns one instrument.
     *
     * @param id the instrument id
     * @return the instrument
     * @throws ResourceNotFoundException if it does not exist
     */
    @Transactional(readOnly = true)
    public InstrumentResponse getInstrument(UUID id) {
        return InstrumentResponse.from(instrument(id));
    }

    /**
     * Adds an instrument to the shared catalogue. The currency defaults to INR.
     *
     * @param request the new instrument
     * @return the created instrument
     * @throws BadRequestException if neither an ISIN nor a symbol is given
     * @throws ConflictException {@code INSTRUMENT_ISIN_EXISTS} if another instrument has the ISIN
     */
    @Transactional
    public InstrumentResponse createInstrument(CreateInstrumentRequest request) {
        if (request.isin() == null && request.symbol() == null) {
            throw new BadRequestException("An instrument needs an isin or a symbol");
        }
        if (request.isin() != null && instruments.findByIsin(request.isin()).isPresent()) {
            throw new ConflictException("INSTRUMENT_ISIN_EXISTS",
                "An instrument with ISIN " + request.isin() + " already exists");
        }
        String currency = request.currency() != null ? request.currency() : SUPPORTED_CURRENCY;
        return InstrumentResponse.from(instruments.save(new Instrument(request.isin(), request.symbol(),
            request.name(), request.assetClass(), currency, request.country())));
    }

    /**
     * Records a manual price for an instrument. Quotes are shared by all users and append-only; a repeat for
     * the same instant replaces the earlier one.
     *
     * @param instrumentId the instrument
     * @param request the price and optional timestamp
     * @return the stored quote
     * @throws ResourceNotFoundException if the instrument does not exist
     * @throws UnprocessableEntityException {@code QUOTE_IN_FUTURE} if the timestamp is in the future
     */
    @Transactional
    public QuoteResponse recordQuote(UUID instrumentId, RecordQuoteRequest request) {
        Instrument instrument = instrument(instrumentId);
        Instant now = Instant.now();
        Instant asOf = request.asOf() != null ? request.asOf() : now;
        if (asOf.isAfter(now.plusSeconds(FUTURE_SKEW_SECONDS))) {
            throw new UnprocessableEntityException("QUOTE_IN_FUTURE", "A price cannot be dated in the future");
        }
        return QuoteResponse.from(quotes.save(new PriceQuote(instrument.getId(),
            asOf.truncatedTo(ChronoUnit.MICROS), new BigDecimal(request.price()), RecordSource.MANUAL)));
    }

    // ------------------------------------------------------------------ holdings

    /**
     * Lists the caller's holdings with their valuation. Closed holdings are left out unless asked for.
     *
     * @param assetClasses  only these asset classes; {@code null} or empty for all
     * @param includeClosed whether closed (zero-quantity) holdings are included
     * @param page          zero-based page index
     * @param size          page size
     * @param sort          {@code field,asc|desc} entries; {@code null} or empty sorts by opened date descending
     * @return one page of holdings
     * @throws BadRequestException if a sort field or direction is invalid
     */
    @Transactional(readOnly = true)
    public PageResponse<HoldingResponse> listHoldings(Collection<AssetClass> assetClasses, boolean includeClosed,
                                                      int page, int size, List<String> sort) {
        UUID userId = currentUser.currentUser().getId();
        Specification<PortfolioHolding> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            if (assetClasses != null && !assetClasses.isEmpty()) {
                where.add(root.get("assetClass").in(assetClasses));
            }
            if (!includeClosed) {
                where.add(cb.isNull(root.get("closedAt")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page, size, parseSort(sort));
        var result = holdings.findAll(spec, pageable);
        Map<UUID, HoldingResponse> byId = toResponses(result.getContent()).stream()
            .collect(Collectors.toMap(HoldingResponse::id, Function.identity()));
        return PageResponse.of(result, h -> byId.get(h.getId()));
    }

    /**
     * Returns one of the caller's holdings with its valuation.
     *
     * @param id the holding id
     * @return the holding
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional(readOnly = true)
    public HoldingResponse getHolding(UUID id) {
        return toResponses(List.of(ownedHolding(id))).get(0);
    }

    /**
     * Records a holding manually (source {@code MANUAL}).
     *
     * @param request the new holding
     * @return the created holding
     * @throws BadRequestException if the quantity is not above zero or {@code openedAt} is in the future
     * @throws ResourceNotFoundException if the instrument does not exist
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} if the instrument is not priced in INR
     * @throws ConflictException {@code HOLDING_ALREADY_EXISTS} if the caller already holds the instrument
     */
    @Transactional
    public HoldingResponse createHolding(CreateHoldingRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Instrument instrument = instrument(request.instrumentId());
        if (!SUPPORTED_CURRENCY.equals(instrument.getCurrency())) {
            throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY",
                "Only INR holdings are supported; this instrument is priced in " + instrument.getCurrency());
        }
        BigDecimal quantity = new BigDecimal(request.quantity());
        if (quantity.signum() <= 0) {
            throw new BadRequestException("quantity must be above zero");
        }
        Instant now = Instant.now();
        Instant openedAt = request.openedAt() != null ? request.openedAt() : now;
        if (openedAt.isAfter(now.plusSeconds(FUTURE_SKEW_SECONDS))) {
            throw new BadRequestException("openedAt must not be in the future");
        }
        if (holdings.existsByUserIdAndInstrumentIdAndClosedAtIsNull(userId, instrument.getId())) {
            throw new ConflictException("HOLDING_ALREADY_EXISTS",
                "You already hold this instrument; update the existing holding instead");
        }
        BigDecimal avgCost = request.avgCost() == null ? null : new BigDecimal(request.avgCost());
        PortfolioHolding saved = holdings.save(
            new PortfolioHolding(userId, instrument, quantity, avgCost, RecordSource.MANUAL, openedAt));
        return toResponses(List.of(saved)).get(0);
    }

    /**
     * Applies a partial update. A quantity of zero closes the holding; a positive quantity reopens a closed one.
     *
     * @param id the holding id
     * @param request the fields to change
     * @return the updated holding
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws BadRequestException if the request changes nothing
     * @throws ConflictException {@code HOLDING_READ_ONLY} if the holding did not come from the user
     */
    @Transactional
    public HoldingResponse updateHolding(UUID id, UpdateHoldingRequest request) {
        PortfolioHolding holding = ownedHolding(id);
        requireManual(holding);
        if (request.quantity() == null && request.avgCost() == null) {
            throw new BadRequestException("Send quantity and/or avgCost to change");
        }
        BigDecimal quantity = request.quantity() == null ? null : new BigDecimal(request.quantity());
        BigDecimal avgCost = request.avgCost() == null ? null : new BigDecimal(request.avgCost());
        if (quantity != null && quantity.signum() > 0 && !holding.isOpen()
            && holdings.existsByUserIdAndInstrumentIdAndClosedAtIsNull(holding.getUserId(), holding.getInstrumentId())) {
            throw new ConflictException("HOLDING_ALREADY_EXISTS",
                "You already hold this instrument; update that holding instead");
        }
        holding.adjust(quantity, avgCost, Instant.now());
        return toResponses(List.of(holding)).get(0);
    }

    /**
     * Permanently deletes a holding.
     *
     * @param id the holding id
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws ConflictException {@code HOLDING_READ_ONLY} if the holding did not come from the user
     */
    @Transactional
    public void deleteHolding(UUID id) {
        PortfolioHolding holding = ownedHolding(id);
        requireManual(holding);
        holdings.delete(holding);
    }

    // ------------------------------------------------------------------ summary

    /**
     * Totals and asset-class allocation across the caller's open INR holdings. Holdings in other currencies
     * are counted but not valued, because there is no FX source yet.
     *
     * @return the portfolio summary
     */
    @Transactional(readOnly = true)
    public PortfolioSummaryResponse summary() {
        UUID userId = currentUser.currentUser().getId();
        List<PortfolioHolding> open = holdings.findByUserIdAndClosedAtIsNull(userId);
        List<PortfolioHolding> inr = open.stream().filter(h -> SUPPORTED_CURRENCY.equals(h.getCurrency())).toList();
        Map<UUID, PriceQuote> latest = latestQuotes(inr);

        Money zero = Money.zero(SUPPORTED_CURRENCY);
        Money invested = zero;
        Money current = zero;
        Money gain = zero;
        Money gainBasis = zero;
        boolean anyGain = false;
        int unpriced = 0;
        Instant pricesAsOf = null;
        Map<AssetClass, Money> valueByClass = new EnumMap<>(AssetClass.class);
        Map<AssetClass, Integer> countByClass = new EnumMap<>(AssetClass.class);

        for (PortfolioHolding h : inr) {
            PriceQuote quote = latest.get(h.getInstrumentId());
            Money cost = PortfolioValuation.invested(h.getQuantity(), h.getAvgCost(), h.getCurrency());
            Money value = quote == null ? null
                : PortfolioValuation.current(h.getQuantity(), quote.getPrice(), h.getCurrency());
            if (cost != null) {
                invested = invested.plus(cost);
            }
            if (value != null) {
                current = current.plus(value);
                if (pricesAsOf == null || quote.getAsOf().isAfter(pricesAsOf)) {
                    pricesAsOf = quote.getAsOf();
                }
            } else {
                unpriced++;
            }
            Money holdingGain = PortfolioValuation.gain(cost, value);
            if (holdingGain != null) {
                anyGain = true;
                gain = gain.plus(holdingGain);
                gainBasis = gainBasis.plus(cost);
            }
            Money allocated = value != null ? value : cost;
            if (allocated != null) {
                valueByClass.merge(h.getAssetClass(), allocated, Money::plus);
                countByClass.merge(h.getAssetClass(), 1, Integer::sum);
            }
        }

        Money allocatedTotal = valueByClass.values().stream().reduce(zero, Money::plus);
        List<AllocationSlice> allocation = valueByClass.entrySet().stream()
            .sorted(Map.Entry.<AssetClass, Money>comparingByValue(Comparator.comparing(Money::amount)).reversed())
            .map(e -> new AllocationSlice(e.getKey(), MoneyDto.from(e.getValue()),
                allocatedTotal.amount().signum() == 0 ? BigDecimal.ZERO
                    : PortfolioValuation.percent(e.getValue().amount(), allocatedTotal.amount()),
                countByClass.get(e.getKey())))
            .toList();

        return new PortfolioSummaryResponse(SUPPORTED_CURRENCY, MoneyDto.from(invested), MoneyDto.from(current),
            anyGain ? MoneyDto.from(gain) : null,
            anyGain ? PortfolioValuation.gainPercent(gain, gainBasis) : null,
            inr.size(), unpriced, open.size() - inr.size(), pricesAsOf, allocation);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Loads an instrument.
     *
     * @param id the instrument id
     * @return the instrument
     * @throws ResourceNotFoundException if it does not exist
     */
    private Instrument instrument(UUID id) {
        return instruments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Instrument not found"));
    }

    /**
     * Loads one of the caller's holdings.
     *
     * @param id the holding id
     * @return the holding
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    private PortfolioHolding ownedHolding(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return holdings.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Holding not found"));
    }

    /**
     * Rejects changes to holdings that were not entered by the user.
     *
     * @param holding the holding to change
     * @throws ConflictException {@code HOLDING_READ_ONLY} unless the holding is {@code MANUAL}
     */
    private static void requireManual(PortfolioHolding holding) {
        if (holding.getSource() != RecordSource.MANUAL) {
            throw new ConflictException("HOLDING_READ_ONLY",
                "This holding was imported from " + holding.getSource() + " and can't be changed here");
        }
    }

    /**
     * Fetches the latest quote of every instrument held, in one query.
     *
     * @param list the holdings to price
     * @return the latest quote by instrument id; instruments without a quote are absent
     */
    private Map<UUID, PriceQuote> latestQuotes(List<PortfolioHolding> list) {
        List<UUID> ids = list.stream().map(PortfolioHolding::getInstrumentId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PriceQuote> byInstrument = new HashMap<>();
        quotes.findLatestByInstrumentIds(ids).forEach(q -> byInstrument.put(q.getInstrumentId(), q));
        return byInstrument;
    }

    /**
     * Maps holdings to responses, batching the instrument and quote lookups instead of querying per row.
     *
     * @param list the holdings
     * @return the responses in the same order
     */
    private List<HoldingResponse> toResponses(List<PortfolioHolding> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instrument> instrumentsById = instruments
            .findAllById(list.stream().map(PortfolioHolding::getInstrumentId).distinct().toList()).stream()
            .collect(Collectors.toMap(Instrument::getId, Function.identity()));
        Map<UUID, PriceQuote> latest = latestQuotes(list);
        return list.stream()
            .map(h -> HoldingResponse.from(h, instrumentsById.get(h.getInstrumentId()),
                h.isOpen() ? latest.get(h.getInstrumentId()) : null))
            .toList();
    }

    /**
     * Escapes the SQL {@code LIKE} wildcards in user text so it is matched literally.
     *
     * @param text the raw search text
     * @return the text with {@code \}, {@code %} and {@code _} escaped
     */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Turns {@code field,asc|desc} entries into a {@link Sort}, always ending with the id for stable paging.
     *
     * @param sort the raw entries; {@code null} or empty for the default (opened date, newest first)
     * @return the sort
     * @throws BadRequestException if a field is not sortable or a direction is invalid
     */
    private static Sort parseSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(Sort.Order.desc("openedAt"), Sort.Order.asc("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String entry : sort) {
            String[] parts = entry.split(",");
            String field = parts[0].trim();
            if (!HOLDING_SORTABLE.contains(field) || parts.length > 2) {
                throw new BadRequestException("Can't sort holdings by '" + entry + "'");
            }
            boolean desc = parts.length == 2 && parts[1].trim().equalsIgnoreCase("desc");
            if (parts.length == 2 && !desc && !parts[1].trim().equalsIgnoreCase("asc")) {
                throw new BadRequestException("Sort direction must be asc or desc: '" + entry + "'");
            }
            orders.add(desc ? Sort.Order.desc(field) : Sort.Order.asc(field));
        }
        orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }
}
