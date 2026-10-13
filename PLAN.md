# Citi Bike DuckDB API Implementation Plan

## Objective

Turn this repository into a Quarkus REST API that loads every top-level CSV
resource matching `src/main/resources/*.csv` into DuckDB through JDBC and
provides safe, observable analytical endpoints for Citi Bike ride data.

This plan keeps the project small enough for learning while applying
production-minded validation, SQL injection prevention, resource limits,
logging, metrics, health checks, and testing.

The sections through the original completion evidence describe the completed
**Phase 1: bundled CSV analytics API**. **Latest published NYC archive downloader**, below, is the current implemented
extension. **Phase 2: monthly ingestion and durable storage** was proposed on October 10,
2026 and implemented on October 12, 2026. Production now uses persistent storage
and the shared monthly ingestion pipeline. Memory mode remains available for
development/demo tests; the latest-only downloader remains independent.

## Starting Repository (Before Implementation)

- `pom.xml` defines a Java 25 Maven project using DuckDB JDBC `1.5.5.1` and
  JUnit 5.
- `App.java` loads one CSV into a private in-memory DuckDB instance and prints
  sample rows.
- `TableSchemaExample.java` demonstrates JDBC metadata inspection.
- `AppTest.java` verifies that DuckDB reads 1,500 rows from the current CSV.
- `src/main/resources/citibike.csv` contains 1,500 Citi Bike rides and is about
  305 KB.
- There is no Quarkus application, REST layer, configuration model, health
  check, or metrics instrumentation yet.
- The worktree already contains staged and untracked changes. Implementation
  must preserve unrelated existing work.

## CSV Profile

The current CSV has 13 columns:

| Column | DuckDB inference | Target type | Missing values |
| --- | --- | --- | ---: |
| `ride_id` | `VARCHAR` | `VARCHAR NOT NULL` | 0 |
| `rideable_type` | `VARCHAR` | `VARCHAR NOT NULL` | 0 |
| `started_at` | `TIMESTAMP` | `TIMESTAMP NOT NULL` | 0 |
| `ended_at` | `TIMESTAMP` | `TIMESTAMP NOT NULL` | 0 |
| `start_station_name` | `VARCHAR` | nullable `VARCHAR` | 11 |
| `start_station_id` | `DOUBLE` | nullable `VARCHAR` | 11 |
| `end_station_name` | `VARCHAR` | nullable `VARCHAR` | 64 |
| `end_station_id` | `DOUBLE` | nullable `VARCHAR` | 64 |
| `start_lat` | `DOUBLE` | `DOUBLE NOT NULL` | 0 |
| `start_lng` | `DOUBLE` | `DOUBLE NOT NULL` | 0 |
| `end_lat` | `DOUBLE` | nullable `DOUBLE` | 23 |
| `end_lng` | `DOUBLE` | nullable `DOUBLE` | 23 |
| `member_casual` | `VARCHAR` | `VARCHAR NOT NULL` | 0 |

Station IDs must be loaded as text. Values such as `7382.04` are identifiers,
not quantities, even though automatic inference treats them as numbers.

Current data-quality findings:

- The file covers June 1 through June 30, 2023.
- Bike types are 1,351 `classic_bike`, 142 `electric_bike`, and 7
  `docked_bike` rides.
- Rider types are 1,031 `member` and 469 `casual` rides.
- There are 524 distinct start stations and 221 distinct end stations.
- Ride IDs are unique.
- No malformed-width rows, invalid timestamps, negative durations, or
  zero-duration rides were found.
- Median duration is 654 seconds, average duration is 2,606.53 seconds, and
  p95 duration is 3,815 seconds.
- There are 22 rides over 24 hours. The maximum duration is 207,024 seconds.
  These should be reported as outliers rather than silently removed.

## Framework And Dependency Decisions

Use Quarkus `3.39.4`, Java 25, Maven 3.9+, and the existing DuckDB JDBC
`1.5.5.1` dependency. Quarkus has fully supported Java 25 since version 3.31.
Use the Quarkus BOM to manage Quarkus, JUnit, Jackson, Micrometer, and health
extension versions.

Add these primary dependencies:

- `io.quarkus:quarkus-rest-jackson`
- `io.quarkus:quarkus-hibernate-validator`
- `io.quarkus:quarkus-micrometer-registry-prometheus`
- `io.quarkus:quarkus-smallrye-health`
- `org.duckdb:duckdb_jdbc`
- `org.jboss.slf4j:slf4j-jboss-logmanager`
- `io.quarkus:quarkus-junit` for tests
- `io.rest-assured:rest-assured` for REST tests

Use the SLF4J API in application code. Quarkus uses JBoss Log Manager as its
supported logging backend, so do not add raw `logback-classic` alongside it.
The Quarkiverse Logback extension is experimental and its current release was
built against an older Quarkus line. If Logback configuration is strictly
required, first perform a compatibility spike using
`io.quarkiverse.logging.logback:quarkus-logging-logback`; otherwise use SLF4J
routed to the Quarkus backend.

Initial delivery targets JVM mode. Native-image support is a later milestone
because DuckDB loads a native library and needs separate native-image testing.

## DuckDB Lifecycle

Use a named in-memory database, for example:

```text
jdbc:duckdb:memory:citibike
```

Keep one application-scoped anchor connection open for the lifetime of the
application. Create a small bounded pool of duplicate connections for queries.
Do not use plain `jdbc:duckdb:` for each request because that creates an
independent database and native thread pool per connection.

Configure conservative limits:

- A bounded connection count, initially 4.
- A bounded DuckDB thread count, initially 4.
- A configurable memory limit, initially 512 MB.
- A bounded temporary-directory size.
- A query timeout, initially 2 seconds.
- A short connection-acquisition timeout. Return `503 Service Unavailable`
  with `Retry-After` when the query pool is saturated.

All REST methods using blocking JDBC calls must execute on Quarkus worker
threads and be explicitly marked as blocking where appropriate.

## Multi-File CSV Discovery

Discover every top-level resource corresponding to
`src/main/resources/*.csv`. At runtime these are classpath resources, not
ordinary source-tree files, so discovery must work in all of these modes:

- Maven and unit tests.
- Quarkus dev mode.
- Packaged fast JAR execution.

The discovery component must:

1. Match only top-level resources ending in `.csv`.
2. Exclude CSV files in nested resource directories.
3. Return resource names in deterministic lexicographical order.
4. Fail startup when no matching resources are found.
5. Avoid accidentally loading CSV resources from dependency JARs.
6. Expose discovered names as trusted application metadata, never as
   user-controlled paths.

Implement this in `CsvResourceCatalog.java`. If standard classloader
enumeration is not reliable in Quarkus fast JAR packaging, generate a resource
index during Maven's `process-resources` phase and read that index at runtime.
The index must be generated automatically from `src/main/resources/*.csv` so
adding another CSV requires no Java or configuration changes.

## Transactional Loading

At startup, `CitibikeDataLoader` must:

1. Discover all matching CSV resources.
2. Extract each resource to a uniquely named file in a controlled temporary
   directory because DuckDB cannot directly read a CSV nested inside a JAR.
3. Reject unsafe or duplicate resource names.
4. Validate that every file has the expected Citi Bike header.
5. Create an explicit staging table rather than relying on inferred types.
6. Load each CSV through JDBC and add a `source_file VARCHAR NOT NULL` value to
   every row.
7. Validate allowed rider and bike categories, timestamp ordering, coordinate
   ranges, required fields, and parse failures.
8. Detect duplicate `ride_id` values within a file and across files.
9. Abort the complete load if any resource fails validation.
10. Atomically replace the active `rides` table only after all files pass.
11. Record per-file and aggregate row counts.
12. Remove extracted temporary files after DuckDB has materialized the data.

Perform the staging load and table replacement in one JDBC transaction. The
API must never observe a partially loaded data set. The default duplicate
policy is fail-fast rather than silently dropping or overwriting rides.

Add the following configuration:

```properties
citibike.csv.resource-pattern=*.csv
citibike.csv.fail-on-empty=true
citibike.csv.fail-on-duplicate-ride-id=true
citibike.jdbc.query-timeout=2s
citibike.jdbc.max-connections=4
citibike.duckdb.threads=4
citibike.duckdb.memory-limit=512MB
```

## Query Repository

Implement fixed analytical queries for:

1. Total rides and summary duration statistics.
2. Ride counts grouped by hour or day.
3. Counts and percentages by bike type.
4. Member-versus-casual counts and duration statistics.
5. Activity grouped by day of week or hour of day.
6. Most active start and end stations.
7. Popular start-to-end station routes.
8. Round-trip counts.
9. Station imbalance: starts minus ends, useful for rebalancing.
10. Missing-field and duration-outlier data-quality summaries.

Use `date_diff('second', started_at, ended_at)` for ride duration and DuckDB
quantile functions for p50, p90, and p95. Date filtering should use an
inclusive lower bound and exclusive upper bound so a requested end date
includes its complete calendar day.

Store query definitions in `RideSql.java`. Keep JDBC mapping and execution in
`RideAnalyticsRepository.java`; do not put SQL in REST resources.

## REST API

Use `/api/v1` as the base path.

| Endpoint | Parameters | Response |
| --- | --- | --- |
| `GET /rides/summary` | `from`, `to` | Count and duration statistics |
| `GET /rides/timeseries` | dates, `interval`, rider/bike filters | Time buckets and counts |
| `GET /rides/by-bike-type` | dates | Counts and percentages by bike type |
| `GET /rides/by-rider-type` | dates | Rider-type counts and duration statistics |
| `GET /rides/activity` | dates, `groupBy` | Hour-of-day or day-of-week buckets |
| `GET /stations` | dates, `direction`, sorting, pagination | Paginated station activity |
| `GET /routes` | dates, filters, round-trip option, pagination | Paginated popular routes |
| `GET /stations/imbalance` | dates, pagination | Starts, ends, and net imbalance |
| `GET /data-quality` | none | Load metadata, missing data, and outliers |

