# LDAP Tool

LDAP and Active Directory operations.

## Tool ID
`ldap`

## Credential Required
Yes — LDAP server connection.

### Credential Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ldapUrl` | string | — | **Required.** LDAP URL (e.g., `ldap://server:389` or `ldaps://server:636`) |
| `bindDn` | string | — | **Required.** Bind DN (e.g., `cn=admin,dc=company,dc=com`) |
| `bindPassword` | string | — | **Required.** Bind password |
| `baseDn` | string | — | Default base DN for searches |

LDAPS (SSL) is auto-detected from the URL scheme.

## Operations

### Common Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `operation` | string | — | **Required.** LDAP operation |
| `baseDn` | string | credential default | Base DN (overrides credential default) |
| `attributes` | string | — | Comma-separated attribute names to return |

### 1. `search` — Search for entries

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `(objectClass=*)` | LDAP search filter |
| `scope` | string | `subtree` | Search scope: `subtree`, `onelevel`, `base` |
| `maxResults` | integer | `100` | Maximum entries to return |

**Published Outputs:**
- `entryCount` — number of entries found
- `entries` — JSON array of entries, each with `dn` and all requested attributes

**Filter Examples:**
- `(objectClass=person)` — all person entries
- `(&(objectClass=user)(department=Engineering))` — engineering users
- `(cn=John*)` — entries with CN starting with "John"
- `(|(mail=*@example.com)(mail=*@corp.com))` — entries with specific email domains

### 2. `get` — Retrieve a specific entry

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `dn` | string | — | **Required.** Distinguished name of the entry |

**Published:** `entry` — JSON object with DN and all attributes

### 3. `count` — Count matching entries

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filter` | string | `(objectClass=*)` | LDAP search filter |

**Published:** `count` — number of matching entries

### 4. `modify` — Update entry attributes

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `dn` | string | — | **Required.** DN of entry to modify |
| `modifications` | array | — | **Required.** Array of `{attribute, value, operation}` |

**Modification operations:** `add`, `remove`, `replace` (default)

**Published:** `modifiedDn`, `modificationCount`

## Killable
Yes — closes the LDAP connection.

## Technology
javax.naming (JNDI) — built into the JDK.

## Chaining Patterns

- **LDAP search → Email** — find users, email a report
- **LDAP search → SQL** — sync directory data to a database
- **LDAP count → conditional** — check user counts for compliance
- **LDAP modify → LDAP get** — update an entry, verify the change

## Use Cases

- User provisioning and deprovisioning
- Active Directory group membership management
- Directory synchronization
- Compliance reporting (user counts, attribute audits)
- Password reset automation (via modify)
