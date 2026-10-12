package com.rohit.nyvra.income;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.dto.PayslipResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * {@link PayslipParser} for text-based PDF payslips. It extracts the page text with PDFBox and looks for the
 * labels Indian payslips commonly use: gross and net pay, a pay period or salary month, the employer and the
 * usual statutory deductions. A field it cannot find is left {@code null}; a file with neither gross nor net
 * pay counts as unreadable. Images and scanned (text-less) PDFs are not read, since that needs OCR.
 */
@Component
public class PdfPayslipParser implements PayslipParser {

    /** Currency every extracted amount is reported in; payslips are INR only for now. */
    private static final String CURRENCY = "INR";

    /** An amount such as {@code 1,23,456.00}, optionally prefixed by a rupee marker. */
    private static final String AMOUNT = "(?:₹|Rs\\.?|INR)?\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)";

    /** Label for the gross pay line. */
    private static final Pattern GROSS = Pattern.compile(
        "(?i)(?:gross\\s*(?:earnings?|salary|pay|total)?|total\\s+earnings?)\\s*[:\\-]?\\s*" + AMOUNT);

    /** Label for the net pay line. */
    private static final Pattern NET = Pattern.compile(
        "(?i)(?:net\\s*(?:pay|salary|payable|amount)|take[\\s-]*home(?:\\s*pay)?)\\s*[:\\-]?\\s*" + AMOUNT);

    /** Employer given as a labelled field. */
    private static final Pattern EMPLOYER_LABEL = Pattern.compile(
        "(?im)^\\s*(?:employer|company(?:\\s*name)?|organi[sz]ation)\\s*[:\\-]\\s*(.+?)\\s*$");

