package com.pdfconverter.service;

import com.pdfconverter.model.InvoiceItem;
import com.pdfconverter.model.InvoiceRequest;
import com.pdfconverter.model.PaymentTransaction;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.configuration.Configuration;
import org.apache.fop.configuration.DefaultConfigurationBuilder;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URL;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Generates invoice PDFs using Apache FOP (XSL-FO → PDF).
 *
 * Flow:
 *   InvoiceRequest  →  buildFoDocument()  →  FO XML string
 *                   →  renderToPdf()       →  raw PDF bytes
 *
 * Fonts are resolved from the classpath (resources/fonts/) so the app works
 * both when running from an IDE and from a packaged Spring Boot jar.
 */
@Service
public class InvoiceService {

    // Nine NotoSerif variants that together cover all major Indian scripts
    private static final String[] FONT_FAMILIES = {
        "NotoSerif", "NotoSerifBengali", "NotoSerifDevanagari", "NotoSerifGujarati",
        "NotoSerifKannada", "NotoSerifMalayalam", "NotoSerifOriya", "NotoSerifTamil", "NotoSerifTelugu"
    };

    // FOP uses this fallback chain to pick the right glyph for any Indian script character
    private static final String FONT_FAMILY =
        "NotoSerif, NotoSerifBengali, NotoSerifDevanagari, NotoSerifGujarati, " +
        "NotoSerifKannada, NotoSerifMalayalam, NotoSerifOriya, NotoSerifTamil, NotoSerifTelugu";

    // ==========================================================================
    // Public entry point
    // ==========================================================================

    /**
     * Builds and renders the invoice; returns the PDF as a byte array.
     * The caller (controller) writes these bytes directly into the HTTP response.
     */
    public byte[] generateInvoice(InvoiceRequest request) {
        try {
            String foDocument = buildFoDocument(request);
            return renderToPdf(foDocument);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate invoice PDF for order: " + request.orderNumber(), e);
        }
    }

    // ==========================================================================
    // FOP rendering — converts FO XML → PDF bytes
    // ==========================================================================