Date parameters use ISO `yyyy-MM-dd`. Defaults use the loaded data's minimum
and maximum dates. Reject ranges where `from` is after `to` or where a range
exceeds a configurable maximum, initially 366 days.

Pagination defaults to `limit=20` and `offset=0`. Limit must be between 1 and
100. Offset must be non-negative and have a configured upper bound.

Use response records such as:

- `RideSummaryResponse`
- `TimeBucketResponse`
- `BikeTypeSummaryResponse`
- `RiderTypeSummaryResponse`
- `StationActivityResponse`
- `RouteSummaryResponse`
- `StationImbalanceResponse`
- `DataQualityResponse`
- `SourceFileLoadResponse`
- `PageResponse<T>`
- `ApiErrorResponse`

The data-quality response includes files discovered, files loaded, total rows,
per-file row counts, invalid rows, duplicate IDs, missing values, and duration
outlier counts.

Return these status codes consistently:

- `200 OK` for successful results, including empty analytical result sets.
- `400 Bad Request` for invalid dates, enums, sorting, filters, or pagination.
- `404 Not Found` for unknown routes only; aggregate queries do not use 404 for
  empty data.
- `503 Service Unavailable` while data is unavailable or the bounded query
  pool is saturated.
- `500 Internal Server Error` with a generic body for unexpected failures.

## SQL Injection Protection

- Use `PreparedStatement` placeholders for every external value.
- Never concatenate request parameters into SQL.
- Do not expose an endpoint that accepts SQL, expressions, column names, file
  paths, or table names.
- Represent interval, grouping, direction, sort column, and sort order as Java
  enums.
- Map each enum to a predefined query or hard-coded SQL fragment through an
  explicit allowlist.
- Keep CSV paths and resource names internal to the application.
- Apply query timeouts, connection limits, memory limits, and result-size
  limits to reduce denial-of-service risk.
- Return validation errors without echoing raw SQL-like input.

Include injection tests using values such as `' OR 1=1 --`, invalid sort
columns, encoded delimiters, oversized station names, and unexpected enum
values. Tests must prove that these values are either safely bound or rejected.

## Logging

Use `org.slf4j.Logger` and `LoggerFactory` in application code. Log:

- Application startup and shutdown.
- Number and names of discovered CSV resources.
- Per-file load completion, sanitized filename, row count, and duration.
- Aggregate load completion and total rows.
- Query operation name, duration, returned row count, and outcome.
- Validation failures at an appropriate level without stack traces.
- Unexpected JDBC failures with correlation information.

Do not log raw SQL, complete request parameters, ride IDs, arbitrary station
search text, extracted temporary paths, or other untrusted values. Use fixed
operation names rather than dynamic logger names.

## Micrometer Metrics

Use Quarkus's automatic HTTP metrics and add:

- `citibike.jdbc.query.duration` timer, tagged by fixed operation and outcome.
- `citibike.jdbc.query.errors` counter, tagged by fixed operation and error
  category.
- `citibike.jdbc.connections.active` gauge.
- `citibike.csv.files.discovered` gauge.
- `citibike.csv.files.loaded` gauge.
- `citibike.csv.rows` gauge.
- `citibike.csv.load.duration` timer.
- `citibike.csv.load.failures` counter with a bounded reason tag.
- Optional ride totals by the bounded `member_casual` and `rideable_type`
  dimensions.

Do not use filename, station name, ride ID, SQL text, exception message, date,
or arbitrary request value as a metric tag. These would create unbounded metric
cardinality. Expose Prometheus metrics at `/q/metrics` or on the Quarkus
management interface when enabled.

## Health Checks

- Liveness only reports whether the application process is functioning. It
  must remain lightweight and must not execute an analytical query.
- Readiness remains down until every discovered CSV has loaded successfully.
- Readiness verifies the loader state and runs a bounded `SELECT 1` against the
  shared DuckDB instance.
- Readiness includes loaded file and row counts as diagnostic data where safe.

Expose checks through `/q/health/live`, `/q/health/ready`, and `/q/health`.

## Package And File Structure

Use packages under `com.bscllc.learning.citibike`:

```text
src/main/java/com/bscllc/learning/citibike/
  config/CitibikeConfig.java
  db/CsvResourceCatalog.java
  db/DuckDbManager.java
  db/CitibikeDataLoader.java
  dto/ApiErrorResponse.java
  dto/BikeTypeSummaryResponse.java
  dto/DataQualityResponse.java
  dto/PageResponse.java
  dto/RideSummaryResponse.java
  dto/RiderTypeSummaryResponse.java
  dto/RouteSummaryResponse.java
  dto/SourceFileLoadResponse.java
  dto/StationActivityResponse.java
  dto/StationImbalanceResponse.java
  dto/TimeBucketResponse.java
  health/DuckDbReadinessCheck.java
  metrics/CitibikeMetrics.java
  repository/RideAnalyticsRepository.java
  repository/RideSql.java
  resource/ApiExceptionMapper.java
  resource/RideAnalyticsResource.java
  service/RideAnalyticsService.java
  validation/ActivityGroup.java
  validation/BikeType.java
  validation/RiderType.java
  validation/SortDirection.java
  validation/StationDirection.java
  validation/StationSort.java
  validation/TimeInterval.java
```

Also add or update:

```text
pom.xml
src/main/resources/application.properties
src/test/resources/fixtures/*.csv
README.md
```

After equivalent REST and repository behavior is covered, remove or relocate
the old command-line `App.java` and `TableSchemaExample.java` examples.

## Testing Plan

### Unit Tests

- Date range, enum, pagination, and station-name validation.
- Allowlist mapping for every SQL structural option.
- Rejection of unknown sorting and grouping values.
- Resource-name sanitization and deterministic ordering.

### Loader And Repository Tests

- One valid CSV loads successfully.
- Multiple valid CSVs combine into one table.
- Adding a new top-level CSV requires no Java or configuration change.
- Nested CSV resources are not loaded.
- No matching resources causes startup failure.
- Incompatible headers abort the complete load.
- Malformed rows do not leave partially loaded data.
- Duplicate ride IDs within one file or across files cause failure.
- `source_file` identifies the origin of every loaded row.
- Station IDs remain strings.
- Query results and duration percentiles are correct against small synthetic
  fixtures.
- Statement timeout and connection cleanup behavior are verified.

### REST Tests

- Every endpoint's happy path, defaults, filters, pagination, and empty result.
- Invalid input produces a stable `400` response.
- Injection-shaped input cannot alter SQL behavior.
- Saturation or unavailable data produces `503`.
- Unexpected failures do not expose SQL or stack traces.

### Observability Tests

- Readiness stays down until the complete multi-file load succeeds.
- Prometheus output contains HTTP, loader, and query metrics.
- Metrics use only bounded tags.
- Logging does not contain raw malicious input or SQL.

Use small synthetic CSV fixtures for exact analytical assertions. Keep one
integration smoke test against the real resource set and assert stable
invariants such as successful loading, known categories, unique ride IDs, and
the current 1,500-row baseline. This avoids making every test brittle when new
CSV files are added.

## Implementation Order

1. Convert `pom.xml` to a Quarkus build and add managed dependencies and test
   plugins.
2. Add application configuration and typed `CitibikeConfig` validation.
3. Implement classpath CSV discovery and packaged-JAR verification.
4. Implement the named DuckDB lifecycle and bounded connection management.
5. Implement explicit schema creation and transactional multi-file loading.
6. Add loader validation, provenance, duplicate detection, logging, and
   metrics.
7. Add fixed SQL definitions and the JDBC repository.
8. Add services, DTOs, enum allowlists, validation, and exception mapping.
9. Add REST resources and pagination.
10. Add readiness, HTTP metrics, and custom query metrics.
11. Add unit, repository, security, REST, health, and metrics tests.
12. Retire obsolete command-line code and update project documentation.

## Verification Commands

```sh
mvn dependency:tree
mvn clean test
mvn quarkus:dev
mvn package
mvn verify
python3 scripts/verify_resource_discovery.py
java --enable-native-access=ALL-UNNAMED -jar target/quarkus-app/quarkus-run.jar
```

Verify manually with representative requests:

```sh
curl http://localhost:8080/api/v1/rides/summary
curl 'http://localhost:8080/api/v1/rides/timeseries?interval=day'
curl 'http://localhost:8080/api/v1/stations?direction=start&limit=10'
curl http://localhost:8080/api/v1/data-quality
curl http://localhost:8080/q/health/ready
curl http://localhost:8080/q/metrics
```

## Acceptance Criteria

- Every compatible top-level `src/main/resources/*.csv` file is discovered and
  loaded automatically on the next build and startup.
- Adding a compatible CSV requires no Java or configuration change.
- The current single CSV loads exactly 1,500 records.
- Multi-file loading is transactional and never exposes partial data.
- Incompatible files and duplicate ride IDs fail startup with actionable logs.
- Station IDs retain their textual representation.
- All API endpoints return deterministic JSON and enforce validation,
  pagination, resource limits, and query timeouts.
- All external values are bound through prepared statements or rejected by
  explicit enum allowlists.
- No endpoint permits arbitrary SQL or filesystem access.
- Logs use SLF4J and do not expose raw untrusted values or SQL.
- Metrics and readiness accurately reflect multi-file loading and query health.
- `mvn clean test` and `mvn package` succeed on Java 25.
- The packaged application discovers and loads the same CSV resources as dev
  and test modes.

