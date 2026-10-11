# duckdb-learn

A Quarkus REST API for exploring Citi Bike ride data with DuckDB through JDBC.

At startup, the application discovers every top-level CSV matching
`src/main/resources/*.csv`, validates that the files use the expected Citi Bike
schema, and loads them transactionally into a shared in-memory DuckDB database.
The included dataset contains 1,500 rides. Invalid input files fail startup;
readiness becomes UP only after the complete load commits.

## Requirements

- Java 25
- Maven 3.9+

DuckDB loads a native library. Use
`--enable-native-access=ALL-UNNAMED` when running the packaged application on
Java 25.

## Run In Development

```sh
mvn quarkus:dev
```

The API is available at `http://localhost:8080/api/v1`.

## Build And Run

```sh
mvn clean package
java --enable-native-access=ALL-UNNAMED -jar target/quarkus-app/quarkus-run.jar
```

## Architecture

The application keeps the HTTP, validation, analytics, and database concerns in
separate layers. DuckDB is an embedded analytics engine inside the Quarkus
process; there is no external database server.

```text
src/main/resources/*.csv
        |
        v
generated resource index -> CSV catalog -> transactional data loader
                                              |
                                              v
                                      shared in-memory DuckDB
                                              ^
                                              |
HTTP request -> REST resource -> service -> repository -> connection manager
                    |              |            |
                    |              |            +-- prepared SQL and query metrics
                    |              +-- validation, defaults, and allowlists
                    +-- JSON contract and HTTP status mapping
```

### Startup And Data Loading

The Maven build generates a classpath index containing the top-level CSV files
from `src/main/resources/*.csv`. At application startup:

1. `CsvResourceCatalog` reads the index and CSVs from the application artifact
   itself. Dependency indexes and same-named CSVs cannot shadow application data.
   Resource names are validated, duplicate names are rejected, and filenames
   are loaded in lexicographical order.
2. `CitibikeDataLoader` copies each classpath resource to a controlled temporary
   file, validates its header, and loads it into an explicitly typed staging
   table.
3. The loader validates categories, timestamps, ride durations, coordinates,
   and duplicate ride IDs across all input files.
4. A successful transaction replaces the active `rides` table atomically. Any
   invalid file rolls back the complete load and prevents the application from
   becoming ready.

The database uses a named, shared in-memory DuckDB instance. This keeps the
learning project simple and makes every startup reproducible from the bundled
CSV resources. A file-backed database would improve restart time for larger
datasets, but would also require schema migration and stale-data handling.

### Request Flow

| Layer | Responsibility |
| --- | --- |
| `resource` | Defines `/api/v1` endpoints, JSON contracts, blocking execution, and HTTP error mapping. |
| `service` | Parses dates, applies dataset-aware defaults, validates ranges and pagination, and selects allowlisted options. |
| `repository` | Executes fixed analytics statements using JDBC `PreparedStatement` values and maps rows to DTOs. |
| `db` | Owns DuckDB startup, CSV loading, connection leasing, timeouts, readiness, and shutdown. |
| `dto` | Defines stable response records serialized by Jackson. |
| `validation` | Defines enum-like API choices and request validation errors. |
| `metrics` and `health` | Records bounded-cardinality metrics and exposes DuckDB readiness checks. |

Quarkus handles concurrent HTTP requests, while `DuckDbManager` limits database
work to a small pool of connections sharing the same in-memory database.
Connection acquisition and statement execution are both time-bounded, and
resource limits constrain DuckDB memory, threads, and temporary storage.

### Security And Observability Boundaries

All request values are bound as prepared-statement parameters. SQL structures
that JDBC cannot parameterize, including grouping intervals, station direction,
sort columns, and sort order, come only from Java enum allowlists. Limits,
offsets, date ranges, rider types, bike types, and station filters are validated
before repository execution.

