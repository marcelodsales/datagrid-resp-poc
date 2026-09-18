# Data Grid 8.6 Security and Cross-Site Replication Insights

Source: Security Guide + Cross-Site Replication Guide (official Red Hat Data Grid 8.6 PDFs)

## 1. RBAC (Role-Based Access Control)

### Built-in Roles (from most to least privileged)

| Role | Cache Manager Permissions | Cache Permissions |
|------|--------------------------|-------------------|
| admin | ALL | ALL |
| deployer | ALL (except ADMIN) | ALL |
| application | READ, WRITE, EXEC, LISTEN, BULK_READ, BULK_WRITE, CREATE, MONITOR | ALL (except ADMIN) |
| observer | READ, MONITOR | READ, MONITOR |
| monitor | MONITOR | MONITOR |

### Cache Manager Permissions
ALL, LIFECYCLE, LISTEN, ADMIN, MONITOR, CREATE, BULK_READ, BULK_WRITE, EXEC, READ, WRITE

### Cache Permissions
ALL, LIFECYCLE, LISTEN, ADMIN, MONITOR, BULK_READ, BULK_WRITE, EXEC, READ, WRITE, CREATE, NONE

### Role Mappers
- **cluster-role-mapper**: Stores principal-to-role mappings in cluster. Dynamic, changeable at runtime via CLI/REST/JMX. Default.
- **identity-role-mapper**: Uses principal name as role name directly.
- **common-name-role-mapper**: Uses Common Name (CN) from client certificates as role name.

### Permission Mappers
- **cluster-permission-mapper**: Stores permission mappings in cluster registry. Allows custom roles at runtime.
- **identity-permission-mapper**: Uses individual principal names. Must be serializable for cluster-wide replication.

### Cache-Level Authorization
Authorization can be configured per cache with explicit role restrictions:

```xml
<distributed-cache name="secured">
  <security>
    <authorization roles="application observer"/>
  </security>
</distributed-cache>
```

When `<authorization>` is set at cache-container level, caches inherit implicitly. Adding explicit `roles=` to a cache restricts which roles can access that specific cache.

Setting `group-only-mapping="true"` (default): only group principals match roles. Set to `false` to also match user principals.

## 2. Security Realms

### Property Realms
- File-based: `users.properties` (passwords) + `groups.properties` (role mapping)
- Multiple property realms supported with different `groups-attribute` names
- Users file format: `username=password` (passwords stored as DIGEST-MD5 hash by default)

### LDAP Realms
- Connects to LDAP/Active Directory for authentication
- Supports connection pooling: `connection-pool-size`, `max-pool-size`, `initial-pool-size`
- Attribute mapping: `attribute` (direct) and `attribute-reference` (search-based)
- Supports LDIF (LDAP Data Interchange Format) for data import
- **Cannot** use LDAP with DIGEST or SCRAM auth mechanisms (they require specific hashed passwords)

### Token Realms (OAuth2)
- Integrates with Red Hat SSO / Keycloak via OAuth2 token introspection
- Configuration: `oauth2-introspection` with `client-id`, `client-secret`, `introspection-url`, `host-name-verification-policy`
- TLS can be configured for the introspection endpoint connection

### Trust Store Realms
- Authenticates clients via certificates in a trust store
- Two modes: CA-only trust store (any cert signed by CA) or full cert trust store (only specific certs)

### Distributed Security Realms
- Combines multiple realm types; tries each in sequence until one authenticates
- Useful for mixed environments (e.g., some users in LDAP, others in properties)

### Aggregate Security Realms
- Uses one realm for authentication and another for authorization
- Example: authenticate via LDAP, get roles from properties file

### Security Realm Caching
- Realms cache authentication/authorization results to avoid repeated external lookups
- Default: 256 entries maximum, 1 hour maximum age
- Configurable: `realms-cache-max-size`, `realms-cache-lifespan`

## 3. Endpoint Authentication Mechanisms

### Per Endpoint Type

| Protocol | Realm Type | Auth Mechanism |
|----------|-----------|----------------|
| Hot Rod (SASL) | Property/LDAP | SCRAM, DIGEST |
| Hot Rod (SASL) | Token | OAUTHBEARER |
| Hot Rod (SASL) | Trust store | EXTERNAL |
| Hot Rod (SASL) | Kerberos | GSSAPI, GS2-KRB5 |
| Hot Rod (SASL) | SSL/TLS | PLAIN |
| REST (HTTP) | Property/LDAP | DIGEST |
| REST (HTTP) | Token | BEARER_TOKEN |
| REST (HTTP) | Trust store | CLIENT_CERT |
| REST (HTTP) | Kerberos | SPNEGO |
| REST (HTTP) | SSL/TLS | BASIC |
| **RESP** | **Property/LDAP only** | **Yes (password-based only)** |
| RESP | Token/Trust/Kerberos/SSL | **No** |