## Risks And Open Decisions

- **Logging backend:** implemented SLF4J with Quarkus JBoss Log Manager. Raw
  Logback Classic was not added because it would compete with Quarkus's
  supported backend; the experimental Quarkiverse adapter remains an option
  for a future compatibility spike.
- **Classpath discovery:** implemented an automatically generated build-time
  index. The catalog resolves the index and CSVs from the application artifact
  using an untransformed resource class's code source, preventing dependency
  indexes or CSVs from shadowing application data. Verified in tests, dev mode,
  and packaged fast-JAR execution.
- **DuckDB concurrency:** many connections do not increase DuckDB parallelism
  and can multiply resource use. Keep connection and thread counts bounded.
- **Native loading:** Java 25 warns unless DuckDB receives
  `--enable-native-access=ALL-UNNAMED`. Include it in JVM deployment settings.
- **Native image:** defer until JVM functionality is complete and DuckDB native
  library behavior is tested explicitly.
- **CSV schema evolution:** this plan requires all files to use the same schema.
  Supporting multiple Citi Bike schema versions would require a separate
  normalization milestone.
- **Timestamp semantics:** source timestamps have no offset. Treat them as
  America/New_York local time and document this in the API.
- **Duration outliers:** retain them by default and expose their count. Any
  exclusion threshold must be an explicit query parameter and prepared value.

## Phase 1 Milestones — Complete

- [x] Quarkus build and configuration
- [x] Classpath `*.csv` discovery
- [x] Packaged-JAR resource verification
- [x] Safe DuckDB lifecycle and bounded concurrency
- [x] Transactional multi-file loading
- [x] Schema, provenance, and duplicate validation
- [x] Analytical JDBC query repository
- [x] Validated REST API
- [x] SQL injection and resource protections
- [x] SLF4J logging
- [x] Micrometer metrics and health checks
- [x] Unit, repository, REST, and security tests
- [x] Packaging and documentation verification

## Phase 1 Completion Evidence — October 10, 2026

The Phase 1 JVM implementation and acceptance criteria are complete. Verification ran
on Java 25.0.2 and Maven 3.9.12:

- `mvn dependency:tree` succeeded and confirmed the Quarkus-managed dependency
  set, DuckDB JDBC `1.5.5.1`, and SLF4J routed through JBoss Log Manager without
  Logback Classic.
- `mvn clean verify` succeeded: 50 unit/API tests and one packaged fast-JAR
  integration test passed with no failures or skipped tests. This includes the
  clean test and packaging phases required by the acceptance criteria.
- The real-data smoke test and fast-JAR test loaded the current 1,500-row
  baseline and confirmed healthy readiness.
- `python3 scripts/verify_resource_discovery.py` verified an isolated build
  with an additional top-level CSV, without Java or configuration changes.
  Both fast-JAR and dev mode loaded 1,501 rows from two files and excluded an
  incompatible nested CSV.
- Synthetic fixtures verify exact duration percentiles, categories, time
  buckets, station activity, routes, round trips, imbalance, and provenance.
- Failure-path tests verify malformed schemas/rows, required fields,
  categorical and coordinate validation, duplicate IDs within/across files,
  duplicate resource names, missing resources, transactional rollback, and
  queries observing only the old table until atomic commit.
- Security and observability tests verify every structural allowlist, prepared
  station-name searches, strict date/number/boolean parsing, empty-page totals,
  real JDBC cancellation, lease cleanup, pool saturation with `503` and
  `Retry-After`, readiness DOWN with liveness UP, bounded metric tags, and
  sanitized logs and correlated JSON errors.

The summary now exposes p90 and round-trip totals. Data quality includes a
computed general invalid-row count. Readiness applies the configured statement
timeout. Resource configuration has validated bounds, and station searches have
an explicit length/control-character limit. README documents the local
America/New_York timestamp semantics, response contracts, configuration, and
repeatable verification commands.

Native-image delivery remains deferred as specified in this plan.

## Latest Published NYC Archive Downloader — Implemented

### Scope And Relationship To The Existing Plan

This is the current implemented extension: discover and download **one latest
published NYC monthly archive**, with durable download state. It is independent
of the bundled CSV analytics pipeline described in README.md and implemented by
`CsvResourceCatalog`, `CitibikeDataLoader`, and `DuckDbManager`. Downloading a ZIP
does not make its rides available through the analytics API.

Phase 2 below is a separate implemented pipeline.
Its previous-complete-month scheduling, Jersey City inclusion, catch-up imports,
historical backfills, nested annual archives, extraction, CSV validation,
DuckDB migration/loading, row counts, and transactional ride replacement do
**not** apply to this downloader. This component requires no analytics storage
change and does not activate Phase 2. Existing endpoints, startup loading,
prepared statements, query limits, metrics, and database readiness remain as
implemented. The earlier documentation-only update is followed by downloader implementation,
configuration, README instructions, and verification. The ingestion extension was subsequently implemented independently.

### Programmatic Discovery And Exact Latest Selection

