# MongoDB Tool

MongoDB NoSQL database operations.

## Tool ID
`mongodb`

## Credential Required
Yes — MongoDB connection.

### Credential Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `connectionString` | string | — | **Required.** MongoDB connection string (e.g., `mongodb://user:pass@host:27017`, `mongodb+srv://...`) |
| `database` | string | — | Default database name |

Credential test uses the `ping` command against the `admin` database.

## Operations

### Common Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `operation` | string | — | **Required.** MongoDB operation |
| `database` | string | credential default | Database name |
| `collection` | string | — | **Required.** Collection name |
| `filter` | string | `{}` | Query filter as JSON |

### 1. `find` — Query documents

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `{}` | Query filter JSON |
| `projection` | string | — | Fields to include/exclude: `{"name": 1, "_id": 0}` |
| `sort` | string | — | Sort order: `{"date": -1}` |
| `limit` | integer | `0` | Max documents (0 = all) |

**Published:** `documentCount`, `documents` (JSON array)

### 2. `insert` — Insert documents

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `document` | string | — | **Required.** Single JSON document or JSON array for batch insert |

**Published:** `insertedCount`

**Behavior:** Detects arrays automatically — `[{...}, {...}]` triggers `insertMany`.

### 3. `update` — Update documents

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `{}` | Filter for documents to update |
| `update` | string | — | **Required.** Update operations: `{"$set": {"status": "done"}}` |

**Published:** `matchedCount`, `modifiedCount`

**Behavior:** Always uses `updateMany` — updates all matching documents.

### 4. `delete` — Delete documents

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `{}` | Filter for documents to delete |

**Published:** `deletedCount`

**Behavior:** Always uses `deleteMany`.

### 5. `count` — Count documents

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `{}` | Filter for counting |

**Published:** `count`

### 6. `aggregate` — Run aggregation pipeline

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `pipeline` | string | — | **Required.** Aggregation pipeline as JSON array: `[{"$match": {...}}, {"$group": {...}}]` |

**Published:** `documentCount`, `documents` (JSON array)

### 7. `export` — Export query results to file

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `{}` | Query filter |
| `limit` | integer | `0` | Max documents |
| `outputFile` | string | — | **Required.** Output JSON file path |

**Published:** `documentCount`, `outputFile`

## Killable
Yes — closes the MongoDB client.

## Technology
Official MongoDB Java Driver (`com.mongodb.client`).

## Chaining Patterns

- **MongoDB export → Email** — export data, email the JSON file
- **MongoDB find → Runtime Env** — query data, process with Python
- **SQL export → MongoDB insert** — migrate relational data to MongoDB
- **MongoDB aggregate → PDF** — run analytics, generate PDF report

## Filter Examples

```json
{"status": "active"}
{"age": {"$gte": 18}}
{"$and": [{"status": "active"}, {"role": "admin"}]}
{"tags": {"$in": ["urgent", "critical"]}}
{"createdAt": {"$gte": {"$date": "2024-01-01T00:00:00Z"}}}
```