Endpoint metrics are supplied by Quarkus Micrometer instrumentation. Custom
metrics cover CSV loading, loaded rows, DuckDB query duration and failures, and
active JDBC connections. Tags use bounded operation names and outcomes; raw
station names, filenames, ride IDs, and request values are not metric tags.
Application logging uses the SLF4J API with Quarkus's JBoss Log Manager backend,
so framework and application logs share one configuration without introducing
the conflicting `logback-classic` runtime.

The main implementation is under
[`src/main/java/com/bscllc/learning/citibike`](src/main/java/com/bscllc/learning/citibike), with
integration and repository coverage under
[`src/test/java/com/bscllc/learning/citibike`](src/test/java/com/bscllc/learning/citibike).

## Add Data

Place compatible Citi Bike CSV files directly in `src/main/resources` and
rebuild the application. Files in nested directories are not loaded.

Every file must use this header:

```text
ride_id,rideable_type,started_at,ended_at,start_station_name,start_station_id,end_station_name,end_station_id,start_lat,start_lng,end_lat,end_lng,member_casual
```

The loader fails atomically if a file has an incompatible schema, invalid data,
or a `ride_id` that is duplicated within or across files. The active table is
never partially replaced.

## API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/rides/summary` | Ride totals and duration percentiles |
| `GET /api/v1/rides/timeseries` | Hourly or daily ride counts |
| `GET /api/v1/rides/by-bike-type` | Bike-type usage |
| `GET /api/v1/rides/by-rider-type` | Member and casual usage |
| `GET /api/v1/rides/activity` | Activity by hour or day of week |
| `GET /api/v1/stations` | Paginated start or end station activity |
| `GET /api/v1/routes` | Paginated popular station pairs |
| `GET /api/v1/stations/imbalance` | Station starts-minus-ends imbalance |
| `GET /api/v1/data-quality` | File provenance and data-quality totals |

Common filters are `from`, `to`, `riderType`, and `bikeType`. Dates use
`yyyy-MM-dd`. Paginated endpoints accept `limit` from 1 to 100 and a
non-negative `offset` up to the configured maximum (10,000 by default).
Empty pages retain the total number of matching groups.

The source timestamps represent **America/New_York local wall-clock time**,
without a UTC offset. The API preserves these local values; it does not convert
them into UTC instants. Date ranges include both requested calendar days, using
an inclusive start and an exclusive boundary at midnight after `to`. Defaults
use the loaded date bounds, and ranges are limited to 366 days by default.

`/rides/summary` reports average, p50 (`medianDurationSeconds`), p90, and p95
durations in seconds, `roundTripCount` (matching non-null station IDs), and
`ridesOver24Hours`. Outliers remain in every analysis. `/routes` excludes round
trips by default; set `includeRoundTrips=true` to include them.

`/stations` accepts `direction=start|end`, `sort=rideCount|averageDuration`,
`order=asc|desc`, and optional `stationName`. Station names match exactly in the
selected direction, accept up to 200 printable characters, and are bound as
SQL values. `/routes` supports the same sort and order choices. Time series
accepts `interval=hour|day`; activity accepts `groupBy=hourOfDay|dayOfWeek`.

Invalid parameters return JSON with status `400`. Pool saturation or unavailable
data returns `503` with `Retry-After: 1`. Unexpected failures return a generic
JSON `500` with an `X-Correlation-ID` that also appears in sanitized logs.
Aggregates with no matches return `200`, and unknown routes return `404`.

`/data-quality` reports discovered/loaded files, per-file and total row counts,
missing fields, duplicate IDs, invalid durations, `invalidRows`, and outliers.
`invalidRows` is zero for a successfully loaded dataset because malformed or
invalid rows abort the transaction rather than being retained or skipped.

Examples:

```sh
curl http://localhost:8080/api/v1/rides/summary
curl 'http://localhost:8080/api/v1/rides/timeseries?interval=day&riderType=member'
curl 'http://localhost:8080/api/v1/stations?direction=start&sort=rideCount&limit=10'
curl 'http://localhost:8080/api/v1/routes?bikeType=electric_bike&limit=10'
curl http://localhost:8080/api/v1/data-quality
```

