package com.rohit.nyvra.ingestion;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.ingestion.dto.ActivateConsentRequest;
import com.rohit.nyvra.ingestion.dto.ConsentResponse;
import com.rohit.nyvra.ingestion.dto.CreateConsentRequest;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent use cases for the signed-in user. Every lookup is scoped to the caller, so another user's consent
 * is indistinguishable from a missing one (404, never 403).
 */
@Service
public class ConsentService {

    /** Domain code for a data range whose end precedes its start. */
    static final String INVALID_DATA_RANGE = "INVALID_DATA_RANGE";

    /** Domain code for an aggregator refusal of a consent call. */
    static final String AGGREGATOR_REJECTED = "AGGREGATOR_REJECTED";

    /** Stores consents. */
    private final AggregatorConsentRepository consents;

    /** Talks to the Account Aggregator. */
    private final AggregatorGateway gateway;

    /** Resolves the caller. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param consents    consent store
     * @param gateway     aggregator port
     * @param currentUser caller resolver
     */
    public ConsentService(AggregatorConsentRepository consents, AggregatorGateway gateway,
                          CurrentUserService currentUser) {
        this.consents = consents;
        this.gateway = gateway;
        this.currentUser = currentUser;
    }

    /**
     * Lists the caller's consents, newest first.
     *
     * @param statuses wanted states; {@code null} or empty for all
     * @param page     zero-based page
     * @param size     page size
     * @return the page
     */
    @Transactional(readOnly = true)
    public PageResponse<ConsentResponse> list(Collection<ConsentStatus> statuses, int page, int size) {
        UUID userId = currentUser.currentUser().getId();
        PageRequest paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AggregatorConsent> result = statuses == null || statuses.isEmpty()
            ? consents.findByUserId(userId, paging)
            : consents.findByUserIdAndStatusIn(userId, statuses, paging);
        return PageResponse.of(result, ConsentResponse::from);
    }

    /**
     * Returns one of the caller's consents.
     *
     * @param id the consent id
     * @return the consent
     * @throws ResourceNotFoundException if it does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public ConsentResponse get(UUID id) {
        return ConsentResponse.from(load(id));
    }

    /**
     * Starts a consent request at the aggregator.
     *
     * @param request the consent details
     * @return the new consent in {@code REQUESTED}
     * @throws UnprocessableEntityException {@code INVALID_DATA_RANGE} or {@code AGGREGATOR_REJECTED}
     */
    @Transactional
    public ConsentResponse create(CreateConsentRequest request) {
        if (request.dataRangeTo().isBefore(request.dataRangeFrom())) {
            throw new UnprocessableEntityException(INVALID_DATA_RANGE, "dataRangeTo must not precede dataRangeFrom");
        }
        UUID userId = currentUser.currentUser().getId();
        String handle;
        try {
            handle = gateway.requestConsent(request.aaHandle(), request.fipList(),
                request.dataRangeFrom(), request.dataRangeTo(), request.frequency());
        } catch (AggregatorException e) {
            throw new UnprocessableEntityException(AGGREGATOR_REJECTED, e.getMessage());
        }
        return ConsentResponse.from(consents.save(new AggregatorConsent(userId, request.aaHandle(),
            request.fipList(), handle, request.dataRangeFrom(), request.dataRangeTo(), request.frequency())));
    }

    /**
     * Activates a consent the user approved at the aggregator.
     *
     * @param id      the consent id
     * @param request carries the approved consent artefact id
     * @return the active consent
     * @throws UnprocessableEntityException {@code AGGREGATOR_REJECTED} if the aggregator does not confirm it
     */
    @Transactional
    public ConsentResponse activate(UUID id, ActivateConsentRequest request) {
        AggregatorConsent consent = load(id);
        Instant expiry;
        try {
            expiry = gateway.confirmConsent(consent.getConsentHandle(), request.consentId());
        } catch (AggregatorException e) {
            throw new UnprocessableEntityException(AGGREGATOR_REJECTED, e.getMessage());
        }
        consent.activate(request.consentId(), expiry);
        return ConsentResponse.from(consents.save(consent));
    }

    /**
     * Pauses an active consent.
     *
     * @param id the consent id
     * @return the paused consent
     */
    @Transactional
    public ConsentResponse pause(UUID id) {
        AggregatorConsent consent = load(id);
        consent.pause();
        return ConsentResponse.from(consents.save(consent));
    }

    /**
     * Resumes a paused consent.
     *
     * @param id the consent id
     * @return the active consent
     */
    @Transactional
    public ConsentResponse resume(UUID id) {
        AggregatorConsent consent = load(id);
        consent.resume();
        return ConsentResponse.from(consents.save(consent));
    }

    /**
     * Revokes a consent and tells the aggregator. Imported data stays; no further fetch is possible.
     *
     * @param id the consent id
     * @return the revoked consent
     * @throws UnprocessableEntityException {@code AGGREGATOR_REJECTED} if the aggregator cannot record it
     */
    @Transactional
    public ConsentResponse revoke(UUID id) {
        AggregatorConsent consent = load(id);
        consent.revoke();
        try {
            gateway.revokeConsent(consent.getConsentId());
        } catch (AggregatorException e) {
            throw new UnprocessableEntityException(AGGREGATOR_REJECTED, e.getMessage());
        }
        return ConsentResponse.from(consents.save(consent));
    }

    /**
     * Loads a consent owned by the caller.
     *
     * @param id the consent id
     * @return the entity
     * @throws ResourceNotFoundException if missing or not the caller's
     */
    private AggregatorConsent load(UUID id) {
        return consents.findByIdAndUserId(id, currentUser.currentUser().getId())
            .orElseThrow(() -> ResourceNotFoundException.of("Consent", id));
    }
}
