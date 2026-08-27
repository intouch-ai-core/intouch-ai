# ClickHouse Connector

Runs SQL against a ClickHouse cluster over its **HTTP interface** (8123, or 8443 with TLS).

No JDBC driver is bundled. ClickHouse's HTTP interface covers queries, inserts and format
conversion, so the connector needs no vendor driver — which keeps the JAR at ~4 MB and keeps it
clear of driver/classloader conflicts.

## Credential

Standard credential fields carry the connection; two extra properties are type-specific.

| Field | Description |
|-------|-------------|
| Server | ClickHouse host name or address — **required** |
| Port | Leave `0` for the default: 8123, or 8443 when SSL is enabled |
| Login | ClickHouse user (defaults to `default`) |
| Secret | Password (may be blank on an unsecured dev cluster) |
| `database` | Default database for statements that don't name one (default `default`) |
| `ssl` | Connect over HTTPS — required by ClickHouse Cloud (default `false`) |

**Test** runs `SELECT version()`, so a green result means the credentials authenticate and the
database is reachable — not merely that the port accepts connections.

## Operations

### 1. `execute` — Run SQL

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `statement` | string | — | **Required.** SQL statement |
| `maxRows` | integer | `0` | Rows to publish (0 = up to 1000) |
| `settings` | string | — | Extra ClickHouse settings as a JSON object |

**Published (queries):** `rows`, `columns`, `rowCount`, `rowsPublished`, `truncated`

`rows` is a JSON array of objects keyed by column name, with values as strings (null stays null) —
every task output is text.

`rowCount` is the true number of rows returned; `rowsPublished` is how many are in `rows`, and
`truncated` says whether they differ. Publishing is capped at `maxRows` (or 1000) because outputs are
held in memory and written to the activity log — use `export` for large result sets.

DDL and DML statements simply run.

### 2. `export` — Query results to file

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `statement` | string | — | **Required.** SQL SELECT statement |
| `outputFile` | string | — | **Required.** Output file path |
| `delimiter` | string | `comma` | `comma`, `tab`, `pipe`, `other` |
| `customDelimiter` | string | — | Required when `delimiter` is `other` |
| `hasHeader` | boolean | `true` | Include column header |
| `maxRows` | integer | `0` | Max rows (0 = all) |
| `nullFormat` | string | — | String for null values |

**Published:** `rowCount`, `outputFile`

**Behavior:** ClickHouse does the formatting and the response is streamed straight to disk, so the
result never has to fit in memory and the quoting is the server's own — values containing the
delimiter, quotes or line breaks are escaped correctly.

### 3. `import` — Load file into table

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `inputFile` | string | — | **Required.** Input file path |
| `table` | string | — | **Required.** Target table |
| `database` | string | credential default | Target database |
| `delimiter` | string | `comma` | Field delimiter |
| `hasHeader` | boolean | `true` | File has a header row |
| `nullFormat` | string | — | String that represents null |

**Published:** `rowCount`, `inputFile`, `database`, `table`

**Behavior:** The file is sent as the body of an `INSERT` and parsed by ClickHouse. With a header
row, columns are matched to the table **by name** (`input_format_with_names_use_header`), so the
file's column order does not need to match the table's. Without a header, values are taken in the
table's own column order.

`rowCount` is measured as the table's row count before and after the load. On a table receiving
concurrent writes that figure is approximate.

### 4. `list-databases`

**Published:** `databases` (comma-separated), `count`

### 5. `list-tables`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `database` | string | credential default | Database to list |

**Published:** `database`, `tables` (comma-separated), `count`

### 6. `describe`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `database` | string | credential default | Database |
| `table` | string | — | **Required.** Table |

**Published:** `database`, `table`, `columns`, `columnCount`

## Delimiters

`comma` and `tab` map to ClickHouse's CSV and TSV formats (the `WithNames` variants when a header is
used). `pipe` and `other` use `CustomSeparated` with the delimiter applied as a format setting; when
`other` is chosen a custom delimiter is required.

## Testing status

Built and loaded against InTouch; the credential and task schemas, tool registration and HTTP error
reporting are verified. **The operations have not yet been exercised against a live ClickHouse
server** — no instance was available. Treat `execute`, `export` and `import` as untested until run
against a real cluster.
