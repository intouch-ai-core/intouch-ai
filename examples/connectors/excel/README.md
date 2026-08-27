# Excel Tool

Read and write Microsoft Excel files (.xlsx and .xls).

## Tool ID
`excel`

## Credential Required
No

## Operations

### Common Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `operation` | string | — | **Required.** Operation to perform |
| `inputFile` | string | — | **Required.** Input file path |
| `outputFile` | string | — | Output file path |
| `sheetName` | string | — | Sheet name (default: first sheet) |
| `sheetIndex` | integer | `0` | Sheet index (0-based, used if sheetName is blank) |
| `hasHeader` | boolean | `true` | First row is a header |
| `delimiter` | string | `,` | CSV delimiter |
| `startRow` | integer | `0` | Start row (0-based) |
| `maxRows` | integer | `0` | Maximum rows to read (0 = all) |

### 1. `read` / `excel-to-csv` — Extract Excel data to CSV

Reads a sheet from an Excel file and writes it as delimited text.

**Required:** `inputFile`, `outputFile`

**Published Outputs:**
- `operation`, `rowCount`, `columnCount`, `outputFile`

### 2. `write` / `csv-to-excel` — Create Excel from CSV

Reads a CSV file and creates an Excel workbook. Numeric detection is automatic. Columns are auto-sized.

**Required:** `inputFile`, `outputFile`

**Behavior:**
- Output format determined by file extension: `.xlsx` → XSSF, `.xls` → HSSF
- Numeric values are detected and stored as numbers (not strings)

**Published:** `operation`, `rowCount`, `columnCount`, `outputFile`

### 3. `list-sheets` — List sheets in a workbook

**Required:** `inputFile`

**Published:**
- `sheetCount` — number of sheets
- `sheets` — comma-separated sheet names

## Killable
No

## Technology
Apache POI — supports both `.xlsx` (XSSF/OOXML) and `.xls` (HSSF/BIFF) formats.

## Chaining Patterns

- **SQL export → csv-to-excel** — export query to CSV, convert to Excel
- **excel-to-csv → SQL import** — convert Excel to CSV, import into database
- **Excel read → Email** — extract data, email as attachment
- **FTP download → Excel read** — download spreadsheet, extract data
- **list-sheets → read** — enumerate sheets, then read specific ones

## Limitations
- Row/column range is by position, not cell reference (no A1 notation)
- No formula evaluation — values read as displayed
- No cell formatting (fonts, colors) when writing
- Large files (100K+ rows) may require significant memory
