# Monthly Citi Bike ingestion

Production mode accumulates monthly trip data in persistent DuckDB. A serialized
background writer downloads and validates a candidate month, then replaces that
city/archive-month partition, source metadata, and job outcome in one database
transaction. HTTP readers keep seeing committed data throughout candidate work.

## Storage and startup

| Option | Benefits | Costs and limitations |
| --- | --- | --- |
| In-memory DuckDB rebuilt from retained inputs | Simple demo; reproducible loading; no durable query state to maintain. | Reload time and RAM grow with history. Back up inputs separately; each instance rebuilds its own copy. |
| Persistent file-backed DuckDB (implemented) | Committed data and metadata survive restarts together; no CSV reload; native analytical tables and transactional month replacement. | Durable local disk, schema migrations, ownership, WAL, backups, spill and source-cache space. One writable process; imports and queries share CPU/I/O. |
| DuckDB over retained Parquet | Compressed immutable partitions, column pruning, portable data, independently scalable readers. | Adds conversion, manifests, atomic generation publication, and retention coordination. Deferred. |

DuckDB itself is persistent when its JDBC URL specifies a file. None of these
options requires an external database service. Local compute, memory, disk,
source archives, and backups determine cost. PLAN.md contains the detailed
comparison, including concurrency and backup trade-offs. Reconsider Parquet
when portable cold history or many independent readers outweigh native-table
simplicity; multiple independent writers require a different architecture.

Production defaults to `citibike.storage.mode=file`, with `citibike.duckdb` under
`./data/citibike`. The application creates versioned schemas and owns an exclusive
`owner.lock`. A second writable process fails startup. Use one application
instance per durable local volume; shared network-filesystem writers and live
read-only access to the writable database from another process are unsupported.
Replicas need separately published snapshots or immutable Parquet generations.

A new file database has no bundled rides. Readiness is DOWN until a valid month
commits, while liveness and authenticated job status remain available. Initial
catch-up runs asynchronously. An existing valid dataset becomes ready on restart
without waiting for downloads. Scheduled/validation failures leave existing data
and readiness available; storage errors and query-pool exhaustion still affect
health. Readiness is not a guarantee that every recent month has been published.

Development/test profiles default to `memory`, retaining the bundled demo. For
an explicit packaged demo:

```sh
java --enable-native-access=ALL-UNNAMED \
  -Dcitibike.storage.mode=memory -Dcitibike.ingestion.enabled=false \
  -jar target/quarkus-app/quarkus-run.jar
```

An explicit `citibike.storage.bootstrap-bundled=true` can seed an empty file
store. Bundled bootstrap must contain one calendar month of NYC data. Its rides
and source archive receive city/month provenance, so a later complete monthly
import replaces the sample rather than duplicating it. Bootstrap is skipped
when committed partitions already exist.

## Scheduling and publication

The monthly cron defaults to the 6th at 04:00 America/New_York and requests the
previous complete calendar month. This is a polling choice, not a promised
upstream publication date. Daily catch-up runs at 04:30, rotating through the
last three complete months with at most two city/month requests per run. The
rotation ensures a small cap does not repeatedly recheck only the oldest month.
NYC is the default; `include-jersey-city=true` explicitly permits JC imports.

All triggers use the same durable queue and single worker. Duplicate queued or
running requests reuse their job. Missing publication is WAITING, with a daily
eligible retry, for up to 45 days after month end. It then becomes OVERDUE;
automatic catch-up stops retrying that overdue job, while manual retry remains
available. Download failures receive bounded HTTP retries; schema, duplicate,
unsafe-archive, and quota errors are terminal for that attempt. A later scheduled
recheck or manual request can observe an upstream correction.

No jobs execute while the application/machine is stopped. Startup marks running
attempts INTERRUPTED and requeues their jobs; committed work is not reimported.
Pending work resumes even when `check-on-startup=false`. That setting disables
new startup catch-up, not durable recovery. Recent committed months are rechecked
for revisions; older corrections require explicit backfill/manual requests.
Set the monthly/catch-up cron to `off` to disable those new scheduled requests;
the lightweight queue poll still resumes eligible durable jobs.

## Discovery, safety, and replacement