    /** A first line that looks like a company name. */
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
        "(?i)\\b(?:ltd|limited|pvt|private|llp|inc|corp|corporation|technologies|solutions|industries)\\b");

    /** Pay period given as two dates joined by "to" or a dash. */
    private static final Pattern PERIOD = Pattern.compile(
        "(?i)pay\\s*period\\s*[:\\-]?\\s*(\\S+(?:\\s+[A-Za-z]{3,9}\\s+\\d{4})?)\\s*(?:to|-|–)\\s*(\\S+(?:\\s+[A-Za-z]{3,9}\\s+\\d{4})?)");

    /** Salary month such as "Salary for September 2026" or "Month: Sep-2026". */
    private static final Pattern MONTH = Pattern.compile(
        "(?i)(?:pay\\s*slip\\s*for|salary\\s*(?:slip\\s*)?for|for\\s+the\\s+month(?:\\s+of)?|month)\\s*[:\\-]?\\s*"
            + "([A-Za-z]{3,9})[\\s,'\\-]*(\\d{4})");

    /** Statutory and common deductions looked for by label, in the order they are reported. */
    private static final List<Deduction> DEDUCTIONS = List.of(
        new Deduction("Provident fund", "(?:employee'?s?\\s+)?(?:provident\\s+fund|epf|pf)"),
        new Deduction("Professional tax", "(?:professional\\s+tax|prof\\.?\\s*tax|pt)"),
        new Deduction("Income tax (TDS)", "(?:income\\s+tax|tds|tax\\s+deducted(?:\\s+at\\s+source)?)"),
        new Deduction("ESI", "(?:esic?|employee\\s+state\\s+insurance)"),
        new Deduction("Labour welfare fund", "(?:lwf|labou?r\\s+welfare\\s+fund)"));

    /** Date shapes accepted in a pay period. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        dateFormat("dd-MMM-yyyy"), dateFormat("d-MMM-yyyy"), dateFormat("dd MMM yyyy"), dateFormat("d MMM yyyy"),
        dateFormat("dd/MM/yyyy"), dateFormat("dd-MM-yyyy"), dateFormat("yyyy-MM-dd"));

    /** One deduction to look for: the label reported and the pattern that finds it. */
    private record Deduction(String label, String namePattern) {

        /**
         * Builds the line pattern for this deduction: a label at the start of a line followed by an amount.
         *
         * @return the compiled pattern
         */
        Pattern linePattern() {
            return Pattern.compile("(?im)^\\s*" + namePattern + "\\s*[:\\-]?\\s*" + AMOUNT + "\\b");
        }
    }

    @Override
    public Optional<PayslipResponse.ParsedFields> parse(InputStream content, String contentType)
            throws IOException {
        if (!"application/pdf".equals(contentType)) {
            return Optional.empty();
        }
        String text;
        try (PDDocument document = Loader.loadPDF(content.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        }
        return parseText(text);
    }

    /**
     * Extracts the fields from already-extracted page text.
     *
     * @param text the text of the payslip
     * @return the fields, or empty when neither gross nor net pay is found
     */
    Optional<PayslipResponse.ParsedFields> parseText(String text) {
        MoneyDto gross = firstAmount(GROSS, text);
        MoneyDto net = firstAmount(NET, text);
        if (gross == null && net == null) {
            return Optional.empty();
        }
        LocalDate[] period = payPeriod(text);
        List<PayslipResponse.Deduction> deductions = deductions(text);
        return Optional.of(new PayslipResponse.ParsedFields(
            employer(text), period[0], period[1], gross, net, deductions.isEmpty() ? null : deductions));
    }

    /**
     * Finds the first amount that follows a label.
     *
     * @param label the pattern whose group 1 is the amount
     * @param text  the text to search
     * @return the amount in INR, or {@code null} when the label is absent
     */
    private static MoneyDto firstAmount(Pattern label, String text) {
        Matcher matcher = label.matcher(text);
        return matcher.find() ? money(matcher.group(1)) : null;
    }

    /**
     * Converts an amount as printed on the payslip into the API's money shape.
     *
     * @param printed the digits, possibly with Indian-style commas
     * @return the amount at two decimals in INR
     */
    private static MoneyDto money(String printed) {
        BigDecimal amount = new BigDecimal(printed.replace(",", "")).setScale(2, java.math.RoundingMode.HALF_UP);
        return new MoneyDto(amount.toPlainString(), CURRENCY);
    }

    /**
     * Finds the employer: a labelled field first, else a first line that looks like a company name.
     *
     * @param text the payslip text
     * @return the employer, or {@code null} when none is recognised
     */
    private static String employer(String text) {
        Matcher labelled = EMPLOYER_LABEL.matcher(text);
        if (labelled.find()) {
            return labelled.group(1);
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return COMPANY_SUFFIX.matcher(trimmed).find() ? trimmed : null;
            }
        }
        return null;
    }

    /**
     * Finds the pay period: an explicit date range, else the whole of a named salary month.
     *
     * @param text the payslip text
     * @return a two-element array, start then end; elements are {@code null} when no period is found
     */
    private static LocalDate[] payPeriod(String text) {
        Matcher range = PERIOD.matcher(text);
        if (range.find()) {
            LocalDate start = date(range.group(1));
            LocalDate end = date(range.group(2));
            if (start != null && end != null && !end.isBefore(start)) {
                return new LocalDate[] {start, end};
            }
        }
        Matcher month = MONTH.matcher(text);
        while (month.find()) {
            Month named = month(month.group(1));
            if (named != null) {
                YearMonth ym = YearMonth.of(Integer.parseInt(month.group(2)), named);
                return new LocalDate[] {ym.atDay(1), ym.atEndOfMonth()};
            }
        }
        return new LocalDate[] {null, null};
    }

    /**
     * Parses a date in any of the accepted shapes.
     *
     * @param printed the date as printed
     * @return the date, or {@code null} when it matches none of the shapes
     */
    private static LocalDate date(String printed) {
        String trimmed = printed.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    /**
     * Resolves a month name or abbreviation.
     *
     * @param name for example {@code Sep} or {@code September}
     * @return the month, or {@code null} when the word is not a month
     */
    private static Month month(String name) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        for (Month candidate : Month.values()) {
            String full = candidate.name().toLowerCase(Locale.ENGLISH);
            if (lower.length() >= 3 && full.startsWith(lower)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Collects the known deductions that appear on the payslip.
     *
     * @param text the payslip text
     * @return the deductions found, in report order, possibly empty
     */
    private static List<PayslipResponse.Deduction> deductions(String text) {
        List<PayslipResponse.Deduction> found = new ArrayList<>();
        for (Deduction deduction : DEDUCTIONS) {
            Matcher matcher = deduction.linePattern().matcher(text);
            if (matcher.find()) {
                found.add(new PayslipResponse.Deduction(deduction.label(), money(matcher.group(1))));
            }
        }
        return found;
    }

    /**
     * Builds a case-insensitive English date formatter.
     *
     * @param pattern the {@link DateTimeFormatter} pattern
     * @return the formatter
     */
    private static DateTimeFormatter dateFormat(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
            .toFormatter(Locale.ENGLISH);
    }
}
