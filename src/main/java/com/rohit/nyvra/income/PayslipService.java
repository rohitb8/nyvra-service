package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.income.dto.PayslipResponse;
import com.rohit.nyvra.user.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Payslip upload and lookup for the caller's income entries. A file is checked (size, declared type and magic
 * bytes), stored through {@link PayslipStorage}, and recorded as {@code PENDING}; a new upload replaces the
 * entry's previous payslip. No parser exists yet, so a payslip stays {@code PENDING} until one is added.
 */
@Service
public class PayslipService {

    /** Largest accepted file, in bytes (5 MB). */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    /** Accepted media types mapped to the leading bytes a real file of that type starts with. */
    private static final Map<String, byte[]> SIGNATURES = Map.of(
        "application/pdf", new byte[] {'%', 'P', 'D', 'F'},
        "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
        "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    /** Longest file name kept; longer names are cut. */
    private static final int MAX_FILE_NAME = 255;

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(PayslipService.class);

    /** Entry persistence, used to check the caller owns the entry. */
    private final IncomeEntryRepository entries;
    /** Payslip persistence. */
    private final PayslipDocumentRepository payslips;
    /** Where the files live. */
    private final PayslipStorage storage;
    /** Resolves the signed-in user that every query is scoped to. */
    private final CurrentUserService currentUser;
    /** Decodes the stored parsed-fields JSON. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param entries      the entry repository
     * @param payslips     the payslip repository
     * @param storage      the file store
     * @param currentUser  the signed-in user resolver
     * @param objectMapper the JSON mapper
     */
    public PayslipService(IncomeEntryRepository entries, PayslipDocumentRepository payslips,
                          PayslipStorage storage, CurrentUserService currentUser, ObjectMapper objectMapper) {
        this.entries = entries;
        this.payslips = payslips;
        this.storage = storage;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores a payslip for one of the caller's entries, replacing any previous one.
     *
     * @param entryId the entry
     * @param file    the uploaded file
     * @return the new payslip, {@code PENDING} parsing
     * @throws ResourceNotFoundException if the entry does not exist or belongs to someone else
     * @throws BadRequestException       if the file is empty
     * @throws PayslipUploadException    413 if over 5 MB, 415 if not a PDF, PNG or JPEG
     */
    @Transactional
    public PayslipResponse upload(UUID entryId, MultipartFile file) {
        UUID userId = currentUser.currentUser().getId();
        entries.findByIdAndUserId(entryId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Income entry", entryId));
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file must not be empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PayslipUploadException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYSLIP_TOO_LARGE",
                "Payslips can be at most 5 MB");
        }
        String contentType = normalise(file.getContentType());
        requireMatchingSignature(file, contentType);

        List<PayslipDocument> previous = payslips.findByIncomeEntryId(entryId);
        String key = "payslips/%s/%s/%s".formatted(userId, entryId, UUID.randomUUID());
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the payslip", e);
        }
        PayslipDocument saved;
        try {
            payslips.deleteAll(previous);
            saved = payslips.saveAndFlush(new PayslipDocument(
                entryId, key, cleanFileName(file.getOriginalFilename()), contentType, file.getSize(), Instant.now()));
        } catch (RuntimeException e) {
            discard(key);
            throw e;
        }
        previous.forEach(old -> discard(old.getObjectKey()));
        return toResponse(saved);
    }

    /**
     * Returns the payslip on one of the caller's entries.
     *
     * @param entryId the entry
     * @return the payslip with its parsed fields when available
     * @throws ResourceNotFoundException if the entry does not exist, belongs to someone else, or has no payslip
     */
    @Transactional(readOnly = true)
    public PayslipResponse get(UUID entryId) {
        UUID userId = currentUser.currentUser().getId();
        entries.findByIdAndUserId(entryId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Income entry", entryId));
        return payslips.findByIncomeEntryId(entryId).stream()
            .max((a, b) -> a.getUploadedAt().compareTo(b.getUploadedAt()))
            .map(this::toResponse)
            .orElseThrow(() -> ResourceNotFoundException.of("Payslip for income entry", entryId));
    }

    /**
     * Maps a payslip to its response, decoding the parsed fields when present.
     *
     * @param document the payslip
     * @return the response; a stored breakup that no longer decodes is left out and logged
     */
    private PayslipResponse toResponse(PayslipDocument document) {
        PayslipResponse.ParsedFields fields = null;
        if (document.getParseStatus() == PayslipParseStatus.PARSED && document.getParsedFields() != null) {
            try {
                fields = objectMapper.readValue(document.getParsedFields(), PayslipResponse.ParsedFields.class);
            } catch (JsonProcessingException e) {
                log.warn("Payslip {} has parsed fields that cannot be decoded", document.getId());
            }
        }
        return PayslipResponse.from(document, fields);
    }

    /**
     * Normalises a declared content type and rejects anything but PDF, PNG and JPEG.
     *
     * @param declared the {@code Content-Type} the client sent, may be {@code null}
     * @return the bare lower-case media type
     * @throws PayslipUploadException 415 if it is not an accepted type
     */
    private static String normalise(String declared) {
        String type = declared == null ? "" : declared.split(";")[0].trim().toLowerCase();
        if (!SIGNATURES.containsKey(type)) {
            throw new PayslipUploadException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PAYSLIP_UNSUPPORTED_TYPE",
                "Payslips must be a PDF, PNG or JPEG file");
        }
        return type;
    }

    /**
     * Checks the file really starts like its declared type, so a renamed file is not accepted.
     *
     * @param file the upload
     * @param type the declared, accepted media type
     * @throws PayslipUploadException 415 if the leading bytes do not match
     */
    private static void requireMatchingSignature(MultipartFile file, String type) {
        byte[] expected = SIGNATURES.get(type);
        byte[] head = new byte[expected.length];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            if (read == head.length && java.util.Arrays.equals(head, expected)) {
                return;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the payslip", e);
        }
        throw new PayslipUploadException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PAYSLIP_UNSUPPORTED_TYPE",
            "The file's content does not match its type");
    }

    /**
     * Reduces an uploaded name to a plain file name: no directories, bounded length.
     *
     * @param original the client-supplied name, may be {@code null}
     * @return the cleaned name, or {@code payslip} when nothing usable remains
     */
    private static String cleanFileName(String original) {
        if (original == null) {
            return "payslip";
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1).trim();
        if (name.isEmpty()) {
            return "payslip";
        }
        return name.length() > MAX_FILE_NAME ? name.substring(0, MAX_FILE_NAME) : name;
    }

    /**
     * Removes a stored file on a best-effort basis; a failure is logged, not thrown, since the database state
     * is already decided.
     *
     * @param key the object key
     */
    private void discard(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored payslip {}", key, e);
        }
    }
}