Discovery uses anonymous paginated S3 ListObjectsV2, with relevant year prefixes,
namespace-aware XML, disabled external entities, bounded pages/response sizes,
and opaque continuation tokens. It recognizes NYC monthly `.zip`/`.csv.zip`,
JC counterparts, and NYC annual envelopes. It never scrapes the HTML index or
guesses an object URL. Multiple monthly candidates are an ambiguity failure.
An annual envelope is used only when a discovered monthly object is absent;
only the requested nested monthly ZIP is selected. Verified parent downloads
are reused across backfills and checked for local checksum integrity.

The shared HTTP transport streams to `.part`, pins an optional version ID or
ETag, enforces advertised/actual sizes, and computes SHA-256. It compares a
compatible upstream full-object checksum when available. ETags are opaque
validators; local SHA-256 alone is not upstream authenticity proof. Completed
files are synced and atomically renamed before database references commit.
Version races trigger bounded rediscovery. HTTP retries use bounded backoff;
the JDK may reconnect an idempotent GET internally.

ZIP64 extraction supports at most two layers. All names are checked before
extracting selected data; traversal, absolute/drive/backslash paths, symlinks,
nonregular Unix entries, encryption, normalized collisions, unknown layouts,
and unsupported compression methods fail safely. Only stored/deflated data is
supported. Known macOS sidecars are ignored. Extracted files use generated local
names, retaining archive-relative names only as provenance. Selected streams
have CRC, actual expanded-byte, entry, ratio, disk, and deadline checks. Per-job
budgets include materializing the nested ZIP. Header reads and ZIP member names
are also bounded. Workspaces and stale partial/spill files are recovered/cleaned.

Unchanged committed versions are no-ops after retained-source verification.
A valid revised archive replaces the entire city/archive-month partition,
including removed rides and all CSV metadata. Other partitions are preserved.
Global ride-ID duplicates, including cross-city collisions, reject the candidate
without silently dropping records. A broken revision keeps all previous rows
and metadata. Annual changes use the nested-month checksum, so unaffected
monthly materializations are not replaced unnecessarily.

## Validation policy and real archive evidence

Memory/demo loading retains the original strict policy, including required start
coordinates. File-mode monthly imports use **monthly-v2**, supported by schema
migration 2:

- Require the exact current 13-column schema; quoted headers, BOMs, textual
  station IDs and fractional timestamps are supported. Historical legacy schemas
  fail with an actionable conversion diagnostic.
- Retain absent start coordinates only when **both** latitude and longitude are
  absent. Never fabricate zeros. Unpaired, invalid, out-of-range, or malformed
  coordinates still reject the complete month.
- The partition belongs to the **published archive month**, not necessarily the
  ride's start month. A ride must overlap the requested calendar month:
  `ended_at >= month_start` and `started_at < next_month_start`. This preserves
  prior-month starts and next-month ends when present. Unrelated months fail.
- Continue rejecting invalid required IDs/timestamps/categories, duplicates,
  and non-positive durations. `csv.fail-on-empty=false` permits header-only
  members alongside nonempty CSVs; an entirely empty month is still rejected.
  Preserve long-ride outliers and nullable station/
  end-coordinate fields. Never skip bad rows or partially publish a month.

The complete published September 2026 NYC archive was discovered and audited on
October 12, 2026: 972,151,835 downloaded bytes; five CSVs; **4,982,855 rides**;
2,917 paired missing start coordinates; 549 prior-month starts; no rides outside
the month's overlap; no duplicate IDs, malformed timestamps, unsupported
categories, non-positive durations, unpaired coordinates, or out-of-range
coordinates. There were 1,070 rides over 24 hours. These observations justify the
two policy changes above; the bundled 1,500-row sample alone did not.

The final full-pipeline smoke replayed that complete archive through a streaming
local HTTP stub and retained all 4,982,855 rows with zero invalid rows. It
committed in about 10.11 seconds and retained
1,232,210,971 bytes including source archive and database.
Summary/data-quality queries took about 135/237 ms under the existing
2-second public query timeout. Peak RSS was 1,625,096,192 bytes (about
1.63 GB), with a 512 MB DuckDB setting, four threads and an 8 GB spill ceiling.
Sampled peak runtime files were 2,995,420,024 bytes;
sampled spill was 863,862,784 bytes. Sampling every 200 ms measures
logical file sizes, not allocated filesystem blocks. This validates increasing
the production spill budget beyond the demo's 512 MB. These are local results
for one archive, not universal capacity or concurrency promises.
See [the recorded aggregate profile](ingestion-profile-2026-10-12.json) for
source ETag/SHA-256, all counts, settings, timing, and resource evidence.

