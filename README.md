# invoice-pdf-service

A production-ready Spring Boot REST API that generates **multilingual invoice PDFs** dynamically.

Send invoice data as JSON — get back a fully formatted, print-ready PDF. No third-party SaaS, no cloud dependency, everything runs on your own server.

Built with **Apache FOP** (XSL-FO → PDF) and a **FreeMarker template** for clean separation between layout and data. Supports **9 Indian language scripts** out of the box.

---

## Why this exists

Most invoice generation solutions either:
- Lock you into a paid SaaS (Invoicely, DocRaptor, etc.)
- Only support English / Latin fonts
- Mix layout logic into application code, making design changes painful

This service solves all three — fully self-hosted, multilingual, and the invoice layout lives in a single editable template file.

---

## Features

- Single REST endpoint — `POST /api/invoice/generate` returns a PDF binary
- **FreeMarker template** — invoice layout is a `.ftl` file, not Java strings. Change the design without touching Java code
- **Multilingual** — renders Bengali, Devanagari, Gujarati, Kannada, Malayalam, Odia, Tamil, Telugu, and Latin scripts in the same document
- **Input validation** — rejects incomplete requests with clear `400 Bad Request` messages
- **Global error handling** — no stack traces leak to the client
- **OpenAPI / Swagger UI** — interactive docs at `/swagger-ui.html`
- Fonts bundled inside the jar — no system font installation needed

---

## Tech stack

| Layer           | Technology                          |
|-----------------|-------------------------------------|
| Framework       | Spring Boot 3.4.1 (Java 17)         |
| PDF engine      | Apache FOP 2.10 (XSL-FO → PDF)     |
| Template engine | FreeMarker                          |
| Validation      | Spring Validation (`jakarta.validation`) |
| API docs        | SpringDoc OpenAPI (Swagger UI)      |
| Build           | Maven                               |
| Fonts           | Noto Serif family (Google Fonts)    |

---

## Architecture

```
POST /api/invoice/generate
           │
           ▼
  InvoiceController           validates request (@Valid)
           │
           ▼
  InvoiceService
    │
    ├── renderTemplate()       FreeMarker fills data into invoice-template.ftl
    │         │
    │         ▼
    │   XSL-FO XML string      (layout from template, data from request)
    │
    └── renderToPdf()          Apache FOP converts XSL-FO → PDF bytes
           │
           ▼
  HTTP Response                Content-Type: application/pdf
                               Content-Disposition: attachment
```

**Key design decision — FreeMarker template:**
The invoice layout (`invoice-template.ftl`) is a resource file, not Java code.
A designer can change fonts, spacing, column widths, or add new sections by editing the `.ftl` file — no recompile needed, no Java knowledge required.

---

## Project structure

```
src/
└── main/
    ├── java/com/pdfconverter/
    │   ├── PdfApplication.java
    │   ├── controller/
    │   │   └── InvoiceController.java       # REST endpoint
    │   ├── service/
    │   │   └── InvoiceService.java          # Template rendering + FOP
    │   ├── model/
    │   │   ├── InvoiceRequest.java          # Full request payload
    │   │   ├── InvoiceItem.java             # One line item
    │   │   └── PaymentTransaction.java      # One payment record
    │   └── exception/
    │       └── GlobalExceptionHandler.java  # @ControllerAdvice error handler
    └── resources/
        ├── application.properties
        ├── templates/
        │   └── invoice-template.ftl         # Invoice layout lives here
        └── fonts/                           # 18 bundled .ttf files
            ├── NotoSerif-Regular.ttf
            ├── NotoSerif-Bold.ttf
            └── NotoSerifBengali-Regular.ttf
            └── ...
```

---

## Getting started

### Prerequisites

- Java 17+
- Maven 3.8+

### Run locally

```bash
git clone https://github.com/<your-username>/invoice-pdf-service.git
cd invoice-pdf-service
./mvnw spring-boot:run
```

Server starts at `http://localhost:8080`
Swagger UI at `http://localhost:8080/swagger-ui.html`

---

## API

### `POST /api/invoice/generate`

Generates and returns a PDF invoice.

