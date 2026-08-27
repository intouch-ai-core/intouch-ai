# Apache Cassandra Tool

Apache Cassandra NoSQL database operations via the DataStax Java Driver v4.

## Tool ID
`cassandra`

## Credential Required
Yes — Cassandra cluster connection.

### Credential Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `contactPoints` | string | — | Extra cluster nodes, comma-separated (e.g. `node2,node3`). Combined with the credential's **Server** field, which supplies the first contact point — set one or both |
| `port` | integer | `9042` | CQL native transport port |
| `datacenter` | string | `datacenter1` | Local datacenter for token-aware routing |
| `login` | string | — | Username (blank = no auth) |
| `password` | string | — | Password |
| `ssl` | boolean | `false` | Enable SSL/TLS |
| `truststorePath` | string | — | Path to Java truststore (.jks) |
| `truststorePassword` | string | — | Truststore password |
| `consistencyLevel` | string | `LOCAL_QUORUM` | Default consistency: `ANY`, `ONE`, `TWO`, `THREE`, `QUORUM`, `ALL`, `LOCAL_QUORUM`, `EACH_QUORUM`, `SERIAL`, `LOCAL_SERIAL`, `LOCAL_ONE` |

## Operations

### 1. `execute` — Run CQL statement

Executes any CQL statement (DDL, DML, or query).

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `statement` | string | — | **Required.** CQL statement |
| `maxRows` | integer | `0` | Rows to publish (0 = up to 1000) |

**Published (queries):** `rows`, `columns`, `rowCount`, `rowsPublished`, `truncated`
**Published (mutations):** `applied`

`rows` is a JSON array of objects keyed by column name, with values as strings
(null stays null) — every task output is text, and types like `inet`/`blob`/`uuid`
have no natural JSON form.

`rowCount` is the true number of rows the query returned; `rowsPublished` is how
many are in `rows`, and `truncated` says whether they differ. Publishing is capped
at `maxRows` (or 1000) because outputs are held in memory and written to the
activity log — use `export` to move a large result set to a file.

### 2. `export` — Query results to file

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `statement` | string | — | **Required.** CQL SELECT statement |
| `outputFile` | string | — | **Required.** Output file path |
| `delimiter` | string | `comma` | Delimiter: `comma`, `tab`, `pipe`, `other` |
| `customDelimiter` | string | — | Custom delimiter |
| `hasHeader` | boolean | `true` | Include column header |
| `maxRows` | integer | `0` | Max rows (0 = all) |
| `nullFormat` | string | — | String for null values |

**Published:** `rowCount`, `outputFile`

**Behavior:** Values are quoted RFC 4180 style — a field containing the delimiter,
a double quote, or a line break is wrapped in quotes with embedded quotes doubled —
so text columns holding commas stay in one field. `import` reads the same quoting,
so an exported file re-imports faithfully. One limit: a value containing a line
break exports correctly for other consumers but cannot be re-imported, because
`import` reads line by line.

### 3. `import` — Load file into table

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `inputFile` | string | — | **Required.** Input file path |
| `keyspace` | string | — | **Required.** Target keyspace |
| `table` | string | — | **Required.** Target table |
| `delimiter` | string | `comma` | Delimiter |
| `hasHeader` | boolean | `true` | File has header row |
| `batchSize` | integer | `50` | Rows per unlogged batch |
| `maxErrors` | integer | `0` | Max errors before stopping (0 = unlimited) |
| `skipRows` | integer | `0` | Data rows to skip |
| `maxRows` | integer | `0` | Max rows to import (0 = all) |
| `nullFormat` | string | — | String that represents null |
| `timestampFormat` | string | `yyyy-MM-dd HH:mm:ss.SSSZ` | Timestamp parsing format |

**Published:** `rowCount`, `errors`, `inputFile`

**Behavior:** Auto-detects column types from table metadata. Uses unlogged batches for performance. Supports all CQL data types: text, int, bigint, float, double, boolean, uuid, timeuuid, decimal, varint, inet, smallint, tinyint, blob (Base64).

Columns are matched to the table **by header name**, so the file's column order
does not need to match the table's. Table columns with no matching header are
reported and left null. Without a header row, fields are taken in the table's own
column order — which Cassandra reports as partition key, clustering keys, then the
rest alphabetically, so a header is strongly preferred.

An import that loads no rows while rejecting some fails, rather than reporting
success with an error count.

### 4. `list-keyspaces` — List all keyspaces

**Published:** `keyspaces` (comma-separated), `count`

### 5. `list-tables` — List tables in a keyspace

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `keyspace` | string | — | **Required.** Keyspace name |

**Published:** `keyspace`, `tables` (comma-separated), `count`

### 6. `describe` — Get table schema

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `keyspace` | string | — | **Required.** Keyspace name |
| `table` | string | — | **Required.** Table name |

**Published:** `keyspace`, `table`, `partitionKeys`, `clusteringKeys`, `columnCount`

## Killable
Yes — closes the CQL session.

## Technology
DataStax Java Driver v4.17 — production-grade, async-capable, automatic node discovery, token-aware routing.

## Chaining Patterns

- **Cassandra export → Email** — export data, email the file
- **SQL export → Cassandra import** — migrate relational data to Cassandra
- **Cassandra export → SQL import** — migrate Cassandra data to relational DB
- **list-keyspaces → list-tables → describe** — schema discovery chain

## Error Handling
- Import: configurable `maxErrors` threshold before stopping
- Import: per-row error messages logged in debug mode
- Import: type mismatches reported with column index and expected type
- Export: progress logged every 10,000 rows