All request values are bound with JDBC prepared statements. Grouping, sorting,
and direction options are mapped through fixed enum allowlists; the API does
not accept arbitrary SQL, identifiers, or file paths.

## Observability

- Readiness: `GET /q/health/ready`
- Liveness: `GET /q/health/live`
- Prometheus metrics: `GET /q/metrics`

Application code logs through SLF4J. Quarkus routes those messages to its JBoss
Log Manager backend so application and framework logs use one configuration.

## Resource Limits

All settings use the `citibike` prefix in `application.properties` or equivalent
Quarkus environment/system-property configuration. Invalid limits fail startup.

| Setting | Default | Accepted bounds |
| --- | --- | --- |
| `jdbc.max-connections` | 4 | 1–32 query connections, plus the anchor |
| `jdbc.acquire-timeout` | 500ms | 1ms–5s |
| `jdbc.query-timeout` | 2s | 1ms–60s; JDBC rounds up to whole seconds |
| `duckdb.threads` | 4 | 1–32 |
| `duckdb.memory-limit` | 512MB | Positive integer with KB, MB, or GB suffix |
| `duckdb.max-temp-directory-size` | 512MB | Positive integer with KB, MB, or GB suffix |
| `api.max-date-range-days` | 366 | 1–366 |
| `api.max-page-size` | 100 | 1–100 |
| `api.max-offset` | 10000 | 0–1000000 |

`csv.resource-pattern` defaults to `*.csv` and filters the generated index.
A matching dataset must contain at least one ride. `csv.fail-on-empty=true`
rejects individual header-only files; setting it to false permits them alongside
nonempty files. `csv.fail-on-duplicate-ride-id=true` rejects duplicate IDs; if
explicitly disabled, duplicates are retained and reported in data quality.

CSV extraction and DuckDB spill files use an application-owned temporary
directory that is removed on shutdown. Readiness uses a pool lease with the
acquisition timeout and a `SELECT 1` with the configured statement timeout.
Liveness performs no database queries.

## Test

```sh
mvn test
```

Exact API and repository assertions use small synthetic CSVs. A separate smoke
test checks the bundled real dataset. Coverage includes transaction rollback,
malformed rows and headers, duplicate IDs/names, provenance and textual station
IDs, dependency isolation, deterministic sorting, empty-page totals, strict
validation, prepared station searches, real statement cancellation, connection
cleanup, saturation/503, readiness, bounded metric tags, and sanitized logs.

Run the complete build including a fast-JAR integration test:

```sh
mvn clean verify
```

Verify automatic discovery after adding a CSV, in both fast-JAR and dev modes:

```sh
python3 scripts/verify_resource_discovery.py
```

This script uses a temporary project copy, adds one compatible top-level CSV and
an incompatible nested CSV, and verifies that only the top-level file is added.
It stops its application processes and removes the temporary copy afterward.

See [PLAN.md](PLAN.md) for the architecture, design decisions, and acceptance
criteria.

## Latest NYC Archive Downloader

An optional component downloads the latest published NYC monthly archive without
extracting it or loading it into DuckDB. The analytics API continues to use its
bundled CSVs. Enable it with:

```sh
java --enable-native-access=ALL-UNNAMED \
  -Dcitibike.download.enabled=true \
  -Dcitibike.download.data-directory=/var/lib/citibike/downloads \
  -jar target/quarkus-app/quarkus-run.jar
```

Discovery uses the public S3 ListObjectsV2 API, follows all pages, and accepts
only full object keys matching `^[0-9]{6}-citibike-tripdata\.zip$` with a valid
Gregorian year/month (0001–9999, 01–12). The greatest `YYYYMM` wins, independently
of LastModified or today's date. Jersey City, annual, `.csv.zip`, and prefixed
keys are excluded. There is no historical download, extraction, or ingestion.