## Configuration

Storage properties use `citibike.storage.*`; ingestion properties below use
`citibike.ingestion.*`. Byte limits accept numeric bytes; durations accept `10s`,
`30m`, `2h`, and `30d`. Production spill defaults to 8 GB; development/test profiles retain
512 MB. Existing public query/acquisition limits remain unchanged.

| Property | Default |
| --- | --- |
| `enabled`, `admin-enabled`, `include-jersey-city` | `true`, `false`, `false` |
| `source-uri` | `https://s3.amazonaws.com/tripdata` |
| `monthly-cron`, `catch-up-cron`, `time-zone` | `0 0 4 6 * ?`, `0 30 4 * * ?`, `America/New_York` |
| `check-on-startup`, `lookback-months`, `jobs-per-run` | `true`, `3`, `2` |
| `catch-up-start-month` | Optional lower bound within the bounded recent window |
| `backfill.from`, `backfill.to` | Optional paired inclusive `YYYY-MM` bounds |
| `publication-wait-days`, `max-backfill-months`, `max-queued-jobs` | `45`, `24`, `100` |
| `connect-timeout`, `read-timeout`, `download-timeout` | `10s`, `60s`, `30m` |
| `max-attempts`, `max-backoff`, `max-rediscoveries` | `3`, `60s`, `2` |
| `max-download-bytes`, `max-entry-bytes` | `4294967296` (4 GiB each) |
| `max-expanded-bytes` | `17179869184` (16 GiB per job) |
| `max-entries`, `max-zip-layers`, `max-compression-ratio` | `1000`, `2`, `100` |
| `max-listing-pages`, `max-listing-response-bytes`, `max-discovered-objects` | `100`, `4194304`, `100000` |
| `sql-timeout`, `job-timeout` | `30m`, `2h` |
| `cache-quota-bytes`, `min-free-disk-bytes` | `68719476736` (64 GiB), `8589934592` (8 GiB reserve) |
| `superseded-retention` | `30d`, requiring a verified backup receipt |

The source URI is a trusted deployment setting: HTTPS, or loopback HTTP for
local fixtures. Requests cannot provide sources, filenames, SQL, or paths.
Preflight includes candidate bytes, the configured expanded-byte budget, and
free-space reserve. Quota failures defer/reject work while retaining active
sources. Use an absolute owned data directory on a durable local volume.

## Protected management API

Routes require the `ingestion-admin` role and `admin-enabled=true`; they never
allow anonymous writes. The packaged application includes the local Basic
identity provider with **no configured users**. Supply credentials/roles from
secure deployment configuration; do not commit them. For a local setup, with
`CITIBIKE_ADMIN_PASSWORD` already set securely in the environment:

```sh
java --enable-native-access=ALL-UNNAMED \
  -Dcitibike.ingestion.admin-enabled=true \
  '-Dquarkus.security.users.embedded.users.operator=${CITIBIKE_ADMIN_PASSWORD}' \
  -Dquarkus.security.users.embedded.roles.operator=ingestion-admin \
  -jar target/quarkus-app/quarkus-run.jar
```

Basic credentials require a protected local connection or TLS at deployment.
For larger deployments, use an appropriate Quarkus identity provider and map its
roles to `ingestion-admin`; the resource role requirement remains the same.

| Route | Purpose |
| --- | --- |
| `POST /api/v1/admin/ingestion/months` | `{ "month": "2026-09", "city": "NYC" }`; `202` with a durable job. |
| `POST /api/v1/admin/ingestion/backfills` | `{ "from": "2026-08", "to": "2026-09", "city": "NYC" }`; `202` with jobs. |
| `GET /api/v1/admin/ingestion/jobs` | Bounded `limit`/`offset`, optional fixed `status` filter. |
| `GET /api/v1/admin/ingestion/jobs/{id}` | Attempts, status, timestamps, counts, bounded reason/action hint. |
| `GET /api/v1/admin/ingestion/status` | Active coverage, row/file counts, missing start-coordinate counts, pending work and recent failures/overdue publication. |

