package com.rohit.nyvra.ingestion;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for {@link RawFinancialRecord}; internal to the ingestion pipeline. */
public interface RawFinancialRecordRepository extends JpaRepository<RawFinancialRecord, UUID> {

    /**
     * Lists the records a fetch produced.
     *
     * @param fetchSessionId the fetch
     * @return its records, oldest first
     */
    List<RawFinancialRecord> findByFetchSessionIdOrderByReceivedAtAsc(UUID fetchSessionId);

    /**
     * Hard-deletes records past their retention date whose fetch has a completed normalisation run, so a
     * payload that was never successfully normalised is kept for a retry.
     *
     * @param today records with {@code purge_after} before this date are deleted
     * @return how many records were deleted
     */
    @Modifying
    @Query(value = """
        DELETE FROM raw_financial_record r
        WHERE r.purge_after < :today
          AND EXISTS (SELECT 1 FROM normalisation_run n
                      WHERE n.fetch_session_id = r.fetch_session_id AND n.status = 'COMPLETED')
        """, nativeQuery = true)
    int deletePurgeable(@Param("today") LocalDate today);
}
