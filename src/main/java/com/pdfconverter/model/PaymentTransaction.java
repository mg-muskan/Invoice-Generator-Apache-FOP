package com.pdfconverter.model;

public record PaymentTransaction(
        String transactionId,
        String dateTime,
        String invoiceValue,
        String paymentMode
) {}
