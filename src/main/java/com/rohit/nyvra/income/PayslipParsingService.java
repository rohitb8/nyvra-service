package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.income.dto.PayslipResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Parses uploaded payslips in the background. After an upload commits, the stored file is read back, handed to
 * the {@link PayslipParser}, and the payslip becomes {@code PARSED} with its fields or {@code FAILED}. A
 * payslip that was replaced or deleted in the meantime is skipped.
 */
@Service
public class PayslipParsingService {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(PayslipParsingService.class);

    /** Payslip persistence. */
    private final PayslipDocumentRepository payslips;
    /** Where the files live. */
    private final PayslipStorage storage;
    /** Reads the fields out of a file. */
    private final PayslipParser parser;
    /** Encodes the parsed fields for storage. */
    private final ObjectMapper objectMapper;
    /** Runs the result update in its own transaction, since parsing itself must not hold one open. */
    private final TransactionTemplate transactions;

    /**
     * Creates the service.
     *
     * @param payslips     the payslip repository
     * @param storage      the file store
     * @param parser       the payslip parser
     * @param objectMapper the JSON mapper
     * @param txManager    the transaction manager used for the result update
     */
    public PayslipParsingService(PayslipDocumentRepository payslips, PayslipStorage storage, PayslipParser parser,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager txManager) {
        this.payslips = payslips;
        this.storage = storage;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(txManager);
    }

    /**
     * Starts parsing once the upload that published the event has committed.
     *
     * @param event the upload event
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUploaded(PayslipUploaded event) {
        parse(event.payslipId());
    }

    /**
     * Parses one payslip and records the outcome. Never throws: any failure leaves the payslip {@code FAILED}.
     *
     * @param payslipId the payslip to parse
     */
    public void parse(UUID payslipId) {
        Optional<PayslipDocument> found = payslips.findById(payslipId);
        if (found.isEmpty() || found.get().getParseStatus() != PayslipParseStatus.PENDING) {
            return;
        }
        PayslipDocument document = found.get();
        String fieldsJson = null;
        try (InputStream in = storage.open(document.getObjectKey())) {
            Optional<PayslipResponse.ParsedFields> fields = parser.parse(in, document.getContentType());
            if (fields.isPresent()) {
                fieldsJson = objectMapper.writeValueAsString(fields.get());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not parse payslip {}", payslipId, e);
        }
        record(payslipId, fieldsJson);
    }

    /**
     * Saves the outcome on the payslip, unless it has been removed meanwhile.
     *
     * @param payslipId  the payslip
     * @param fieldsJson the encoded fields, or {@code null} when parsing failed
     */
    private void record(UUID payslipId, String fieldsJson) {
        transactions.executeWithoutResult(status -> payslips.findById(payslipId).ifPresent(document -> {
            if (fieldsJson == null) {
                document.markParseFailed();
            } else {
                document.recordParsedFields(fieldsJson);
            }
        }));
    }
}