Use anonymous HTTPS S3 `ListObjectsV2` requests against
`https://s3.amazonaws.com/tripdata?list-type=2&max-keys=1000&encoding-type=url`.
The [archive index](https://s3.amazonaws.com/tripdata/index.html) is a browsing UI;
never scrape its rendered HTML or construct guessed monthly filenames.

Follow every `NextContinuationToken` until `IsTruncated=false`, treating the
continuation token as opaque and URL-encoding it correctly. Use namespace-aware
XML parsing with DTDs and external entities disabled; decode URL-encoded object
keys once. Enforce response-size, page-count, and overall discovery-time limits.
Malformed XML, missing/repeated pagination tokens, or hitting a limit makes
selection fail: never publish a candidate from an incomplete listing.
[S3 ListObjectsV2 API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html)
defines the listing and continuation protocol.

Apply full-string matching to decoded keys using exactly:

```regex
^[0-9]{6}-citibike-tripdata\.zip$
```

Parse the six digits as a strict Gregorian year/month, requiring year
0001–9999 and month 01–12. Reject malformed dates such as `202600`, `202613`,
and `000001`. Do not accept path prefixes, suffixes, different letter case,
Jersey City objects, annual objects, `.csv.zip`, or nested archives.

Choose the greatest valid `YYYYMM` across the complete listing. `LastModified`
is metadata, not ordering; do not restrict selection to the current year,
previous complete month, or dates before the current clock. A published valid
future-labelled month wins if its key is greatest. Do not optimize discovery
using a current-year prefix. Resolve duplicate listing entries consistently;
conflicting metadata for the same key requires rediscovery, not arbitrary choice.

Only the selected object's body may be downloaded. Earlier matching months are
never downloaded as fallbacks. If there are no matching objects, record
`NO_MATCH`, finish without a download, preserve any prior successful archive,
and expose a sanitized operational reason. Discovery failure is distinct from
a complete listing containing no match.

### Streaming Download And Integrity

After discovery, obtain current object metadata with HEAD as needed, including
size, ETag, LastModified, and optional version ID. Pin the GET to the version ID
when available, otherwise use `If-Match` with the ETag. A 404 or 412 during the
operation causes bounded rediscovery and latest selection; never fall back to
an earlier month. Restrict requests and redirects to the configured trusted
S3 origin; a test-only endpoint override enables local HTTP fixtures.

Stream the response through a bounded buffer to a uniquely named `.part` file
inside the configured runtime data directory, outside application resources.
Do not buffer the full archive in Java memory. Disable transparent content
transformation, enforce the limit against both advertised and actual bytes,
and reject non-success responses, premature EOF, length disagreements, and
upstream-version changes. Calculate SHA-256 while streaming. ETags are opaque
version validators and are not assumed to be MD5/SHA-256 checksums. Compare a
compatible upstream full-object checksum when supplied; otherwise local
SHA-256 records local byte integrity, not independent upstream authenticity.
No ZIP extraction, entry traversal, CSV parsing, or ride validation occurs.

Use configurable connect, idle/read, and whole-operation timeouts. Retry
transient connection failures, 408, 429, and selected 5xx responses with bounded
exponential backoff and jitter; respect Retry-After within the overall deadline.
Do not retry permanent authorization errors or size-limit violations. Restart
an interrupted transfer from zero into a fresh temporary file; resumable range
transfers are outside this proposal. Delete failed partial files safely.

### Durable Metadata, Atomic Publication, And Recovery

Keep downloader metadata independent of the in-memory analytics DuckDB. Use
immutable archive generations and atomically replaced JSON state on the same
durable local filesystem. The active manifest contains source key, YYYYMM,
ETag, optional version ID, LastModified, SHA-256, exact byte size, relative
archive locator, discovery/download/completion timestamps, and `SUCCEEDED`
status. Separate bounded attempt records contain attempt ID, timestamps,
selected object when known, retry count, status, and sanitized failure code;
a failed attempt must not overwrite the last-success manifest.

Implemented attempt states: `DISCOVERING`, `DOWNLOADING`, `SUCCEEDED`, `UNCHANGED`,
`NO_MATCH`, `FAILED`, and `INTERRUPTED`. There are no row counts because no rows
are read. Retain only a configured number of attempts and archive generations.

Hold an in-process coordinator lock and an exclusive OS file lock on the data
directory across discovery, download, and publication. Scheduled and manual
triggers use the same coordinator; overlapping requests return `BUSY` or are
skipped, without starting another transfer. One application instance owns each
directory; fail closed if the deployment cannot provide reliable file locks,
atomic same-filesystem moves, and durable writes. Independent instances require
separate directories; sharing a directory across hosts is unsupported.

Publication protocol:

1. Persist the pending attempt atomically, then stream and verify its `.part`.
2. Flush and sync completed bytes, rename with an atomic move to a unique
   immutable generation, and sync directory metadata where supported.
3. Write and sync a complete manifest to a temporary sibling, then atomically
   replace the active manifest. This replacement is the publication commit:
   consumers see either the previous complete generation or the new one.
4. Finalize the attempt and clean up only unreferenced generations after the
   configured retention/grace period. Keep the previous successful generation
   until the new manifest is durable. Never delete the active archive.

The archive rename and manifest replacement are separate operations. A crash
before manifest commit leaves an unreferenced generation; a crash after commit
may leave an unfinished attempt but a valid success. At restart, acquire the
same lock, validate the active manifest and its referenced file's size/checksum,
reconcile the committed attempt, mark other nonterminal attempts interrupted,
and remove stale partial files. Reuse orphan generations only after verifying
their checksum and source identity. A corrupt manifest or referenced file must
never produce a false `UNCHANGED` result; report it and redownload the currently
selected latest object, preserving any independently verified prior generation.

Skip body downloading when the selected key, available version validators,
and size match durable successful metadata **and** the local archive passes
integrity verification. If validators are absent, use conditional retrieval
where possible, otherwise redownload the latest object to establish identity;
size and LastModified alone do not prove unchanged content.

If the latest key is revised, download a new generation and publish only after
successful verification. If the new checksum equals the existing checksum,
update durable source metadata without retaining duplicate archive bytes.
When a newer month appears, the same publication flow advances the active
manifest. Prior files may remain temporarily for retention/recovery, but no
older month is fetched. Failure at any stage preserves the last successful
manifest and bytes. A missing upstream object never deletes the local success.

### Downloader Configuration And Operational Behavior

These properties/defaults are implemented by `DownloadConfig`. Byte settings
accept integer bytes; the table expresses their equivalent GiB/MiB values.

| Property under `citibike.download.*` | Default / behavior |
| --- | --- |
| `enabled` | `false`; explicitly enable the component. |
| `data-directory` | `./data/citibike-downloads`; durable writable volume, outside the JAR. |
| `schedule` | `0 0 4 * * ?`; daily discovery at 04:00 to notice publication/revisions. |
| `time-zone` | `America/New_York`; controls polling only, never latest selection. |
| `check-on-startup` | `true` when enabled; one asynchronous latest check, no missed-run replay. |
| `connect-timeout`, `read-timeout` | `10s`, `60s`; enforce idle reads explicitly. |
| `operation-timeout` | `45m`; bounds discovery, retries, and transfer together. |
| `max-attempts`, `max-backoff` | `3`, `60s`; finite HTTP attempts per request within the operation deadline. |
| `max-rediscoveries` | `2`; cap restart cycles when publication changes during a transfer. |
| `max-download-bytes` | `4GiB`; larger latest objects fail without fetching earlier ones. |
| `max-listing-pages`, `max-listing-response-bytes` | `100`, `4MiB` per page; limit breaches fail full discovery. |
| `min-free-disk-bytes` | `8GiB` reserve; preflight space for new bytes alongside active generation. |
| `retained-generations`, `retained-attempts` | `2`, `100`; bounded local retention, never evict active data. |
| `cleanup-grace-period` | `24h`; readers/backup operations must finish within this interval or pin generations. |

A daily check downloads monthly archives only when the latest published object
changes. There is no previous-month calculation, history range, catch-up queue,
or publication deadline. While the application is stopped no checks run; on
restart one latest check suffices and intermediate months are intentionally
skipped. A manual internal/service or authenticated management trigger takes
no month, URL, key, or backfill range; it simply runs the same latest check.
Do not introduce a public download endpoint without an authentication design.

Expose bounded operational status: last successful month/time, current attempt,
last outcome, and sanitized failure reason. Logs may use attempt correlation
IDs; metric tags use fixed outcomes only, never month, object key, ETag, hash,
or attempt ID. Download failures do not change analytics readiness or terminate
the application. Monitor failed checks, age of last success, disk space, and
lock contention independently of analytics health. Back up the active manifest
and referenced immutable archive together, holding a generation pin or pausing
cleanup; verify restored checksums before reporting success.

### Downloader Components And Deterministic Verification

Implemented responsibilities: `LatestArchiveDownloader` performs strict discovery and streams/verifies bytes; `DownloadStateStore`
owns manifests, attempt retention, and recovery; `DownloadCoordinator` owns
locking and retries; `LatestArchiveScheduler` invokes the same coordinator.
These components do not call `CitibikeDataLoader` or analytics repositories.

Tests use small opaque ZIP fixtures, temporary runtime directories,
a local HTTP stub and zero-backoff test configuration. Month selection never
uses the current clock; wall-clock timestamps are operational metadata.
No public S3 connection or large archive is required. Cover:

- Multi-page, namespaced, encoded listings; maximum month across all pages;
  misleading LastModified; current-clock-independent future-labelled selection.
- Exact filename acceptance and exclusions, invalid years/months, empty listings,
  malformed XML, XXE refusal, repeated/missing continuation tokens, and limits.
- Only one selected archive GET; no older fallback when latest disappears,
  publication appears on a subsequent poll, and bounded 404/412 rediscovery.
- Streaming, timeouts, transient retries/Retry-After, permanent failures,
  actual/advertised oversize, truncated or corrupt bytes against a supplied
  checksum, changed validators, and cleanup of unsuccessful temporary files.
- SHA-256/size metadata, unavailable ETags/version IDs, verified unchanged skip,
  missing/corrupt local files, revised latest archives, identical revised bytes,
  and advancement to a newly published month without intermediate downloads.
- Overlapping scheduled/manual requests, second-process directory locking,
  restart after interrupted transfer, crash before/after manifest commit,
  atomic reader visibility, and preservation of prior success on every failure.
- Retention never deleting active data, no-match status with/without prior
  success, backup/restore validation, bounded metric tags, sanitized diagnostics,
  and analytics contracts/readiness remaining independent of downloader results.

Implementation verification includes local-stub tests and packaged subprocess
checks using application classes/libraries from the built Quarkus artifact.
They exercise external storage, repeated process restart, abrupt halt after
manifest publication, interrupted attempt recovery, and process lock exclusion.
Existing packaged API tests verify that analytics behavior is preserved.

Implementation details: discovery and HTTP streaming share one class rather
than a separate catalog. Read-only status is available at
`GET /api/v1/download/status`; manual execution remains an internal CDI method.
Attempt count retention applies immediately; archive count retention observes
the cleanup grace period. The JDK HTTP transport may reconnect an idempotent
GET internally in addition to bounded application attempts. No real large S3
archive was downloaded for verification.

- [x] Implement strict complete-listing discovery and latest selection.
- [x] Implement bounded streaming, integrity verification, and retry behavior.
- [x] Implement durable manifests, atomic publication, locking, and recovery.
- [x] Implement configurable polling and the shared manual trigger.
- [x] Verify local-stub tests and packaged restart behavior.

### Downloader Completion Evidence — October 10, 2026

- `mvn verify` passed: **71 unit/application tests and 2 integration tests**,
  zero failures, errors, or skips. This includes **21 downloader local-stub
  tests**, one packaged downloader subprocess test, and the packaged analytics
  API test. The latter also checks read-only downloader status and refusal of
  POST requests to that status route.
- Packaged downloader processes load application classes/libraries from
  `target/quarkus-app`, store state in an external temporary directory, skip
  unchanged bytes after a fresh process launch, recover after `Runtime.halt`,
  and reject another process holding the same directory lock.
- Full application packaging succeeds with the Quarkus scheduler extension.
  Default-disabled operation preserves the 1,500-row analytics baseline and
  existing health checks. Enabling production S3 downloading is an operator
  decision documented in README.md; no live archive body was fetched in tests.
- `git diff --check` passed. Existing unrelated worktree changes were preserved.
- Safe atomic publication requires a filesystem supporting atomic moves,
  file/directory sync, and process locks. Distributed directory sharing and
  archive extraction/analytics ingestion are outside this component.

## Phase 2: Monthly Ingestion And Durable Storage — Implemented

### Scope And Recommendation

Extend the existing Java 25 / Quarkus / DuckDB JDBC application to accumulate
monthly Citi Bike trip data without rebuilding its application JAR. Scheduled,
manual, startup catch-up, and historical backfill requests must use one ingestion
pipeline. Preserve the analytical endpoints, prepared statements, validation,
pagination, query limits, logging, metrics, and health-check contracts.

**Recommend file-backed DuckDB on a durable local volume, with retained,
immutable source archives and ingestion metadata in the same DuckDB database.**
DuckDB itself supports persistent storage; an embedded database does not have
to be an in-memory database. JDBC selects a persistent database by supplying a
file path. The recommendation is an application design decision based on the
current single-process architecture and monthly accumulation requirement.
[DuckDB JDBC connections](https://duckdb.org/docs/current/clients/java/connecting)
documents the supported connection modes.

Evaluate only in-memory DuckDB, persistent DuckDB, and DuckDB over retained
Parquet files. External datastores are outside this extension's scope. Retain
in-memory mode for existing demo and deterministic tests. Native-image delivery
remains deferred.

### Storage Options: Pros And Cons

The following performance and cost assessments are qualitative planning
judgments. Benchmark this application's queries and imports before assigning
hardware requirements or claiming that one format is always faster.

| Concern | In-memory DuckDB rebuilt from retained data | Persistent, file-backed DuckDB | DuckDB querying retained Parquet |
| --- | --- | --- | --- |
| Durability | Simple disposable query state. Retained inputs survive, but database state must be rebuilt; durable ingestion metadata needs separate handling. | Committed rides and ingestion metadata survive restart together. Requires durable storage and recovery/backup procedures. | Immutable data files survive restart. A durable manifest is also required to identify the active versions. |
| Restart time | Reparse/reload accumulated history before serving it; restart work grows with history. | Open the existing database and recover its WAL if necessary; avoid recurring CSV reloads. | Open metadata and register views/file lists rather than importing all rows; initial queries still read files. |
| Analytical performance | Useful for small, repeatedly queried datasets; avoid CSV parsing on each query after loading. Rebuilding is expensive. | Suitable for repeated aggregations with native tables and caching; concurrent imports still compete for CPU, RAM, and disk. | Column pruning and filter pushdown suit date-partitioned scans. File layout and row groups matter; many small files add overhead. |
| Memory and disk | Retained inputs plus substantial memory for loaded data and query work. Spill space does not make the database durable. | Data can remain on disk with a bounded working memory budget. Budget database, WAL, staging, spill, source archives, and backups. | Keep compressed columns on disk without duplicating all rows into native tables. Budget conversion, staging, manifest, and query spill space. |
| Ingestion and query concurrency | One process can load on a separate connection while readers use committed data, but large reloads contend for resources. | One application process, one serialized ingestion writer, and bounded query connections. Transactions provide atomic monthly replacement. | Readers can use immutable snapshots while new files are prepared. Manifest publication and old-file retention need additional coordination. |
| Multiple instances | Each instance has independent data and memory costs; ingestion consistency must be coordinated. | Do not mount one writable database across application processes. Read-only copies/snapshots can serve replicas. | Independent readers can share immutable files. A single publisher and versioned manifests are still required. |
| Backups | Back up retained inputs and metadata, then rebuild. Test restore duration. | Back up a consistent database snapshot and referenced archives; a live file copy alone is insufficient. | Back up the manifest and every immutable file it references as one restorable generation. |
| Operational complexity | Few database files, but restart reconstruction and metadata consistency become cumbersome with history. | Smallest change to the current JDBC repository; adds migrations, a durable volume, backups, and retention management. | Adds CSV-to-Parquet conversion, partitioning, manifests, version cleanup, and file-based atomic publication. |
| Cost | No database service fee, but repeated parsing and memory can increase compute costs. | No separate database service; local disk and archive/backup storage are the main additions. | No separate database service; storage can be efficient, with conversion and potential remote I/O costs. |

The in-process read/write deployment model and read-only multi-process mode are
described in [DuckDB concurrency](https://duckdb.org/docs/current/connect/concurrency).
Direct Parquet scans and their projection/filter behavior are documented in
[DuckDB Parquet queries](https://duckdb.org/docs/current/guides/file_formats/query_parquet).
These capabilities do not imply unlimited memory or automatic multi-file
transactionality.

Keep the persistent recommendation while one application owns ingestion and
the history fits the available disk and backup window. Reassess after measuring
restart/recovery time, disk growth, import duration, and query latency. Prefer
partitioned Parquet with a versioned manifest when portable immutable datasets,
many independent readers, or cold-history storage outweigh the simplicity of
native tables. In-memory mode remains appropriate for disposable demonstrations
and small bounded subsets. A requirement for multiple independent writers is
outside this single-instance design and requires a separate architecture review.

### Archive Evidence And Discovery

Research on October 10, 2026 used anonymous S3 XML `ListObjectsV2` requests and
small HTTP range requests to inspect ZIP directories and CSV headers. The HTML
[archive index](https://s3.amazonaws.com/tripdata/index.html) is a browsing UI,
not the ingestion API. No complete large archive was downloaded or profiled.

| Observed object | What the inspection establishes |
| --- | --- |
| `202609-citibike-tripdata.zip` | NYC September 2026; 972,151,835 bytes; five CSVs named with `_1` through `_5`; physical ZIP entry order is not sorted. The inspected CSV uses the current 13-column header. |
| `202606-citibike-tripdata.zip` | Listing size is 1,051,241,498 bytes: a 1 GB decimal download cap would reject a published month. |
| `JC-202601-citibike-tripdata.zip` and `JC-202602-citibike-tripdata.csv.zip` | Jersey City monthly object names use both suffix conventions. |
| `JC-202609-citibike-tripdata.csv.zip` | One real CSV with the quoted current header, plus a `__MACOSX` sidecar that must not be ingested. |
| `2023-citibike-tripdata.zip` | The `2023` listing currently exposes an annual archive. Its ZIP directory contains twelve monthly ZIPs under a directory and macOS metadata entries. A monthly backfill cannot assume a root-level monthly object exists. |

The observations can be reproduced from the [NYC 2026 listing](https://s3.amazonaws.com/tripdata?list-type=2&prefix=2026),
[NYC 2023 listing](https://s3.amazonaws.com/tripdata?list-type=2&prefix=2023), and
[Jersey City 2026 listing](https://s3.amazonaws.com/tripdata?list-type=2&prefix=JC-2026).
Current object modification timestamps are not guaranteed first-publication
dates, and no fixed publication day is assumed. Official
[Citi Bike system-data documentation](https://citibikenyc.com/system-data)
also describes multiple CSVs within an archive and a different historical
schema. Header inspection alone does not establish full-file data validity.

The implemented `ArchiveCatalog` using anonymous HTTPS listing requests, with no
AWS credentials required for the public bucket:

1. Request relevant year/month prefixes, including the annual NYC fallback,
   and Jersey City prefixes only when enabled. Parse namespace-aware XML with
   DTDs/external entities disabled and bounded response size.
2. Follow `IsTruncated` / `NextContinuationToken` until complete, with page/key
   count limits and repeated-token detection. Correctly encode query tokens and
   object keys. Do not infer absence from an incomplete or failed listing.
3. Recognize the observed monthly `.zip` and `.csv.zip` forms and annual
   envelopes through explicit patterns. Parse months as `YearMonth`; exclude
   unrelated objects and the current incomplete month.
4. Prefer a discovered monthly archive over its annual fallback. If multiple
   distinct monthly candidates claim the same city/month, fail with an
   `ambiguous_source` status rather than arbitrarily merging overlapping data.
5. For annual fallback, download/cache the discovered parent archive and select
   the requested monthly ZIP by validated entry metadata. Process only that
   month, using a maximum of two ZIP layers; do not recursively unpack arbitrary
   archives. Reuse the immutable parent download for subsequent backfill months.
6. Record the actual parent object and nested entry chain. Never build a
   supposed object URL from a guessed filename. Unsupported layouts produce a
   bounded diagnostic and leave active data unchanged.

Use the [S3 listing API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html)
and [object metadata API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html)
as the protocol references. Source endpoints are trusted deployment settings;
manual/API requests cannot supply URLs, object keys, paths, or SQL.

### Persistent Storage And Startup

Add a validated storage mode (`memory` or `file`) and a configured database path
under an application-owned durable data directory. In file mode, retain one
anchor, a dedicated ingestion connection, and the existing bounded pool of
duplicate query connections sharing one database instance. Serialize all
application writes; never lend the ingestion connection to HTTP queries.
Downloads and extraction occur before the final database transaction.

Use versioned, transactional schema migrations. On restart, open and validate
the existing database, recover committed state, and construct load metadata from
durable tables; do not invoke the current drop-and-replace startup loader against
persisted monthly history. Introduce a shared reporting service so services and
readiness no longer depend exclusively on `CitibikeDataLoader.report()` in RAM.

File mode defaults to no bundled-data bootstrap. An empty database is not ready
until one valid dataset commits; trigger initial previous-month/catch-up work
asynchronously. Existing valid data becomes ready without waiting for network
access or backfill completion. Keep bundled loading in memory-mode demo/tests.
If a bundled bootstrap is explicitly enabled in file mode, tag its city/month
provenance so a full monthly import replaces the sample instead of duplicating
the June 2023 subset. This is an explicit migration path, not an automatic merge.

Store these durable records, using generated IDs and prepared statements:

| Record | Required information |
| --- | --- |
| Schema version | Applied migration versions and timestamps. |
| Job / attempt | Job ID, trigger (`monthly`, `catch-up`, `manual`, `backfill`), requested city/month, status, attempt count, next eligible retry, timestamps, bounded failure reason and correlation ID. |
| Archive version | Source bucket/key, optional upstream version ID, opaque ETag, last-modified time, expected/received bytes, locally computed SHA-256, immutable local archive reference, and nested archive chain where applicable. |
| Active partition | City/month, committed version, active job, CSV count, row count, date bounds, validation-policy version, and commit timestamp. |
| Source CSV | Stable provenance identity, archive-relative entry, CSV SHA-256, row count, and missing/outlier/validation counts. |
| Rides | Existing columns and textual station IDs, plus internal city, source month, source version, and CSV provenance. |

Use a unique active partition per city/month and retain previous attempt/version
history. Preserve the current strict ride-ID duplicate policy within a month and
against other active partitions, including optional Jersey City data. Report
collisions without ride IDs; do not silently discard or overwrite conflicting
rides. Cross-city ID collisions require an explicit identity-policy decision.

### Scheduling, Manual Requests, And Backfill

Use Quarkus `quarkus-scheduler`, managed by the existing BOM. Schedule the monthly
trigger for the **6th at 04:00 America/New_York**, using a configurable cron and
zone, and calculate the previous complete month from an injected `Clock` in that
zone. This default is a polling choice, not a promised upstream release date.
Use a daily **04:30** configurable catch-up trigger for late publication and
recent corrections. [Quarkus scheduling](https://quarkus.io/guides/scheduler-reference/)
supports explicit zones; its skip-concurrent option applies within an instance.

All triggers enqueue durable work through the same coordinator. One bounded
worker executes jobs in month order, with a shared gate covering scheduler,
startup, manual, and backfill paths. Scheduler skip-concurrent protection alone
does not prevent overlap between different triggers. Duplicate requests for an
already queued/running city/month reuse its job; an unchanged committed version
becomes a recorded no-op. Limit queue size and backfill range.

At startup, recover pending/interrupted attempts and enqueue missing months
since a configured catch-up start month, capped to a configurable recent window
unless an explicit historical range is supplied. Defaults: last three complete
months, two jobs per catch-up run, and daily checks for 45 days after a month's
end before flagging `publication_overdue`. Keep missing publication as a distinct
state from transport or validation failures; manual retry remains available.
Recheck committed months in that recent window for revisions. Older corrections
require an explicit recheck/backfill range; do not claim indefinite automatic
detection. Historical backfill accepts inclusive `YYYY-MM` bounds, excludes
incomplete/future months, and defaults to a maximum of 24 months per request.

Provide protected management operations, separate from analytical endpoints:

- `POST /api/v1/admin/ingestion/months` accepts a month and optional allowlisted
  city scope; returns `202` and a durable job reference.
- `POST /api/v1/admin/ingestion/backfills` accepts an inclusive month range and
  city scope; returns `202` and the queued job references.
- `GET /api/v1/admin/ingestion/jobs` provides bounded pagination and fixed status
  filters; `GET /api/v1/admin/ingestion/jobs/{jobId}` reports safe progress.
- `GET /api/v1/admin/ingestion/status` reports active coverage, the last successful
  import, pending months, recent failures, and overdue publication.

Management routes are disabled until authentication/authorization is configured;
require an ingestion-admin role and document Quarkus management security setup.
NYC is the default. A Jersey City request is rejected unless deployment
configuration explicitly enables Jersey City. Use stable JSON validation and
queue-busy errors, and never expose local archive paths or raw exceptions.

The scheduler runs only while the application is running: it cannot download
while the process or machine is stopped. Durable jobs and startup reconciliation
provide bounded catch-up, rather than pretending missed cron executions were
run. Each city/month is an atomic unit; a backfill is not one giant transaction.
Failure of one month does not undo successful earlier months.

### Download, Extraction, And Resource Bounds

Use Java's streaming HTTP client or a similarly bounded client. Keep buffers
small; do not download or decompress entire archives into heap memory. Write to
a generated job directory and `.part` file, compute SHA-256 while streaming,
flush and atomically publish the verified archive on the same filesystem.

Read object size and ETag/version metadata first. Pin a version when available,
otherwise use conditional requests against the selected ETag; rediscover after a
precondition failure. The inspected objects did not expose a version ID, so it
must be optional. Treat ETag as a change token, not a universal content checksum.
Validate response status, byte count, checksum headers when usable, ZIP structure,
and entry CRCs. A SHA-256 computed locally records the received artifact but does
not by itself prove correspondence to an upstream checksum.

Retry connection failures, timeouts, throttling, and transient server errors:
at most three attempts with exponential backoff, bounded jitter, and capped
`Retry-After`. Missing publication is retried by catch-up, not a tight loop.
Schema errors, unsafe ZIPs, quota failures, and duplicate conflicts do not receive
blind immediate retries. Abandon or restart incomplete downloads safely; a
resume implementation is optional and must pin the same object version.

Use a local seekable ZIP reader with ZIP64 support. Validate every entry name,
reject absolute/traversal/drive/backslash paths, symlinks, encryption, normalized
name collisions, and unsupported compression/layouts. Extract only regular CSVs
and recognized monthly ZIP entries. Ignore known macOS sidecars/directories;
unknown non-data layouts are reported rather than silently treated as valid
coverage. CSVs can be in legitimate archive directories; the Phase 1 top-level
classpath rule does not apply to archive members. Sort selected entry identities
before loading, and never flatten away provenance or collisions.

Enforce compressed, per-entry, aggregate expanded, entry-count, nesting,
compression-ratio, disk-space, and wall-clock limits using observed bytes, not
just ZIP declarations. Apply budgets across both ZIP layers, including nested
ZIP materialization. Reject truncated streams and CRC failures. Clean job
directories after failure/success while retaining referenced verified archives.

Implemented configurable settings (production defaults; memory-mode overrides
retain the demo/test behavior):

| Setting | Proposed default |
| --- | --- |
| `citibike.storage.mode` / `data-directory` | `file` / `./data/citibike`, resolved once to an owned durable directory |
| `citibike.storage.database-path` / `bootstrap-bundled` | `citibike.duckdb` inside that directory / `false` in file mode |
| `citibike.ingestion.enabled` / `include-jersey-city` | `true` in file deployments / `false` |
| `citibike.ingestion.monthly-cron` / `time-zone` | `0 0 4 6 * ?` / `America/New_York` |
| `citibike.ingestion.catch-up-cron` / `lookback-months` | `0 30 4 * * ?` / `3` |
| `citibike.ingestion.catch-up-start-month` | Optional inclusive lower bound; otherwise use the recent lookback |
| `citibike.ingestion.backfill.from` / `.to` | Optional paired inclusive month bounds, no historical backfill by default |
| `citibike.ingestion.publication-wait-days` / `jobs-per-run` | `45` / `2` |
| `citibike.ingestion.max-backfill-months` / `max-queued-jobs` | `24` / `100` |
| HTTP connect / idle-read / download deadline | `10s` / `60s` / `30m` |
| HTTP attempts / maximum retry delay | `3` / `60s` |
| Download / extracted entry / aggregate expanded bytes | `4GiB` / `4GiB` / `16GiB` per job |
| ZIP entries / layers / compression ratio | `1000` / `2` / `100:1` |
| Ingestion SQL timeout / complete job deadline | `30m` / `2h`, separate from the existing 2s query timeout |
| Ingestion concurrency / DuckDB threads / query connections | `1` / `4` / existing `4` query connections |
| File-mode DuckDB memory / spill limit | Start at `512MB` / `8GB`; expose validated bounded overrides and measure actual peak process memory |
| Archive cache quota / minimum free-space reserve | `64GiB` / `8GiB`, plus a preflight estimate for the selected archive, staging, WAL, and spill |
| Superseded archive retention | `30 days`, only after a restorable backup and when no active partition/job references it |

Do not retain the Phase 1 512MB spill cap unquestioningly for multi-million-row
imports. DuckDB's memory setting is not a promise that total JVM/native process
memory stays below that value. If disk/quota limits cannot accommodate an import,
reject/defer it before destructive work and preserve the existing data. Never
evict an active partition's only retained source merely to admit a new download.

### Validation, Atomic Imports, And Revised Archives

Extract the existing typed CSV ingestion/schema validation into a reusable
component accepting trusted local sources. Keep the exact current column
contract, station IDs as text, required identifiers/categories/timestamps,
finite values, coordinate bounds, and duplicate validation. Support quoted
headers, BOMs, multiple files, and fractional timestamp precision without
schema inference. Legacy headers produce `unsupported_schema` with a sanitized
source identity and expected schema version. Historical schema normalization is
a separate milestone; historical backfill can legitimately report unsupported
months rather than inventing missing identifiers.

Before finalizing validation, profile at least one full modern NYC month and one
enabled Jersey City month, recording aggregate counts only. Inspect missing and
unpaired coordinates, zero/negative durations, category variants, duplicate IDs,
and month boundaries, including DST. The small header samples above do not
justify relaxing current checks. Retain long-duration outliers; do not introduce
an additional 60-second filter merely because upstream describes preprocessing.
Source timestamps remain America/New_York local values without offsets. Do not
pretend ambiguous DST timestamps identify unique instants.

If full-file evidence shows legitimate records rejected by Phase 1, document a
specific proposed rule change and its effect on analytics/data-quality counts,
add fixtures, and update a validation-policy version. Do not silently skip bad
rows or disable validation to force an import to pass. An entire candidate month
fails by default for parse/schema/required-field/duplicate errors. Rows must
belong to the claimed month by `started_at`; rides ending in the following month
remain valid. Unexpected start-month coverage is a reported layout/data error.

The import protocol is:

1. Persist the queued job and attempt state in short transactions. Download,
   verify, and retain an immutable archive version; safely extract all selected
   files before altering active ride data.
2. Bulk-load a private per-job staging table through the dedicated writer with
   ingestion-specific timeouts. Validate the complete candidate and record
   per-file counts; do not expose staging through REST or source reports.
3. In one database transaction, validate conflicts with other active partitions,
   remove only the previous target city/month rows and active source records,
   insert the candidate rides, publish source/partition metadata and date/count
   summaries, and mark that job/version committed. Do not drop and replace the
   complete `rides` table as the startup loader currently does.
4. Commit once. Readers use snapshot-consistent queries and see old or new
   committed data. Data-quality responses obtain ride statistics and metadata
   from the same snapshot, avoiding mixed generations.
5. On failure, roll back; record a safe failure in a separate short transaction
   and clean staging. Do not shut down the shared database or mark valid existing
   data unavailable because a later import failed.

An unchanged city/month version is a no-op after checking its committed
manifest. A changed ETag/version causes a candidate download and validation;
matching archive content can reuse the existing materialization. A valid revised
archive **replaces the entire city/month partition**, including removed rides,
all CSV counts, and source metadata. Validation excludes the old version of that
same partition when testing duplicates, but checks all other active partitions.
A bad revision leaves the previous version active. Parent annual archive changes
are checked through the selected nested month's checksum to avoid replacing
unaffected months unnecessarily. Never regard a missing upstream object as an
instruction to delete already committed data.

Archive publication and a database commit are different durability boundaries.
Publish verified files before committing references to them; leftover unreferenced
archives after a crash are safe to reconcile later. On restart, mark nonterminal
attempts interrupted, clear orphan staging/partial files, reuse verified cached
artifacts where safe, and consult the committed partition/job record before
retrying. A crash after commit but before an HTTP response must not import again.
Durable filesystem guarantees and WAL recovery must be tested with abrupt
process termination, not only a graceful close.

### API, Health, Metrics, And Operations

Keep existing analytical routes and response fields. Derive existing load and
data-quality metadata from active committed sources across months; audit history
belongs in management status. Keep all date, enum, pagination, and prepared SQL
protections. Ingestion gets a separate timeout; do not extend public query
timeouts to accommodate background work.

Preserve the current dataset-bound date defaults and 366-day request cap. With
history spanning more than 366 days, an over-wide inferred default range continues
to produce the existing `400`; document explicit `from`/`to` windows and expose
coverage in management status. Do not silently change endpoint defaults to a
rolling window. A more convenient default-window contract would be a separate
API change, not part of this storage migration.

Liveness remains lightweight. Initial readiness requires migrated storage, a
valid committed dataset, and the bounded `SELECT 1` check. Later publication,
download, validation, or correction failures degrade ingestion status but keep
readiness UP while the prior dataset is usable. Database corruption, inaccessible
storage, and existing pool saturation still affect readiness. Expose freshness
and coverage separately so a ready process does not imply the newest month is
available.

Add ingestion duration, downloaded/expanded byte totals, attempt/failure/no-op
counts, pending-job count, running-job gauge, and last-success timestamp/freshness
gauges. Tags are limited to fixed trigger, stage, outcome, city, and bounded
reason enums; months, keys, filenames, hashes, job IDs, and exception text are
never metric tags. Use structured safe logs with a generated correlation/job ID,
fixed stage/reason, sanitized source identity, counts, and timing. No CSV row
values, SQL, station text, credentials, or local paths in logs or status JSON.

Operate exactly one writable application process per database/data directory.
Acquire an application ownership lock and fail a second writable startup rather
than relying on scheduler overlap settings. Use a persistent local volume with
tested locking/durability semantics; do not run writable copies over a shared
network filesystem. Scaling readers requires independently published read-only
database snapshots or immutable Parquet generations; a read-only replica must
not expect to open the same live writable file concurrently.

For a first simple backup procedure, pause ingestion, drain transactions, close
the database cleanly, and copy it with its manifest-referenced archives; keep the
brief maintenance downtime explicit. Alternatively implement and test a
database-consistent export/snapshot procedure. Do not copy only an open database
file and ignore its WAL. Follow [DuckDB checkpoint documentation](https://duckdb.org/docs/current/sql/statements/checkpoint)
and [DuckDB's persistent files](https://duckdb.org/docs/current/operations_manual/footprint_of_duckdb/files_created_by_duckdb).
Test restoring the database, manifests, and source cache, and document quotas,
retention, ownership, disk alerts, restart catch-up, and manual retry operations.
Avoid claiming deletion automatically shrinks the physical database file.

### Full-Archive Policy Reconciliation — Monthly V2

Full September 2026 NYC profiling found 4,982,855 rides in five CSVs, including
2,917 paired missing start coordinates and 549 rides starting in August but
ending in September. All rows overlap September; there are no unpaired
coordinates, malformed timestamps, unsupported categories, duplicate IDs,
non-positive durations, or out-of-range coordinates. Retain those records;
do not fabricate coordinates or discard boundary trips.

This evidence supersedes the earlier strict start-coordinate and start-month
ownership assumptions for file-mode ingestion: `monthly-v2` allows paired null
start coordinates and requires month overlap (`ended_at >= month_start` and
`started_at < next_month_start`). Schema migration 2 makes start coordinates
nullable. The bundled memory loader retains its original strict requirement.
API data-quality field names remain unchanged; management coverage reports the
paired missing-coordinate count. Other validation/duplicate/outlier rules remain
in force, and unsupported legacy schemas still require explicit conversion.

Persistent storage is the production default; development/test profiles use
memory mode. Startup reconstructs committed metadata and does not replace
history. Dedicated writer work is serialized; administrative writer contention
is bounded and can return 503 while queries use their separate pool. Durable
attempt rows preserve interruption history. A normal queue-drain race is
rechecked immediately; a database drain error waits for the bounded queue poll.
Catch-up rotates through the recent window so a small per-run cap does not
starve newer months. Missing publication becomes overdue after the configured
window and then requires a manual retry.

Superseded cache deletion waits for retention and a fully verified backup
receipt. Active partitions and nonterminal jobs pin their archives. Terminal
audit identities remain durable; pruned bytes remain in backup. No receipt or
an incomplete/corrupt backup means no eviction. See `docs/INGESTION.md` for
security, configuration, deployment, backup/restore, and operational procedures.

### Implemented Components And Implementation Order

Implemented components under `com.bscllc.learning.citibike`:

| Component | Responsibility |
| --- | --- |
| `config/IngestionConfig` and storage configuration | Typed settings, quotas, zone/cron/month-range validation, mode selection. |
| `db/SchemaMigrator`, updated `DuckDbManager` | Persistent lifecycle, ownership lock, schema versions, separate writer and query connections. |
| Shared CSV importer and reporting service | Existing validation reused for classpath and trusted local files; durable snapshot reporting. |
| `ingestion/ArchiveCatalog` | Paged S3 discovery, city/month matching, annual fallback, ambiguity handling. |
| `ingestion/ArchiveDownloader` / `SafeArchiveExtractor` | Streaming transfer, integrity checks, bounded ZIP64/nested-month extraction. |
| `ingestion/IngestionRepository` / `MonthlyImporter` | Durable jobs, archive/partition versions, atomic replacement, audit and recovery. |
| `ingestion/IngestionCoordinator` / `IngestionScheduler` | One bounded worker, shared overlap gate, monthly/daily/startup/manual/backfill triggers. |
| `resource/IngestionAdminResource` and DTOs | Protected queue/status operations without arbitrary sources or paths. |
| Ingestion metrics and updated readiness | Freshness/failure reporting independent of committed-data availability. |

Implementation sequence:

1. Create synthetic ZIP/XML/HTTP fixtures and dependency/lifecycle spikes against
   the pinned Quarkus and DuckDB versions; profile representative full archives
   and document any justified validation changes.
2. Add storage configuration, migrations, locking, persistent restart behavior,
   writer isolation, and memory-mode regression tests.
3. Reuse CSV validation/importing and replace in-memory reporting with committed
   snapshot metadata.
4. Implement paged discovery, version-aware streaming/cache, safe monthly/annual
   extraction, quotas, and integrity checks.
5. Implement durable jobs, atomic city/month replacement, duplicate rules,
   idempotency, corrected archives, and crash recovery.
6. Add scheduler/catch-up/backfill coordination and protected management routes.
7. Add status, bounded metrics, health behavior, operational documentation and
   backup/restore checks; update README during implementation, not this edit.
8. Run deterministic tests and packaged lifecycle checks, then an explicit opt-in
   full-data smoke import on a suitably sized temporary durable volume.

### Planned Tests And Acceptance Criteria

Use small archives and a local HTTP stub for normal tests; disable live schedules
and network dependencies in tests. Inject clock/sleeper/jitter so retries and
month calculations are deterministic. Cover:

- XML pagination, encoded tokens, repeated/truncated listings, both suffixes,
  NYC-only default, opt-in Jersey City, annual/nested-month selection, cache reuse,
  ambiguous candidates, unsupported layouts, and deterministic multi-CSV order.
- Publication after an initial missing listing, overdue status, HTTP retries and
  throttling, `Retry-After` bounds, interrupted/truncated downloads, checksum/CRC
  mismatch, ETag races, and unchanged versions avoiding repeated materialization.
- Traversal, absolute/drive/backslash paths, symlinks, duplicate normalized names,
  ignored sidecars, encrypted/unsupported entries, excessive nesting, ZIP64,
  declared-versus-actual size differences, compression bombs, quotas, disk-full
  cleanup, and multiple CSVs inside legitimate archive directories.
- Current quoted/unquoted schemas, legacy-schema rejection, null/invalid data,
  duplicates within/across files/months, overlapping bundled samples, textual
  station IDs, timestamp precision, outliers, and start/end/DST month boundaries.
- Exact queries from synthetic fixtures, atomic visibility while a candidate is
  staged/committed, metadata/statistics snapshot consistency, removal of deleted
  rides on revision, and failed revisions preserving all old rows and metadata.
- Durable restart without reloading history; faults during download, staging,
  replacement before commit, and after commit before acknowledgement; actual
  subprocess termination and WAL recovery; restoration from a backup.
- Overlap among all triggers, bounded queues, duplicate requests, scheduler zone
  and previous-month calculation, off-line startup catch-up limits, inclusive
  backfill bounds, and independently committed backfill months.
- Management authentication/authorization, stable JSON errors, no arbitrary
  source/SQL/path input, readiness DOWN before initial data, readiness UP after
  later failures, liveness independence, existing saturation behavior, bounded
  metric tags, and sanitized logs/status responses.
- A second writable process failing ownership checks and documented read-only
  replica behavior, while all Phase 1 memory-mode regressions remain green.

Verification commands are `mvn clean test`, `mvn clean verify` (including
fast-JAR tests), and the existing resource-discovery script in its memory/demo
profile. Add a packaged ingestion verification script using the local HTTP stub
and a temporary durable volume: import two months, restart, confirm no reimport,
replace a revised month, inject a failure, and verify old data/readiness remain
available. It must not depend on live S3 or download gigabytes in ordinary CI.

The opt-in full-data check enumerates the current archive, count all selected
CSV rows, establish schema/quality results, verify source provenance and exact
load totals, and record import time, disk/spill growth, peak process memory, and
representative query latency. Record measured evidence and configuration; do not
reuse the Phase 1 1,500-row baseline as proof of monthly-scale feasibility.

Phase 2 is accepted only when scheduled and manual requests use the same safe,
recoverable pipeline; runtime data requires no rebuild; month replacement and
metadata are atomic; unchanged jobs are idempotent; failures preserve usable
history; all listed deterministic and packaged checks pass; and README contains
storage trade-offs, configuration, security, backup/restore, deployment,
publication-delay, and stopped-process scheduling instructions.

### Phase 2 Milestones — Complete

- [x] Full-archive profiling and validation-policy reconciliation
- [x] Persistent DuckDB lifecycle, migrations, and ownership protection
- [x] Durable metadata and snapshot-consistent reporting
- [x] Paged archive discovery, including annual archive fallback
- [x] Streaming downloads, immutable cache, integrity checks, and safe extraction
- [x] Atomic monthly import/replacement and global duplicate protection
- [x] Idempotency, interrupted-job recovery, and crash/restore verification
- [x] Monthly scheduling, daily catch-up, and historical backfill
- [x] Protected manual ingestion and operational status
- [x] Ingestion metrics, freshness, and readiness behavior
- [x] Deterministic HTTP/archive tests and packaged ingestion verification
- [x] README updates, measured full-data verification, and operational runbook

### Phase 2 Completion Evidence — October 12, 2026

- Final `mvn clean verify` passed **102 unit/application tests and 3 integration
  tests**, with zero failures, errors, or skips. Python syntax and
  `git diff --check` passed. Verification-only processes/containers and the
  large temporary archive/databases were removed; aggregate evidence remains.
- Full-source discovery/download and profiling audited **4,982,855 NYC rides**
  across all five September 2026 CSVs. Source bytes, ETag, SHA-256, row/quality
  counts, native resource settings, and measured results are preserved in
  `docs/ingestion-profile-2026-10-12.json`.
- The complete archive was replayed through the real streaming HTTP/local-cache,
  ZIP, validation, and atomic import pipeline on a temporary file-backed volume.
  It committed all rows, reported zero invalid rows, and returned UNCHANGED on
  recheck. The final sampled run took about 10.11 seconds; summary/data-quality
  queries took about 135/237 ms. Retained disk was 1,232,210,971 bytes; sampled peak runtime
  files/spill were 2,995,420,024/863,862,784 bytes. Peak RSS was 1,625,096,192 bytes. File/spill samples measure
  logical bytes every 200 ms. These observations validate an 8 GB production
  spill ceiling and show that DuckDB's 512 MB setting does not cap total RSS.
- Deterministic tests cover monthly/annual discovery, cache reuse, publication
  delay/overdue and retries, CRC/corruption, unsafe names/backslashes, symlinks,
  encryption/methods, ZIP64, false sizes, budgets, legacy schemas, duplicate
  handling, policy-specific boundaries/paired-null coordinates, quoted/bounded
  headers, empty-member configuration, corrected/idempotent imports, metadata
  snapshot visibility, bootstrap replacement, schema upgrades, ownership,
  backup-gated retention, administrative authentication/authorization, queue
  bounds, New York month calculation, and NYC/JC catch-up fairness.
- Packaged subprocess checks crash before commit and after commit-before-ack,
  recover DuckDB/WAL, verify two independently committed months, and refuse a
  second writer. The packaged API script passed protected backfill/manual
  ingestion, restart without reimport, correction/removal, failed-revision
  readiness, coherent metadata, checksum backup, and restored queries.
- The resource-discovery script passed in explicit memory mode: 1,501 rides and
  two top-level files in both packaged and dev modes, with nested input excluded.
- Monitoring now includes a sixth monthly-ingestion dashboard. All six
  provisioned dashboards and **59 PromQL expressions** were checked against
  actual application metrics through Prometheus and Grafana. Promtool fixtures
  passed 14 checks across five missing/disabled/no-traffic/independent-health
  scenarios. Verification used an isolated temporary Compose project.
- `README.md`, `docs/INGESTION.md`, and monitoring instructions document modes,
  trade-offs, protected operations, schedules/offline behavior, quotas, policy,
  retention, backup/restore, and single-writer/read-replica deployment limits.

## Grafana And Prometheus Monitoring — Implemented

### Design And Scope

Add a local Compose stack with pinned Prometheus `v3.15.0` and Grafana `13.2.3`.
The application stays outside Docker. Prometheus scrapes `/q/metrics` every 15
seconds through a file-discovered host target. Grafana provisions the datasource
and five repository-owned JSON dashboards: API, DuckDB, CSV loading, latest
archive downloads, and JVM/process. The later ingestion extension adds a sixth dashboard and activates file-backed
monthly import independently of the archive-only downloader.

Use loopback-published ports (9090/3000 by default), independent named data
volumes, a Compose file secret for configurable Grafana credentials, and ignored
local `.env`/target/secret files. Prometheus retains 15 days or approximately
2 GB of blocks; budget WAL/head overhead separately. Disable Grafana analytics,
plugin preinstallation, and update checks. Document host connectivity and
Linux bridge/listening-address requirements.

### Instrumentation And Dashboard Semantics

Inspect the actual running `/q/metrics` output before choosing PromQL names and
labels. Existing HTTP/JDBC timers initially export summaries. Add a narrowly
scoped Micrometer filter with 14 histogram boundaries from 1ms to 60s for
`http.server.requests` and `citibike.jdbc.query.duration`. Existing count/sum
series and application contracts remain intact.

Register fixed configured connection/thread/memory/spill/timeout gauges and
`citibike.analytics.ready`. Readiness uses the existing bounded health probe on
scrape, not downloader outcome. Memory/spill gauges express configured ceilings
in decimal bytes and do not claim measured native allocations.

Eagerly initialize downloader enabled/active, last-check time/result, and
last-success publication time gauges. Initial unknown timestamps/results are
NaN. Disabled activity/freshness queries are filtered out. Failed/no-match checks
set the last-check result to zero while retained archive publication time stays
available. Busy checks do not overwrite a completed check's timestamp/result.
Initialize fixed downloader outcome and CSV validation/runtime failure counters
so genuine zero cumulative failures have an exported series. Tests explicitly
select failure-reason series rather than assuming a counter has only one tag.

All dashboard selectors scope the `citibike` job and selected application
instance. Dependent panels require scrape reachability. Request latency and
error fractions require positive rates; no samples are displayed as unknown,
not zero or green. CSV startup load duration uses cumulative sum/count because
loading can complete before the first scrape. Counters describe this process,
not durable job history. No new labels contain months, archive names, hashes,
ride IDs, station names, or attempt IDs.

### Files And Operational Verification

- `monitoring/compose.yaml`, `.env.example`, and `monitoring/README.md`: local
  stack, configuration, secrets, networking, retention, operations, and checks.
- `monitoring/prometheus/`: scrape config, default file targets, and promtool
  expression fixtures for missing metrics, idle API traffic, disabled downloads,
  and independent analytics readiness during a failed check.
- `monitoring/grafana/`: provisioned datasource/provider and five dashboards.
- `scripts/generate_monitoring_dashboards.py`: deterministic dashboard generator.
- `scripts/verify_monitoring.py`: checks JSON structure, provisioned dashboards,
  Grafana datasource health, actual exported metric references, and evaluates
  every panel expression through the live Prometheus engine. Optional synthetic
  requests populate success/client-error series across multiple scrapes.

### Monitoring Completion Evidence — October 12, 2026

- `mvn verify` passed **72 unit/application tests and 2 integration tests**, with
  zero failures, errors, or skips. Tests cover histogram export, configured
  limits, readiness, initial unknown freshness, disabled state, retained success
  through download failure, and bounded tags.
- Docker Compose configuration validation and Prometheus `promtool check config`
  passed for the final setup. `promtool test rules` passed 11 expression checks
  across four edge-case scenarios.
- On Docker Desktop/macOS, the packaged application ran outside Docker on port
  18080; Prometheus/Grafana used test ports 19090/13000. The live verifier
  confirmed the datasource, all five provisioned dashboards, and **49 panel
  expressions** against actual exported metrics, directly through Prometheus
  and through Grafana's datasource API. Synthetic requests produced real API
  rates/error series. The disabled downloader's dependent panels stayed absent.
- Recreating the containers retained credentials and monitoring volumes; the
  same complete live verification passed afterward. Secret-path changes require
  `up -d --force-recreate`, as documented.
- Dashboard JSON regeneration, Python syntax checks, and `git diff --check`
  passed. No live Citi Bike archive was downloaded. The verification-only
  application and containers were stopped; local default ports/target settings
  were restored, with the ignored credential file and named volumes retained.

## References

- [Quarkus REST](https://quarkus.io/guides/rest)
- [Quarkus logging](https://quarkus.io/guides/logging/)
- [Quarkus Micrometer](https://quarkus.io/guides/telemetry-micrometer/)
- [Quarkus SmallRye Health](https://quarkus.io/extensions/io.quarkus/quarkus-smallrye-health/)
- [DuckDB JDBC connections](https://duckdb.org/docs/current/clients/java/connecting)
- [DuckDB JDBC queries](https://duckdb.org/docs/current/clients/java/querying)
- [DuckDB JDBC query monitoring](https://duckdb.org/docs/current/clients/java/profiling)
- [DuckDB security](https://duckdb.org/docs/current/operations_manual/securing_duckdb/overview)