`city` defaults to NYC; JC is rejected unless explicitly enabled. Current/future
months and over-wide backfills return stable JSON `400`; writer/queue saturation
returns bounded `503`/Retry-After. Authentication challenges can have an empty
body (`401`); insufficient roles return `403`. Paths/raw exceptions are not in
job JSON. Example commands prompt for the operator password rather than putting
it in shell history:

```sh
curl --user operator -H 'Content-Type: application/json' \
  -d '{"month":"2026-09"}' http://localhost:8080/api/v1/admin/ingestion/months
curl --user operator http://localhost:8080/api/v1/admin/ingestion/status
```

Analytics routes retain their response fields, prepared queries and validation.
`/data-quality` obtains metadata and row statistics from one read transaction.
Dataset-bound default dates remain unchanged: history wider than 366 days
requires explicit `from`/`to` windows; an over-wide default still returns `400`.
The missing-coordinate count is exposed in management coverage; analytics
`missingValues` retains its existing fields and accepted paired absence is not
classified as an invalid row.

## Metrics, backups, and retention

The **Monthly ingestion** Grafana dashboard is separate from the latest-only
archive downloader. New metrics expose enabled/running state, pending jobs,
last committed timestamp, outcome counts/duration, and downloaded/expanded byte
totals. Fixed outcome labels are `committed`, `unchanged`, `waiting`, `failed`,
and `overdue`; filenames/months/hashes/job IDs are never tags. Unknown freshness
is NaN, and disabled panels are filtered out. Existing CSV row/file gauges track
all committed sources; startup duration means dataset initialization in file
mode, which restores metadata rather than reloading CSVs.

For backup, stop the application cleanly. The POSIX Python tool takes the same
ownership lock, refuses a remaining WAL, copies the database and retained
archives, verifies checksums, and atomically publishes a backup directory:

```sh
python3 scripts/backup_ingestion.py /var/lib/citibike /backups/citibike-snapshot
python3 scripts/backup_ingestion.py /backups/citibike-snapshot \
  /var/lib/citibike-restored --restore
```

Destinations must not exist; restart using the restored data directory. If a WAL
remains after interruption, start the database to recover it and shut down
cleanly before backup. Never copy only an open DuckDB file and ignore its WAL.
Backups contain all retained sources, including audit versions. The tool writes
an owned backup receipt after verifying the complete copy. Cache cleanup uses
that receipt, re-verifies the backup, waits for retention, and removes only
superseded bytes with no active partition or queued/running/waiting job pin.
Terminal audit identities/checksums remain in the database and their bytes remain
in backup. Active sources are never evicted to make room for an incoming month.
Missing/corrupt/incomplete backups disable cleanup. Database deletions do not
promise that its physical file shrinks. Monitor filesystem space, cache growth,
queue pressure, failed/overdue outcomes, and freshness independently of readiness.

## Verification

```sh
mvn clean verify
python3 scripts/verify_monthly_ingestion.py
python3 scripts/verify_resource_discovery.py
```

Ordinary tests use small ZIP fixtures and local HTTP stubs. Packaged subprocess
checks terminate before/after commit and reopen the WAL-backed database, check
cross-process ownership, and verify independent month commits. The packaged
API script tests initial/later readiness, authenticated backfill/manual requests,
restart without reimport, corrections, failed schema replacement, metadata
snapshots, and verified backup/restore. It generates ephemeral credentials,
creates temporary storage, and cleans up its application/stub processes.

`ArchiveProfileMain` and `FullIngestionMain` are opt-in test helpers for full-data
audits/smoke imports, excluded from ordinary CI. Use a previously programmatically
discovered/downloaded archive on a suitably sized temporary volume; their output
contains aggregate counts and timing, never ride/station values. Record actual
source key/ETag/SHA-256 and settings with the measured results.

Final verification on October 12, 2026: `mvn clean verify` passed 102
unit/application tests and 3 integration tests, with no failures or skips.
Packaged API ingestion/backup/restore, memory-mode CSV resource discovery, six
Grafana dashboards/59 PromQL queries, and the full-source audit/smoke import all
passed. Large profiling archives/databases and verification services were
cleaned up; only aggregate evidence is retained in the repository.
