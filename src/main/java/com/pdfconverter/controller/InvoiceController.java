package com.pdfconverter.controller;

import com.pdfconverter.model.InvoiceRequest;
import com.pdfconverter.service.InvoiceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoice")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /**
     * POST /api/invoice/generate
     * Accepts invoice data as JSON and returns the generated PDF as a binary download.
     */
    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> generateInvoice(@RequestBody InvoiceRequest request) {
        byte[] pdf = invoiceService.generateInvoice(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment",
                "invoice-" + request.orderNumber() + ".pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .body(pdf);
    }
}
