package com.rohit.nyvra.ingestion;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link RawFinancialRecord}; internal to the ingestion pipeline. */
public interface RawFinancialRecordRepository extends JpaRepository<RawFinancialRecord, UUID> {

    /**
     * Lists the records a fetch produced.
     *
     * @param fetchSessionId the fetch
     * @return its records, oldest first
     */
    List<RawFinancialRecord> findByFetchSessionIdOrderByReceivedAtAsc(UUID fetchSessionId);
}
