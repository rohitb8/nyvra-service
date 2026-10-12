package com.rohit.nyvra.income;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.rohit.nyvra.income.dto.PayslipResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PdfPayslipParser}: label matching on text, and reading real generated PDFs. */
class PdfPayslipParserTest {

    /** Parser under test. */
    private final PdfPayslipParser parser = new PdfPayslipParser();

    /** A typical payslip layout as extracted text. */
    private static final String TYPICAL = """
        Acme Technologies Pvt Ltd
        Payslip for the month of September 2026
        Pay Period: 01-Sep-2026 to 30-Sep-2026
        Basic 60,000.00
        Gross Earnings 1,20,000.00
        Provident Fund 7,200.00
        Professional Tax 200.00
        Income Tax 12,500.50
        Net Pay 1,00,099.50
        """;

    /**
     * Builds a one-page PDF with one line of text per element.
     *
     * @param lines the text lines
     * @return the PDF bytes
     * @throws IOException if the PDF cannot be built
     */
    static byte[] pdf(List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                stream.setLeading(14);
                stream.newLineAtOffset(40, 740);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLine();
                }
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Gross, net, period, employer and the known deductions are read from typical text. */
    @Test
    void readsTheFieldsOfATypicalPayslip() {
        PayslipResponse.ParsedFields fields = parser.parseText(TYPICAL).orElseThrow();

        assertThat(fields.employer()).isEqualTo("Acme Technologies Pvt Ltd");
        assertThat(fields.payPeriodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(fields.payPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(fields.grossAmount().amount()).isEqualTo("120000.00");
        assertThat(fields.grossAmount().currency()).isEqualTo("INR");
        assertThat(fields.netAmount().amount()).isEqualTo("100099.50");
        assertThat(fields.deductions()).extracting(PayslipResponse.Deduction::label)
            .containsExactly("Provident fund", "Professional tax", "Income tax (TDS)");
        assertThat(fields.deductions()).extracting(d -> d.amount().amount())
            .containsExactly("7200.00", "200.00", "12500.50");
    }

    /** Without an explicit range, a named salary month becomes the whole calendar month. */
    @Test
    void usesTheWholeSalaryMonthWhenThereIsNoRange() {
        PayslipResponse.ParsedFields fields = parser.parseText("Salary for Feb 2028\nNet Salary: Rs. 50,000").orElseThrow();

        assertThat(fields.payPeriodStart()).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(fields.payPeriodEnd()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(fields.netAmount().amount()).isEqualTo("50000.00");
        assertThat(fields.grossAmount()).isNull();
        assertThat(fields.deductions()).isNull();
    }

    /** A labelled employer wins over the first line, and slash dates are understood. */
    @Test
    void readsALabelledEmployerAndSlashDates() {
        PayslipResponse.ParsedFields fields = parser.parseText("""
            Salary Slip
            Company Name: Globex Corporation
            Pay Period: 01/10/2026 - 31/10/2026
            Gross Salary: 90,000
            """).orElseThrow();

        assertThat(fields.employer()).isEqualTo("Globex Corporation");
        assertThat(fields.payPeriodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(fields.payPeriodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(fields.grossAmount().amount()).isEqualTo("90000.00");
    }

    /** Text with neither gross nor net pay is unreadable. */
    @Test
    void textWithoutPayFiguresIsNotAPayslip() {
        assertThat(parser.parseText("Meeting notes\nBuy milk")).isEmpty();
    }

    /**
     * A real PDF is read through PDFBox.
     *
     * @throws IOException if the PDF cannot be built or read
     */
    @Test
    void readsARealPdf() throws IOException {
        byte[] bytes = pdf(List.of("Acme Technologies Pvt Ltd", "Pay Period: 01-Sep-2026 to 30-Sep-2026",
            "Gross Earnings 80,000.00", "Net Pay 70,000.00"));

        Optional<PayslipResponse.ParsedFields> fields =
            parser.parse(new ByteArrayInputStream(bytes), "application/pdf");

        assertThat(fields).isPresent();
        assertThat(fields.get().grossAmount().amount()).isEqualTo("80000.00");
        assertThat(fields.get().netAmount().amount()).isEqualTo("70000.00");
        assertThat(fields.get().employer()).isEqualTo("Acme Technologies Pvt Ltd");
    }

    /**
     * Images are not read (no OCR), and a corrupt PDF raises an I/O error for the caller to record.
     *
     * @throws IOException never; declared by the parser contract
     */
    @Test
    void skipsImagesAndRejectsCorruptPdfs() throws IOException {
        assertThat(parser.parse(new ByteArrayInputStream(new byte[] {1, 2, 3}), "image/png")).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
            () -> parser.parse(new ByteArrayInputStream("%PDF-1.4 broken".getBytes()), "application/pdf"));
    }
}
