package com.pdfconverter.model;

import java.util.List;

/**
 * Represents everything needed to generate one invoice PDF.
 *
 * Using a Java 17 record keeps this class immutable and boilerplate-free.
 * Jackson deserializes the incoming JSON body directly into this record.
 */
public record InvoiceRequest(

        // ── Service-provider details (printed top-left and in the footer) ──
        String providerName,
        String providerAddress,
        String logoUrl,           // publicly accessible image URL; can be empty

        // ── Order / receipt meta ──
        String orderNumber,
        String receiptNumber,
        String invoiceDate,

        // ── Payer / customer details ──
        String payerName,
        String payerContact,
        String payerEmail,
        String shippingAddress,
        String billingAddress,

        // ── Line items ──
        List<InvoiceItem> items,

        // ── Financial summary ──
        String subtotal,
        String deliveryCharges,
        String totalTax,
        String total,

        // ── Payment transactions ──
        List<PaymentTransaction> transactions

) {}