### RESP Authentication Limitation
RESP endpoints support authentication ONLY with security realms that use password-based authentication (property realms and LDAP realms). No certificate-based, token-based, or Kerberos auth for RESP.

### SASL Quality of Protection (QoP)
- `auth`: Authentication only
- `auth-int`: Authentication + integrity
- `auth-conf`: Authentication + integrity + privacy (encryption)

### SASL Policies
Fine-grained control over which mechanisms are allowed:
- `forward-secrecy`, `pass-credentials`, `no-plain-text`, `no-active`, `no-dictionary`, `no-anonymous` (default: true)

### Disabling Authentication
For development only: remove `security-realm` from endpoints and remove `authorization` from caches.

## 4. TLS/SSL Encryption

### Server Keystores
- Supported formats: JKS, JCEKS, PKCS12/PFX, PEM, BKS, BCFKS
- PEM files can be used as keystores if they contain a private key (PKCS#1 or PKCS#8) and certificates
- Certificates should include `subjectAltName` extension (dNSName and/or iPAddress) for hostname validation
- Production: must be signed by trusted CA (Root or Intermediate)
- Keystore placed in `$RHDG_HOME/server/conf`

### Certificate Rotation
- Data Grid monitors keystore files and automatically reloads on change (no restart needed)
- Use CA-signed certs for seamless rotation; self-signed causes temporary handshake failures

### Auto-Generated Keystores
- `generate-self-signed-certificate-host` attribute on keystore element
- **Not for production** — generated on demand, suitable for development only

### TLS Versions and Cipher Suites
- Default: TLS 1.2 and 1.3
- TLSv1.3 recommended if restricting to single version
- TLSv1.1 and below: not recommended, never enable < 1.1
- Configurable: `enabled-protocols`, `enabled-ciphersuites` (TLS 1.2), `enabled-ciphersuites-tls13` (TLS 1.3)
- If SSL engine is modified, `enabled-protocols` must be explicitly set

### Client Certificate Authentication (Mutual TLS)
- `require-ssl-client-auth="true"` on endpoint configuration
- Trust store with CA cert or all client certs must be in `$RHDG_HOME/server/conf`
- `<truststore-realm/>` element authenticates each client certificate individually
- Hot Rod uses SASL EXTERNAL mechanism, REST uses CLIENT_CERT
- CN (Common Name) from certificate maps to roles via `common-name-role-mapper`
- `authorization.group-only-mapping` must be `false` when using CN-based role mapping

## 5. FIPS 140-2 Compliance

### PKCS11 (SunPKCS11-NSS-FIPS)
- Uses NSS database for certificate storage
- Requires: system in FIPS mode, NSS database initialized, JDK `java.security` configured for SunPKCS11

### Bouncy Castle FIPS (BCFIPS)
- Uses BCFKS keystore format
- Requires: Bouncy Castle FIPS JAR in `server/lib`, system in FIPS mode
- Install via CLI: `install org.bouncycastle:bc-fips:1.0.2.3`

## 6. Credential Keystores

### Purpose
Store sensitive credentials (passwords for databases, LDAP, etc.) encrypted instead of plain-text in configuration files.

### Creating
```bash
bin/cli.sh credentials add dbpassword -c changeme -p "secret1234!"
```

### Password Protection Options
- **Clear text**: `<clear-text-credential clear-text="secret1234!"/>`
- **Masked (PBE)**: `credentials mask -i 100 -s pepper99 "secret1234!"` → `<masked-credential masked="..."/>`
- **External command**: `<command-credential command="/path/to/executable.sh arg1 arg2"/>`

### Referencing in Config
```xml
<credential-reference store="credentials" alias="dbpassword"/>
```
Used in: data-source connections, LDAP realm connections, any external system authentication.

## 7. Cluster Transport Encryption

### TLS Identities
- Separate security realm for cluster transport (do NOT share with endpoint realm)
- Keystore in `$RHDG_HOME/server/conf`
- Configure via `server:security-realm` attribute on `<transport>` element
- Encrypts JGroups messages including RELAY2 (cross-site replication)

### JGroups Encryption Protocols

| Protocol | Type | Key Distribution | Certificate Auth | Auto Key Rotation |
|----------|------|-----------------|-----------------|-------------------|
| ASYM_ENCRYPT | Asymmetric | Coordinator node generates and distributes | Yes (MitM protection) | Yes (configurable) |
| SYM_ENCRYPT | Symmetric | Shared keystore on all nodes | No | No (manual) |

- **ASYM_ENCRYPT**: More secure, adds `SSL_KEY_EXCHANGE` + `ASYM_ENCRYPT` to JGroups stack. Coordinator generates secret key, joining nodes authenticate via certificate then receive encrypted key. Can force key regeneration on membership change.
- **SYM_ENCRYPT**: Faster (no coordinator exchange), but no auto key rotation on membership change. All nodes must have same keystore pre-installed.
- Both use `stack.combine="INSERT_AFTER"` and `stack.position` to inject into existing JGroups stack.

## 8. Cross-Site Replication

### Architecture
- Clusters at different geographic locations replicate data via JGroups RELAY2 protocol
- Relay nodes handle inter-cluster communication (recommend all nodes as relay for performance)
- Uses separate JGroups stack: local cluster = UDP (default), cross-site = TCP (bridge stack)
- Each site has a name (e.g., LON, NYC) configured in RELAY2

### Backup Strategies
- **Synchronous** (`strategy=sync`): Waits for remote write to complete. Strongest consistency. Higher latency. Supports two-phase commit for optimistic transactions. WARNING: deadlock if same key written concurrently across clusters.
- **Asynchronous** (`strategy=async`): Does not wait. No latency impact. Retries on failure. Recommended for Active/Active.

### Automatic Offline Parameters
- `after-failures`: Number of consecutive failures before taking backup offline (default behavior)
- `min-wait`: Minimum time to wait after first failure before going offline (1-2 minutes recommended)
- Both conditions must be met if both configured

### State Transfer
- Bidirectional: push from any site to any other
- Overwrites existing data, does NOT delete data
- Does NOT overwrite updates that occur after transfer starts
- Automatic state transfer: only with async strategy, triggered when backup comes back online
- Manual: via CLI, REST API, or JMX
- Config: `chunk-size`, `timeout`, `max-retries`, `wait-time`, `mode` (AUTO or MANUAL)

### Client Connection Patterns
- **Active/Passive**: One cluster handles all client requests. Backup receives replicated data. Failover: clients switch to backup.
- **Active/Active**: Both clusters handle requests simultaneously. Async backup recommended. Conflict resolution via vector clocks.

### Conflict Resolution (Active/Active)
- Vector clocks detect conflicting entries
- Default resolution: lexicographic comparison of cluster names (AAA wins over AAB)
- Tip: prepend numbers to cluster names for explicit priority (1LON, 2NYC)
- Only works with async backup strategy
- Built-in merge policies: `ALWAYS_REMOVE`, `PREFER_NON_NULL`, `PREFER_NULL`
- Custom merge: implement `XSiteEntryMergePolicy` SPI

### Expiration with Cross-Site
- `lifespan`: Clusters expire entries independently
- `max-idle`: Clusters coordinate via touch commands (performance impact)

### Backing Up to Different Cache Names
- Use `backup-for` / `backupFor` to map remote cache name to local cache name
- Example: "eu-customers" on NYC receives from "customers" on LON

### Cross-Site Operations (CLI / REST / JMX)
- Bring backup locations offline/online
- Push state to backup locations
- Cancel state transfer
- Get/clear state transfer status
- Modify take-offline conditions at runtime
- REST API: `/rest/v2/caches/{cacheName}/x-site/backups/`
- CLI: `site` command
- JMX: `XSiteAdmin` MBeans

## 9. PoC-Relevant Security Decisions

### For the organization Multi-Tenant PoC
1. **RBAC per cache** is the primary mechanism for tenant isolation — each tenant's caches restrict access to specific roles
2. **RESP limitation**: Redis-compatible clients can ONLY authenticate via password (property/LDAP realms). No token or certificate auth. This is a hard constraint for the RESP use case.
3. **TLS is mandatory** in production — auto-generated keystores for dev/PoC, CA-signed for production
4. **Cluster transport encryption** should use a separate security realm from endpoint encryption
5. **Cross-site replication** is relevant if the organization has multiple OpenShift clusters — requires RELAY2 JGroups configuration and per-cache backup definitions
6. **Credential keystores** should be used instead of plain-text passwords in configuration
7. **Property realm** is simplest for PoC; **LDAP realm** or **token realm** (Red Hat SSO) for production integration
