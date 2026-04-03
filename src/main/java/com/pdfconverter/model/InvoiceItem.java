package com.pdfconverter.model;

public record InvoiceItem(
        String description,
        String unitPrice,
        int quantity,
        String netAmount,
        String taxRate,
        String taxType,
        String taxAmount,
        String totalAmount
) {}
