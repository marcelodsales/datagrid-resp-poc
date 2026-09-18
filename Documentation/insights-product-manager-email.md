# Insights from Product Manager Email Exchange

Source: Email thread between Marcelo Sales (masales@redhat.com) and Marcelo Guimaraes (mguimara@ibm.com), Feb 19-23, 2026. Context: Qatar Airways evaluation of Data Grid — questions and expert answers applicable to any enterprise DG engagement.

## State Management and Architecture

- Best practices are scenario-dependent, not generic. Asking for "best practices in an enterprise environment" is too vague — need to know what state is being managed (sessions, tokens, domain data, etc.) [Guimaraes]
- Cannot recommend a deployment architecture without understanding how the customer will use the product [Guimaraes]
- Reference: Performance and Sizing Guide, use-cases section

## Security and Access Control

- Cache-level security, authn/authz: covered in Server Guide (security-realms) and Security Guide
- **Encryption at rest: Data Grid does NOT encrypt data internally.** If encryption is required, data must be encrypted before storing in DG. Features requiring data awareness (indexing, querying) cannot be used on encrypted data [Guimaraes]
- Hot Rod over SSL/TLS: covered in Server Guide (configuring-encryption)

## Capacity Planning and Sizing

- Performance and Sizing Guide section 2 is key for planning
- Section 2.2 has the formula for estimating memory consumption and heap allocation considerations
- Customer pain point (v8.5): limited visibility into runtime capacity usage — monitoring must be set up proactively

## Monitoring and Observability

- OpenTelemetry tracing: covered in Server Guide
- Monitoring services on OpenShift: covered in Operator Guide
- Dashboards: need to check what Red Hat provides out-of-box vs what must be built

## REST and Client Access

- REST is just another exposed endpoint — it lacks HotRod's topology-aware routing, meaning operations may not go straight to the node that handles the required key [Guimaraes]
- Reference: Data Grid REST API guide

## Multi-site / Replication / DR

- **Cannot recommend replication topology without knowing application functional requirements and network topology** [Guimaraes — "super red flag"]
- Cross-site backup strategies: covered in Cross-Site Replication guide
- Required ports: Server Guide (server-ports-protocols) plus JGroups stack ports (varies, configurable)
- **Key warning: understanding the Fallacies of Distributed Computing is mandatory — failing to understand them yields broken DG clusters** [Guimaraes]
- Latency/bandwidth requirements are NOT product requirements — they are application non-functional requirements. Acceptable latency depends on max tolerable response time per application [Guimaraes]

## Integrations and Ecosystem

- Integration support is the responsibility of the integration vendor, not shipped as part of Data Grid [Guimaraes]
- Database integration: no direct database support. Users provide the JDBC driver. DG only executes statements via JDBC [Guimaraes]

## Serialization / Query

- References: Cache Encoding and Marshalling guide, Querying Data Grid Caches guide
- Without an index, queries run slow
- Without a schema in remote deployment, queries won't run at all
- If indexing is set to manual mode, query perceived performance may suffer
- Recommendation: understand exactly what the customer means by "effective query" — file a support case if needed [Guimaraes]

## Benchmarking

- Benchmarks are only useful in the context of the benchmark itself [Guimaraes]
- **Benchmarking DG without proper application context makes no sense** — best approach is a load test simulating real application scenarios including concurrent usage [Guimaraes]
- Reference: Red Hat KB solution 7054626

## Competitive Positioning

- Red Hat does not officially compare against competitors
- Hazelcast: focusing on stream capabilities; strong competitor but overlaps with streaming platforms [Guimaraes]
- Redis: customers usually hit scalability issues — expensive to scale, limited by master-slave distributed model [Guimaraes]
- DevNation Tech Talk by Tristan: "Redis vs Infinispan" recommended as reference
- **Vector support: Infinispan has it but Red Hat chose NOT to make it supported in the product** [Guimaraes]
- Agent memory / AI use cases: beyond DG scope — that's an AI platform problem, not a cache problem [Guimaraes]

## Additional Context (Feb 23 email)

- Qatar Airways is an existing DG user on Java runtimes, looking to expand to .NET Core apps
- Customer wants broader integrations (database, queuing via connector ecosystem)
- Customer wants cross-datacenter replication with client auto-failover and a functional UI (not CLI-based management)
