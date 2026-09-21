# Citi Bike DuckDB API Implementation Plan

## Objective

Turn this repository into a Quarkus REST API that loads every top-level CSV
resource matching `src/main/resources/*.csv` into DuckDB through JDBC and
provides safe, observable analytical endpoints for Citi Bike ride data.

This plan keeps the project small enough for learning while applying
production-minded validation, SQL injection prevention, resource limits,
logging, metrics, health checks, and testing.

## Current Repository

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
- **Classpath discovery:** Java does not provide a universally reliable API for
  listing resource-directory contents. Prefer an automatically generated
  build-time index if fast-JAR enumeration is inconsistent.
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

## Milestones

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

## References

- [Quarkus REST](https://quarkus.io/guides/rest)
- [Quarkus logging](https://quarkus.io/guides/logging/)
- [Quarkus Micrometer](https://quarkus.io/guides/telemetry-micrometer/)
- [Quarkus SmallRye Health](https://quarkus.io/extensions/io.quarkus/quarkus-smallrye-health/)
- [DuckDB JDBC connections](https://duckdb.org/docs/current/clients/java/connecting)
- [DuckDB JDBC queries](https://duckdb.org/docs/current/clients/java/querying)
- [DuckDB JDBC query monitoring](https://duckdb.org/docs/current/clients/java/profiling)
- [DuckDB security](https://duckdb.org/docs/current/operations_manual/securing_duckdb/overview)