| Property        | Value                    |
|-----------------|--------------------------|
| URL             | `/api/invoice/generate`  |
| Method          | `POST`                   |
| Request body    | `application/json`       |
| Response        | `application/pdf`        |
| On success      | `200 OK` + PDF binary    |
| On bad input    | `400 Bad Request` + JSON error details |
| On server error | `500 Internal Server Error` + JSON message |

---

### Request body

```json
{
  "providerName":    "KALA KUL INTERNATIONAL",
  "providerAddress": "14-B/45, Dev Nagar, New Delhi 110005, India",
  "logoUrl":         "https://example.com/logo.png",

  "orderNumber":     "00000088",
  "receiptNumber":   "REC-2024-001",
  "invoiceDate":     "13-Dec-2024",

  "payerName":       "Muskan Gupta",
  "payerContact":    "9123456789",
  "payerEmail":      "muskan@example.com",
  "shippingAddress": "Satna, Mizoram, India - 485001",
  "billingAddress":  "Satna, Mizoram, India - 485001",

  "items": [
    {
      "description":  "ಶ್ರೀ ಕನಕದಾಸರ ನಳ ಚರಿತ್ರೆ | (6332)",
      "unitPrice":    "₹ 50",
      "quantity":     1,
      "netAmount":    "₹ 50",
      "taxRate":      "0%",
      "taxType":      "CGST + SGST",
      "taxAmount":    "₹ 0",
      "totalAmount":  "₹ 50"
    }
  ],

  "subtotal":        "₹ 50",
  "deliveryCharges": "₹ 100",
  "totalTax":        "₹ 0",
  "total":           "₹ 150",

  "transactions": [
    {
      "transactionId": "323de474-716a-4df4-b1d1-5",
      "dateTime":      "13-Dec-2024, 3:50 pm",
      "invoiceValue":  "₹ 150",
      "paymentMode":   "PayU"
    }
  ]
}
```

All string fields marked with `@NotBlank` will return a `400` if missing or empty.
See Swagger UI for the full validation rules.

---

### Try it with cURL

```bash
curl -X POST http://localhost:8080/api/invoice/generate \
  -H "Content-Type: application/json" \
  -d @sample-request.json \
  --output invoice.pdf
```

A `sample-request.json` with the above payload is included in the repo root.

---

### Error response format

All errors return a consistent JSON body:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "payerName must not be blank",
  "path": "/api/invoice/generate",
  "timestamp": "2024-12-13T15:30:00"
}
```

---

## Multilingual support

Any text field (product name, address, payer name) can be written in any supported script.
Apache FOP picks the correct font automatically via a fallback chain.

| Script     | Font                | Languages              |
|------------|---------------------|------------------------|
| Latin      | NotoSerif           | English                |
| Bengali    | NotoSerifBengali    | Bengali                |
| Devanagari | NotoSerifDevanagari | Hindi, Marathi, Sanskrit |
| Gujarati   | NotoSerifGujarati   | Gujarati               |
| Kannada    | NotoSerifKannada    | Kannada                |
| Malayalam  | NotoSerifMalayalam  | Malayalam              |
| Odia       | NotoSerifOriya      | Odia                   |
| Tamil      | NotoSerifTamil      | Tamil                  |
| Telugu     | NotoSerifTelugu     | Telugu                 |

---

## Customising the invoice layout

The invoice design is fully controlled by `src/main/resources/templates/invoice-template.ftl`.

It is a standard FreeMarker template with XSL-FO markup:
- `${orderNumber}` — simple field substitution
- `<#list items as item>` — loop over line items
- `<#if logoUrl?has_content>` — conditional sections

No Java changes required to:
- Adjust fonts, sizes, or spacing
- Add / remove columns from the items table
- Add a new section (e.g. GST breakdown, signature block)
- Change the page size or margins

---

## Running tests

```bash
./mvnw test
```

Test coverage includes:
- `InvoiceService` unit tests with mocked `InvoiceRequest` data
- Validation tests — verifying `400` responses for missing required fields
- Happy path test — verifying a non-empty PDF is returned

---

## License

[MIT](LICENSE)