The default daily check runs at 04:00 America/New_York, with an asynchronous
startup check. Only a changed latest object needs a body download. Nothing runs
while the application is stopped; restarting checks the latest object once and
intentionally skips intervening months. Use `schedule=off` to disable periodic
checks. The internal CDI method `DownloadCoordinator.checkLatest()` provides a
manual trigger using exactly the same pipeline. No public mutation endpoint is
provided. Read sanitized status at `GET /api/v1/download/status`; it cannot
start downloads. Check outcomes are also counted by `citibike.download.checks`
with fixed `outcome` tags. Download errors do not affect DuckDB readiness.

All settings below use the prefix `citibike.download.`. Byte limits are numeric
bytes; duration values use SmallRye duration syntax (`10s`, `45m`, `24h`).

| Setting | Default |
| --- | --- |
| `enabled` | `false` |
| `data-directory` | `./data/citibike-downloads` |
| `schedule` | `0 0 4 * * ?` |
| `time-zone` | `America/New_York` |
| `check-on-startup` | `true` |
| `connect-timeout`, `read-timeout`, `operation-timeout` | `10s`, `60s`, `45m` |
| `max-attempts`, `max-backoff`, `max-rediscoveries` | `3`, `60s`, `2` |
| `max-download-bytes` | `4294967296` (4 GiB) |
| `max-listing-pages`, `max-listing-response-bytes` | `100`, `4194304` (4 MiB/page) |
| `min-free-disk-bytes` | `8589934592` (8 GiB reserve) |
| `retained-generations`, `retained-attempts` | `2`, `100` |
| `cleanup-grace-period` | `24h` |

Use a durable, trusted, locally owned directory with reliable file locking,
atomic moves, and directory/file sync. One instance owns a directory per check;
other processes receive `BUSY`. Independent deployments require separate
volumes/directories. Cross-host sharing is unsupported. The production origin
is fixed to `https://s3.amazonaws.com/tripdata`; a package-private constructor
permits a local HTTP stub in tests only. Redirects are rejected.

Transfers stream to unique `.part` files, pin ETag/version when available, check
length and limits, compute SHA-256, and compare a full-object upstream SHA-256
when supplied. ETag is an opaque validator, not a checksum. Local SHA-256 alone
cannot independently prove upstream correctness. Transient HTTP/transport
failures have bounded application retries; the JDK transport may reconnect an
idempotent GET internally. 404/412 causes bounded rediscovery without selecting
an older fallback. No matching object produces `NO_MATCH` and leaves prior
successful bytes available.

`active.json` atomically references an immutable `archive-<id>.zip` generation.
It records the selected key/month, optional upstream version/ETag, size, hash,
timestamps, and successful attempt ID. `attempt-<id>.json` records outcomes and
sanitized reasons; errors never overwrite active success. Revised objects
publish a new generation only after verification; identical bytes reuse the
existing generation with updated metadata. Startup recovery occurs on the next
check: stale `.part` files are removed, pending attempts are reconciled with the
committed manifest, and corrupt attempt records are quarantined with bounded
retention. Unreferenced orphan archives are cleaned after the grace period.
Unchanged skips require validating the retained file's hash, which incurs a
sequential disk read. Missing validators cause a fresh latest-object download.

Attempt history is bounded by count. Archive cleanup keeps the active generation
and observes the grace period, so temporarily more than the configured number
of archives can remain. Disk reserve checks protect space for new downloads;
monitor volume growth and failure status. A full/corrupt/unwritable volume
prevents publication while preserving the existing manifest.

For backup, pause checks/cleanup, copy `active.json` and its referenced archive
together, and verify the recorded SHA-256 after restore. Consumers should read
the manifest once, then open that generation. Complete reads within the cleanup
grace period or pause cleanup during long reads/backups. Do not use this archive
cache as the application analytics database.

Downloader verification is part of `mvn verify`: local stub tests cover strict
pagination/selection, delayed publication, retries, integrity failures,
idempotency, revisions, locking, atomic manifests, retention, and recovery.
Packaged subprocess tests cover restart, abrupt process termination, and
cross-process lock exclusion without downloading public S3 archives.
