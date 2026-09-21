# duckdb-learn

A Quarkus REST API for exploring Citi Bike ride data with DuckDB through JDBC.

At startup, the application discovers every top-level CSV matching
`src/main/resources/*.csv`, validates that the files use the expected Citi Bike
schema, and loads them transactionally into a shared in-memory DuckDB database.

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

1. `CsvResourceCatalog` reads the index and resolves each resource without
   accepting user-controlled paths.
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
non-negative `offset`.

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

## Test

```sh
mvn test
```

The test suite covers real-data queries, multi-file loading, validation,
pagination, injection-shaped inputs, health checks, and metrics.

See [PLAN.md](PLAN.md) for the architecture, design decisions, and acceptance
criteria.
