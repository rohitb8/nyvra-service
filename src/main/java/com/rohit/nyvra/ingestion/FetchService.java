package com.rohit.nyvra.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.crypto.BlindIndexHasher;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.ingestion.dto.FetchSessionResponse;
import com.rohit.nyvra.ingestion.dto.TriggerFetchRequest;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fetch use cases: pull data under an active consent, keep the raw payloads encrypted, normalise them and
 * publish {@link TransactionsIngested} events. Runs inline for now behind a small seam
 * ({@link #fetchAndNormalise}) so it can move to a queue worker without changing the API.
 */
@Service
public class FetchService {

    /** Domain code: the consent is not {@code ACTIVE} (or has lapsed), so no fetch is allowed. */
    static final String CONSENT_NOT_ACTIVE = "CONSENT_NOT_ACTIVE";

    /** Domain code: the requested range is invalid or lies outside the consent's range. */
    static final String FETCH_RANGE_INVALID = "FETCH_RANGE_INVALID";

    /** Domain code: only a completed fetch can be normalised again. */
    static final String FETCH_NOT_COMPLETED = "FETCH_NOT_COMPLETED";

    /** Failure text stored when the aggregator call fails with something unexpected. */
    private static final String UNEXPECTED_FAILURE = "Unexpected error while fetching";

    /** Consents, for ownership checks and the consent artefact id. */
    private final AggregatorConsentRepository consents;

    /** Fetch sessions. */
    private final FetchSessionRepository sessions;

    /** Encrypted raw payloads. */
    private final RawFinancialRecordRepository records;

    /** Normalisation history. */
    private final NormalisationRunRepository runs;

    /** Account Aggregator port. */
    private final AggregatorGateway gateway;

    /** Maps raw payloads to events. */
    private final RecordNormaliser normaliser;

    /** Publishes the normalised events to the other modules. */
    private final ApplicationEventPublisher events;

    /** Keyed hasher for the idempotency key. */
    private final BlindIndexHasher hasher;

    /** Resolves the caller. */
    private final CurrentUserService currentUser;

    /** Time source. */
    private final Clock clock = Clock.systemUTC();

    /** Days a raw record is kept before it may be purged. */
    private final int rawRetentionDays;

    /**
     * Creates the service.
     *
     * @param consents         consent store
     * @param sessions         fetch session store
     * @param records          raw record store
     * @param runs             normalisation run store
     * @param gateway          aggregator port
     * @param normaliser       payload normaliser
     * @param events           event publisher
     * @param hasher           keyed hasher
     * @param currentUser      caller resolver
     * @param rawRetentionDays days to keep raw records ({@code nyvra.ingestion.raw-retention-days}, default 30)
     */
    public FetchService(AggregatorConsentRepository consents, FetchSessionRepository sessions,
                        RawFinancialRecordRepository records, NormalisationRunRepository runs,
                        AggregatorGateway gateway, RecordNormaliser normaliser, ApplicationEventPublisher events,
                        BlindIndexHasher hasher, CurrentUserService currentUser,
                        @Value("${nyvra.ingestion.raw-retention-days:30}") int rawRetentionDays) {
        this.consents = consents;
        this.sessions = sessions;
        this.records = records;
        this.runs = runs;
        this.gateway = gateway;
        this.normaliser = normaliser;
        this.events = events;
        this.hasher = hasher;
        this.currentUser = currentUser;
        this.rawRetentionDays = rawRetentionDays;
    }

    /**
     * Outcome of {@link #trigger}.
     *
     * @param session the fetch session, including its latest normalisation summary
     * @param created whether this call created the session ({@code false} means an earlier identical call did)
     */
    public record TriggerResult(FetchSessionResponse session, boolean created) {
    }

    /**
     * Fetches and normalises data for a consent. Idempotent per consent and data range: repeating the call
     * returns the existing session without fetching again.
     *
     * @param consentId the consent
     * @param request   optional range, defaulting to the consent's
     * @return the session and whether it was newly created
     * @throws ConflictException            {@code CONSENT_NOT_ACTIVE}
     * @throws UnprocessableEntityException {@code FETCH_RANGE_INVALID}
     */
    @Transactional
    public TriggerResult trigger(UUID consentId, TriggerFetchRequest request) {
        UUID userId = currentUser.currentUser().getId();
        AggregatorConsent consent = consents.findByIdAndUserId(consentId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Consent", consentId));
        Instant now = clock.instant();
        if (!consent.canFetch(now)) {
            throw new ConflictException(CONSENT_NOT_ACTIVE, "The consent is " + consent.getStatus()
                + (consent.getStatus() == ConsentStatus.ACTIVE ? " but has expired" : ", not ACTIVE"));
        }
        LocalDate from = request != null && request.from() != null ? request.from() : consent.getDataRangeFrom();
        LocalDate to = request != null && request.to() != null ? request.to() : consent.getDataRangeTo();
        validateRange(consent, from, to);

        String dedupKey = hasher.hash(consentId + "|" + from + "|" + to);
        Optional<FetchSession> existing = sessions.findByDedupKey(dedupKey);
        if (existing.isPresent()) {
            return new TriggerResult(toResponse(existing.get()), false);
        }
        FetchSession session = sessions.save(new FetchSession(consentId, dedupKey, now));
        fetchAndNormalise(userId, consent, session, from, to);
        return new TriggerResult(toResponse(session), true);
    }

    /**
     * Normalises a completed fetch's stored records again, publishing the events again. Safe because
     * consumers dedupe on each transaction's key.
     *
     * @param sessionId the fetch session
     * @return the session with its new normalisation summary
     * @throws ConflictException {@code FETCH_NOT_COMPLETED}
     */
    @Transactional
    public FetchSessionResponse renormalise(UUID sessionId) {
        UUID userId = currentUser.currentUser().getId();
        FetchSession session = loadOwned(sessionId, userId);
        if (session.getStatus() != FetchStatus.COMPLETED) {
            throw new ConflictException(FETCH_NOT_COMPLETED, "Only a COMPLETED fetch can be normalised again");
        }
        List<AggregatorGateway.FiPayload> payloads = records
            .findByFetchSessionIdOrderByReceivedAtAsc(sessionId).stream()
            .map(r -> new AggregatorGateway.FiPayload(r.getFipId(), r.getAccountRef(), r.getPayloadType(),
                r.getRawJson()))
            .toList();
        normalise(userId, sessionId, payloads);
        return toResponse(session);
    }

    /**
     * Lists the caller's fetch sessions, newest first.
     *
     * @param consentId limit to one consent, or {@code null}
     * @param statuses  wanted states; {@code null} or empty for all
     * @param page      zero-based page
     * @param size      page size
     * @return the page
     */
    @Transactional(readOnly = true)
    public PageResponse<FetchSessionResponse> list(UUID consentId, List<FetchStatus> statuses, int page, int size) {
        UUID userId = currentUser.currentUser().getId();
        List<UUID> owned = consents.findByUserId(userId).stream().map(AggregatorConsent::getId).toList();
        Pageable paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));
        if (owned.isEmpty() || consentId != null && !owned.contains(consentId)) {
            return PageResponse.of(new PageImpl<FetchSession>(List.of(), paging, 0), s -> toResponse(s));
        }
        Specification<FetchSession> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(consentId != null ? cb.equal(root.get("consentId"), consentId) : root.get("consentId").in(owned));
            if (statuses != null && !statuses.isEmpty()) {
                where.add(root.get("status").in(statuses));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<FetchSession> result = sessions.findAll(spec, paging);
        Map<UUID, NormalisationRun> latest = result.getContent().stream()
            .map(s -> runs.findFirstByFetchSessionIdOrderByCreatedAtDesc(s.getId()))
            .flatMap(Optional::stream)
            .collect(Collectors.toMap(NormalisationRun::getFetchSessionId, Function.identity()));
        return PageResponse.of(result, s -> FetchSessionResponse.from(s, latest.get(s.getId())));
    }

    /**
     * Returns one of the caller's fetch sessions.
     *
     * @param sessionId the session id
     * @return the session
     * @throws ResourceNotFoundException if missing or not the caller's
     */
    @Transactional(readOnly = true)
    public FetchSessionResponse get(UUID sessionId) {
        return toResponse(loadOwned(sessionId, currentUser.currentUser().getId()));
    }

    /**
     * Runs the aggregator call, stores the raw payloads, normalises them and closes the session. A failure
     * is recorded on the session ({@code FAILED}) and announced as {@link IngestionFailed}; it never fails
     * the request, so the caller can read the outcome.
     *
     * @param userId  owner of the data
     * @param consent the consent being used
     * @param session the running session
     * @param from    first day to fetch
     * @param to      last day to fetch
     */
    private void fetchAndNormalise(UUID userId, AggregatorConsent consent, FetchSession session,
                                   LocalDate from, LocalDate to) {
        try {
            List<AggregatorGateway.FiPayload> payloads = gateway.fetch(consent.getConsentId(), from, to);
            Instant now = clock.instant();
            LocalDate purgeAfter = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(rawRetentionDays);
            records.saveAll(payloads.stream()
                .map(p -> new RawFinancialRecord(session.getId(), p.fipId(), p.accountRef(), p.payloadType(),
                    p.json(), now, purgeAfter))
                .toList());
            normalise(userId, session.getId(), payloads);
            session.complete(payloads.stream().map(p -> p.payloadType().name()).distinct().sorted().toList(),
                clock.instant());
        } catch (AggregatorException e) {
            fail(userId, consent, session, e.getMessage());
        } catch (RuntimeException e) {
            fail(userId, consent, session, UNEXPECTED_FAILURE);
        }
        sessions.save(session);
    }

    /**
     * Records a failed session and publishes {@link IngestionFailed}.
     *
     * @param userId  owner of the data
     * @param consent the consent used
     * @param session the session that failed
     * @param reason  short, personal-data-free reason
     */
    private void fail(UUID userId, AggregatorConsent consent, FetchSession session, String reason) {
        session.fail(reason, clock.instant());
        events.publishEvent(new IngestionFailed(userId, consent.getId(), session.getId(), reason));
    }

    /**
     * Normalises payloads, records the run and publishes the events.
     *
     * @param userId    owner of the data
     * @param sessionId the fetch
     * @param payloads  the payloads to map
     */
    private void normalise(UUID userId, UUID sessionId, List<AggregatorGateway.FiPayload> payloads) {
        RecordNormaliser.Result result = normaliser.normalise(userId, sessionId, payloads);
        runs.save(new NormalisationRun(sessionId, result.events().size(), result.unmapped()));
        result.events().forEach(events::publishEvent);
    }

    /**
     * Checks a requested range is ordered and within the consent's range.
     *
     * @param consent the consent
     * @param from    requested start
     * @param to      requested end
     * @throws UnprocessableEntityException {@code FETCH_RANGE_INVALID}
     */
    private void validateRange(AggregatorConsent consent, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new UnprocessableEntityException(FETCH_RANGE_INVALID, "from and to must form a valid range");
        }
        if (consent.getDataRangeFrom() != null && from.isBefore(consent.getDataRangeFrom())
            || consent.getDataRangeTo() != null && to.isAfter(consent.getDataRangeTo())) {
            throw new UnprocessableEntityException(FETCH_RANGE_INVALID, "The range is outside the consent's range");
        }
    }

    /**
     * Loads a session whose consent belongs to the caller.
     *
     * @param sessionId the session id
     * @param userId    the caller
     * @return the session
     * @throws ResourceNotFoundException if missing or not the caller's
     */
    private FetchSession loadOwned(UUID sessionId, UUID userId) {
        FetchSession session = sessions.findById(sessionId)
            .orElseThrow(() -> ResourceNotFoundException.of("Fetch session", sessionId));
        consents.findByIdAndUserId(session.getConsentId(), userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Fetch session", sessionId));
        return session;
    }

    /**
     * Builds a response including the latest normalisation run.
     *
     * @param session the session
     * @return the response
     */
    private FetchSessionResponse toResponse(FetchSession session) {
        return FetchSessionResponse.from(session,
            runs.findFirstByFetchSessionIdOrderByCreatedAtDesc(session.getId()).orElse(null));
    }
}
