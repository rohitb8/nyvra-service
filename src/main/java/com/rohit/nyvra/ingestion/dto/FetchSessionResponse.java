package com.rohit.nyvra.ingestion.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rohit.nyvra.ingestion.FetchSession;
import com.rohit.nyvra.ingestion.FetchStatus;
import com.rohit.nyvra.ingestion.NormalisationRun;

/**
 * A fetch session with the summary of its latest normalisation pass. Raw provider data is never exposed.
 *
 * @param id            fetch session id
 * @param consentId     the consent it ran under
 * @param status        progress
 * @param fiTypes       FI types returned, possibly empty
 * @param requestedAt   when it was requested
 * @param completedAt   when it finished; absent while running
 * @param error         short failure reason; absent unless failed
 * @param normalisation the latest normalisation pass; absent if none has run
 */
public record FetchSessionResponse(UUID id, UUID consentId, FetchStatus status, List<String> fiTypes,
                                   Instant requestedAt,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Instant completedAt,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String error,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Normalisation normalisation) {

    /**
     * Summary of one normalisation pass.
     *
     * @param id             run id
     * @param status         outcome
     * @param producedEvents normalised events published
     * @param unmapped       counts of things that could not be mapped, by reason
     * @param ranAt          when the pass ran
     */
    public record Normalisation(UUID id, String status, int producedEvents, Map<String, Integer> unmapped,
                                Instant ranAt) {

        /**
         * Maps a run to its summary.
         *
         * @param run the run, or {@code null}
         * @return the summary, or {@code null} when there is no run
         */
        static Normalisation from(NormalisationRun run) {
            return run == null ? null : new Normalisation(run.getId(), run.getStatus(),
                run.getProducedEvents() == null ? 0 : run.getProducedEvents(),
                run.getUnmapped() == null ? Map.of() : run.getUnmapped(), run.getCreatedAt());
        }
    }

    /**
     * Maps a session and its latest run to the response.
     *
     * @param session the session
     * @param run     its latest normalisation run, or {@code null}
     * @return the response
     */
    public static FetchSessionResponse from(FetchSession session, NormalisationRun run) {
        return new FetchSessionResponse(session.getId(), session.getConsentId(), session.getStatus(),
            session.getFiTypes() == null ? List.of() : session.getFiTypes(), session.getRequestedAt(),
            session.getCompletedAt(), session.getError(), Normalisation.from(run));
    }
}