    private byte[] renderToPdf(String foContent) throws Exception {
        FopFactory fopFactory = createFopFactory();
        ByteArrayOutputStream pdfOutput = new ByteArrayOutputStream();

        try (OutputStream out = new BufferedOutputStream(pdfOutput)) {
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, out);

            // SAXParserFactory is the modern, non-deprecated way to get an XMLReader (replaces XMLReaderFactory)
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            XMLReader xmlReader = spf.newSAXParser().getXMLReader();
            xmlReader.setContentHandler(fop.getDefaultHandler());
            xmlReader.parse(new InputSource(new StringReader(foContent)));
        }
        return pdfOutput.toByteArray();
    }

    private FopFactory createFopFactory() throws Exception {
        FopFactoryBuilder builder = new FopFactoryBuilder(new File(".").toURI());
        DefaultConfigurationBuilder cfgBuilder = new DefaultConfigurationBuilder();
        Configuration cfg = cfgBuilder.build(new ByteArrayInputStream(buildFopConfig().getBytes()));
        builder.setConfiguration(cfg);
        return builder.build();
    }

    // ==========================================================================
    // FOP configuration — registers every font from the classpath
    // ==========================================================================

    private String buildFopConfig() {
        // For each font family, register the Regular and Bold variants
        String fontEntries = Arrays.stream(FONT_FAMILIES)
            .map(family -> fontEntry(family, "Regular", "normal", "normal")
                         + fontEntry(family, "Bold",    "normal", "bold"))
            .collect(Collectors.joining());

        return """
                <fop version="1.0">
                    <renderers>
                        <renderer mime="application/pdf">
                            <fonts>
                                %s
                            </fonts>
                        </renderer>
                    </renderers>
                </fop>
                """.formatted(fontEntries);
    }

    /**
     * Resolves the font file from the classpath and returns one <font> config entry.
     * getResource() returns a URL that works for both file:// (IDE) and jar:// (packaged app).
     */
    private String fontEntry(String family, String variant, String style, String weight) {
        String classpathPath = "fonts/" + family + "-" + variant + ".ttf";
        URL fontUrl = getClass().getClassLoader().getResource(classpathPath);
        if (fontUrl == null) {
            throw new IllegalStateException("Font not found in classpath: " + classpathPath);
        }
        return """
                <font embed-url="%s" kerning="yes" embedding-mode="subset">
                    <font-triplet name="%s" style="%s" weight="%s"/>
                </font>
                """.formatted(fontUrl.toExternalForm(), family, style, weight);
    }

    // ==========================================================================
    // FO document assembly — stitches all sections into one complete document
    // ==========================================================================

    private String buildFoDocument(InvoiceRequest req) {
        // Page definition goes in layout-master-set; actual content goes inside fo:flow
        return """
                <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
                    <fo:layout-master-set>
                        <fo:simple-page-master master-name="invoice"
                                page-height="11in" page-width="9.5in" margin="0.5in">
                            <fo:region-body/>
                        </fo:simple-page-master>
                    </fo:layout-master-set>
                    <fo:page-sequence master-reference="invoice">
                        <fo:flow flow-name="xsl-region-body">
                """ +
                headerSection(req)      +
                addressSection(req)     +
                itemsTable(req)         +
                transactionsTable(req)  +
                footerSection(req)      +
                """
                        </fo:flow>
                    </fo:page-sequence>
                </fo:root>
                """;
    }

    // ==========================================================================
    // Invoice sections — each method builds one logical piece of the invoice
    // ==========================================================================

    /**
     * Top area: logo, two-column header (provider info on the left, payer info on the right).
     */
    private String headerSection(InvoiceRequest req) {
        // Build each column's content first, then slot them into the table template
        String leftColumn =
            block("Payment Receipt",                              "bold",   "15pt", "left") +
            block("Receipt Number: " + xml(req.receiptNumber()), "normal", "13pt", "left") +
            block("Order Number: "   + xml(req.orderNumber()),   "normal", "13pt", "left") +
            spacer() +
            block("Service Provider",               "bold",   "15pt", "left") +
            block(xml(req.providerName()),           "normal", "13pt", "left") +
            block(xml(req.providerAddress()),        "normal", "13pt", "left");

        String rightColumn =
            block("Invoice Date: " + xml(req.invoiceDate()),     "normal", "13pt", "right") +
            spacer() +
            block("Payer",                                        "bold",   "15pt", "right") +
            block(xml(req.payerName()),                           "normal", "13pt", "right") +
            block("Contact: " + xml(req.payerContact()),         "normal", "13pt", "right") +
            block(xml(req.payerEmail()),                          "normal", "13pt", "right");

        // %% becomes a literal % in the output (required for column-width percentages inside formatted())
        return """
                <fo:block space-after="10pt" text-align="center">
                    <fo:external-graphic src="url('%s')" content-width="150px"/>
                </fo:block>
                <fo:table width="100%%" table-layout="fixed">
                    <fo:table-column column-width="49%%"/>
                    <fo:table-column column-width="51%%"/>
                    <fo:table-body>
                        <fo:table-row>
                            <fo:table-cell>%s</fo:table-cell>
                            <fo:table-cell>%s</fo:table-cell>
                        </fo:table-row>
                    </fo:table-body>
                </fo:table>
                """.formatted(xml(req.logoUrl()), leftColumn, rightColumn);
    }

    /**
     * Shipping and billing addresses.
     */
    private String addressSection(InvoiceRequest req) {
        return sectionLabel("Shipping Address:") +
               block(xml(req.shippingAddress()), "normal", "13pt", "left") +
               sectionLabel("Billing Address:") +
               block(xml(req.billingAddress()),  "normal", "13pt", "left");
    }

    /**
     * Line-items table with unit price, quantity, tax breakdown, and total columns.
     * Summary rows (subtotal, delivery, tax, grand total) are appended at the bottom.
     */
    private String itemsTable(InvoiceRequest req) {
        String itemRows = req.items().stream()
            .map(this::itemRow)
            .collect(Collectors.joining());

        String summaryRows =
            summaryRow("Subtotal",           xml(req.subtotal()))        +
            summaryRow("Delivery Charges",   xml(req.deliveryCharges())) +
            summaryRow("Total Tax (incl.)",  xml(req.totalTax()))        +
            summaryRow("Total",              xml(req.total()));

        return sectionLabel("Order Items:") +
               foTable(
                   tableColumns("30%", "8%", "10%", "10%", "10%", "10%", "10%", "12%"),
                   tableHeaders("Description", "Unit Price", "Quantity", "Net Amount",
                                "Tax Rate", "Tax Type", "Tax Amount", "Total Amount"),
                   itemRows + summaryRows
               );
    }

    /**
     * Payment transactions table (transaction ID, date, amount, payment mode).
     */
    private String transactionsTable(InvoiceRequest req) {
        String rows = req.transactions().stream()
            .map(this::transactionRow)
            .collect(Collectors.joining());

        return sectionLabel("Payment Transactions:") +
               foTable(
                   tableColumns("34%", "26%", "25%", "15%"),
                   tableHeaders("Transaction ID", "Date &amp; Time", "Invoice Value", "Payment Mode"),
                   rows
               );
    }

    /**
     * Footer: legal disclaimer, provider address, and order ID.
     */
    private String footerSection(InvoiceRequest req) {
        return "<fo:block space-before=\"20pt\" font-family=\"" + FONT_FAMILY + "\" font-size=\"13pt\" text-align=\"center\">" +
               "(This is a computer generated receipt and does not require physical signature.)</fo:block>\n" +
               block(xml(req.providerName()) + ", " + xml(req.providerAddress()), "normal", "13pt", "center") +
               block("Order ID: " + xml(req.orderNumber()),                       "normal", "13pt", "center");
    }

    // ==========================================================================
    // Row builders — one method per data type keeps section methods clean
    // ==========================================================================

    private String itemRow(InvoiceItem item) {
        return "<fo:table-row>\n"                   +
               dataCell(xml(item.description()))    +
               dataCell(xml(item.unitPrice()))      +
               dataCell(String.valueOf(item.quantity())) +
               dataCell(xml(item.netAmount()))      +
               dataCell(xml(item.taxRate()))        +
               dataCell(xml(item.taxType()))        +
               dataCell(xml(item.taxAmount()))      +
               dataCell(xml(item.totalAmount()))    +
               "</fo:table-row>\n";
    }

    private String transactionRow(PaymentTransaction t) {
        return "<fo:table-row>\n"                +
               dataCell(xml(t.transactionId())) +
               dataCell(xml(t.dateTime()))      +
               dataCell(xml(t.invoiceValue()))  +
               dataCell(xml(t.paymentMode()))   +
               "</fo:table-row>\n";
    }

    /**
     * A summary row that spans all but the last column (used for Subtotal, Total, etc.).
     */
    private String summaryRow(String label, String value) {
        return "<fo:table-row>\n" +
               "    <fo:table-cell number-columns-spanned=\"7\" padding=\"2pt\">" +
               "<fo:block font-family=\"" + FONT_FAMILY + "\" font-weight=\"bold\" font-size=\"13pt\" text-align=\"right\">" +
               label + "</fo:block></fo:table-cell>\n" +
               "    <fo:table-cell border=\"solid 1pt black\" padding=\"2pt\">" +
               "<fo:block font-family=\"" + FONT_FAMILY + "\" font-size=\"13pt\" text-align=\"center\">" +
               value + "</fo:block></fo:table-cell>\n" +
               "</fo:table-row>\n";
    }

    // ==========================================================================
    // Generic table helpers — reused by both itemsTable() and transactionsTable()
    // ==========================================================================

    /**
     * Wraps columns, a header row, and a body into a bordered FO table.
     */
    private String foTable(String columns, String headers, String body) {
        return "<fo:table width=\"100%\" table-layout=\"fixed\" space-before=\"5pt\" border=\"solid 1pt black\">\n" +
               columns +
               "    <fo:table-header><fo:table-row>\n" + headers + "    </fo:table-row></fo:table-header>\n" +
               "    <fo:table-body>\n" + body + "    </fo:table-body>\n" +
               "</fo:table>\n";
    }

    /** Builds the <fo:table-column> entries from an array of percentage widths. */
    private String tableColumns(String... widths) {
        return Arrays.stream(widths)
            .map(w -> "    <fo:table-column column-width=\"" + w + "\"/>\n")
            .collect(Collectors.joining());
    }

    /** Builds a header row's cells from an array of column labels. */
    private String tableHeaders(String... labels) {
        return Arrays.stream(labels)
            .map(this::headerCell)
            .collect(Collectors.joining());
    }

    // ==========================================================================
    // Low-level FO element helpers
    // ==========================================================================

    /** Generic text block with configurable weight, size, and alignment. */
    private String block(String text, String fontWeight, String fontSize, String textAlign) {
        return "<fo:block font-family=\"" + FONT_FAMILY + "\" font-weight=\"" + fontWeight +
               "\" font-size=\"" + fontSize + "\" text-align=\"" + textAlign + "\">" + text + "</fo:block>\n";
    }

    /** Bold section heading with built-in top spacing (e.g. "Order Items:"). */
    private String sectionLabel(String text) {
        return "<fo:block space-before=\"15pt\" font-family=\"" + FONT_FAMILY +
               "\" font-weight=\"bold\" font-size=\"13pt\">" + text + "</fo:block>\n";
    }

    /** Empty block that adds vertical breathing room between two elements. */
    private String spacer() {
        return "<fo:block space-after=\"10pt\"/>\n";
    }

    /** Bold, centered, bordered table header cell. */
    private String headerCell(String content) {
        return "<fo:table-cell border=\"solid 1pt black\" padding=\"2pt\">" +
               "<fo:block font-family=\"" + FONT_FAMILY + "\" font-weight=\"bold\" font-size=\"13pt\" text-align=\"center\">" +
               content + "</fo:block></fo:table-cell>\n";
    }

    /** Normal, centered, bordered table data cell. */
    private String dataCell(String content) {
        return "<fo:table-cell border=\"solid 1pt black\" padding=\"2pt\">" +
               "<fo:block font-family=\"" + FONT_FAMILY + "\" font-size=\"13pt\" text-align=\"center\">" +
               content + "</fo:block></fo:table-cell>\n";
    }

    /**
     * Escapes XML special characters in any user-supplied string before embedding
     * it into the FO document. Prevents malformed XML if values contain &, <, >, ".
     */
    private String xml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
