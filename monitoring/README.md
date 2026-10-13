# Local Citi Bike monitoring

This stack monitors the Quarkus application running **outside Docker**, scraping
`/q/metrics`. It contains pinned Prometheus 3.15.0 and Grafana 13.2.3 containers,
a provisioned datasource, and six dashboards in the **Citi Bike** folder.

## Start

Requirements: Docker Desktop or Docker Engine with Docker Compose, Java 25,
Maven 3.9+, Python 3 for verification, and OpenSSL for generating a credential.
Run commands from the repository root.

Start the application in one terminal:

```sh
mvn clean package
java --enable-native-access=ALL-UNNAMED \
  -Dquarkus.http.host=0.0.0.0 \
  -jar target/quarkus-app/quarkus-run.jar
```

The latest-only downloader remains disabled unless explicitly enabled. The
packaged application uses persistent monthly ingestion by default; use memory
mode with ingestion disabled for a small bundled monitoring demo. Generate credentials
once and start monitoring in another terminal:

```sh
test -f monitoring/.env || cp monitoring/.env.example monitoring/.env
mkdir -p monitoring/.secrets
chmod 700 monitoring/.secrets
test -s monitoring/.secrets/grafana-admin-password || \
  openssl rand -hex 32 > monitoring/.secrets/grafana-admin-password
chmod 444 monitoring/.secrets/grafana-admin-password

docker compose --env-file monitoring/.env -f monitoring/compose.yaml up -d
```

The credential file is readable by the container's unprivileged Grafana user;
the enclosing host directory is owner-only. The file is mounted read-only as a
Compose secret. Local credentials, `.env`, and target overrides are ignored by
Git. Do not regenerate an existing credential file casually: Grafana uses it
only to initialize a new database. A persisted Grafana volume retains its
existing login; change its password through Grafana administration instead.

Open [Grafana](http://localhost:3000), sign in as `admin` (or the configured
`GRAFANA_ADMIN_USER`) using the generated file's value, and open **Dashboards →
Citi Bike**. [Prometheus](http://localhost:9090) provides target and query
inspection. Both container ports bind to host loopback. Dashboard and datasource
provisioning requires no manual imports. Grafana datasource requests go to
`http://prometheus:9090` on the Compose network, not the host-published port.

## Application connectivity and configuration

`monitoring/prometheus/targets.json` defaults to `host.docker.internal:8080`.
Docker Desktop provides host connectivity; `extra_hosts: host-gateway` also
supports Docker Engine on Linux. The application must listen on an address
reachable from the container, normally `0.0.0.0`, and host firewall rules must
allow access from the Docker bridge. Exposing the application on all interfaces
is for this local setup; restrict access appropriately for your environment.

For another port or host, copy the target list to the ignored local override:

```sh
cp monitoring/prometheus/targets.json monitoring/prometheus/targets.local.json
```

Edit its `targets` array, then set
`PROMETHEUS_TARGETS_FILE=./prometheus/targets.local.json` in `monitoring/.env`.
Paths in Compose configuration are relative to `monitoring/`. Re-run `up -d --force-recreate`
after changing the environment, secret path, or mount source. Prometheus file discovery polls
every 30 seconds; updates to the mounted target file are picked up automatically.
Select the desired **Application instance** at the top of each dashboard.

Other `.env` settings: `GRAFANA_PORT` (3000), `PROMETHEUS_PORT` (9090),
`GRAFANA_ADMIN_USER` (admin), and `GRAFANA_ADMIN_PASSWORD_FILE` (the local secret
path). Published port changes do not change the datasource's internal URL.
The application is not managed or stopped by this Compose project.

## Dashboards and interpretation

| Dashboard | Coverage |
| --- | --- |
| API traffic and latency | Request rates by endpoint/status, 4xx+5xx fraction, mean and histogram p95/p99 latency. |
| DuckDB queries and limits | Query rates, latency and failures, active/maximum query connections, configured memory/spill/thread ceilings and timeouts. |
| CSV loading | Loaded rides/files, mean startup and per-file loading duration, failure totals since process start. |
| Latest archive downloads | Enabled/active state, last completed check result/age, last archive publication age, outcome counts and failure totals. |
| Monthly ingestion | Pending/running jobs, committed freshness, outcomes/duration and transferred/expanded bytes. |
| JVM and process | Heap/non-heap usage, heap maximum, process/system CPU, GC pause rates/time and process uptime. |

Every dashboard distinguishes **Prometheus scrape reachability** from
**Analytics readiness**. Readiness performs the existing bounded DuckDB probe
on each metrics scrape, with the configured acquisition/query timeouts. A failed
download check does not change analytics readiness.

A zero downloader-enabled gauge displays **Disabled**; its activity/freshness
panels are empty. Unknown check/publication timestamps are NaN and display
**Unknown / no samples**, never zero age or success. The archive publication
age increases on unchanged checks; use the separate last-check age to see recent
verification. Durable publication state becomes visible after the first check
following a restart. Downloader failures remain represented by last-check
result and cumulative failure counter even when prior valid data is retained.

Rates require multiple scrapes (15-second interval). Latency and error-fraction
panels are absent when there are no requests, rather than showing zero latency
or a healthy fraction. Histogram quantiles are estimates over fixed buckets
from 1ms through 60s. Startup CSV metrics use cumulative sum/count because a
single startup event may precede the first scrape. In file mode startup restores
metadata; it does not reparse the archived CSVs. Monthly job duration is separate. Counters reset on application
restart; they are not durable ingestion history. Missing metrics or an
unreachable application produces absent dependent series, not invented zeros.

Resource gauges are **configured limits**, not DuckDB memory/disk usage. JVM
memory does not include all native DuckDB allocations. No metric label contains
archive keys/months, checksums, attempt IDs, ride IDs, or station names. Existing
fixed operation/outcome/reason/category tags remain bounded.

## Persistence and shutdown

Named volumes retain Grafana's database and Prometheus samples. Prometheus
retains at most 15 days or approximately 2 GB, whichever limit is reached first;
allow extra filesystem space for WAL/head blocks. Dashboard JSON and datasource
provisioning remain the source of truth; UI dashboard edits are disabled.
Grafana plugin preinstallation/update checks and analytics reporting are disabled.

```sh
# Stop and remove this project's containers/network; preserve data volumes.
docker compose --env-file monitoring/.env -f monitoring/compose.yaml down

# Start again with retained samples and credentials.
docker compose --env-file monitoring/.env -f monitoring/compose.yaml up -d
```

Adding `--volumes` to `down` deletes monitoring history and Grafana's database.
Use it only when intentionally resetting the local setup. Application datasets
and downloader archives live outside these monitoring volumes.

## Validate

```sh
docker compose --env-file monitoring/.env -f monitoring/compose.yaml config --quiet

docker compose --env-file monitoring/.env -f monitoring/compose.yaml \
  run --rm --no-deps --entrypoint promtool prometheus \
  check config /etc/prometheus/prometheus.yml

docker compose --env-file monitoring/.env -f monitoring/compose.yaml \
  run --rm --no-deps \
  -v "$PWD/monitoring/prometheus/query-tests.yml:/tmp/query-tests.yml:ro" \
  --entrypoint promtool prometheus test rules /tmp/query-tests.yml

mvn verify
python3 scripts/verify_monitoring.py --generate-traffic
```

The live verifier reads the credential file without printing it. It checks
Grafana health, datasource connectivity, all five provisioned dashboards,
exported metric references, and every panel query against the actual Prometheus
engine and through Grafana’s datasource query API. Synthetic successful and invalid API requests populate rates across
multiple scrapes. Use `--application-url`, `--prometheus-url`, `--grafana-url`,
`--instance`, `--admin-user`, and `--password-file` for overrides; increase
`--wait-seconds` if samples have not yet arrived. Omit `--generate-traffic` to
perform read-only checks without requiring positive request rates.

The promtool fixtures cover disabled downloading, absent metrics, zero traffic,
and a failed download check while analytics remains ready. Regenerate dashboard
JSON after editing its source with:

```sh
python3 scripts/generate_monitoring_dashboards.py
```

Update associated query fixtures if their expressions change, then repeat
validation. There are no additional Python library requirements.

## Troubleshoot

- **Target DOWN:** inspect Prometheus **Status → Target health**, confirm the
  application is running and `/q/metrics` is reachable, then check the configured
  port, listening address, Docker host mapping, and firewall.
- **Datasource failure:** confirm both containers are running and the datasource
  URL is `http://prometheus:9090`; the host URL cannot identify the Prometheus
  container from Grafana.
- **No latency data:** send API requests for at least two scrape intervals and
  confirm the correct instance. Do not interpret no samples as zero latency.
- **No histogram buckets:** rebuild/restart the application with the monitoring
  instrumentation; old application artifacts export only timer summaries.
- **No downloader freshness:** check enabled state and operational status at
  `/api/v1/download/status`; no completed/verified publication is unknown.
- **Cannot log in:** verify the admin username and the credential used when the
  Grafana volume was first initialized. Replacing the file does not reset an
  existing database password.
- **Provisioning errors:** use `docker compose --env-file monitoring/.env -f
  monitoring/compose.yaml logs grafana prometheus` and verify mount paths/files.

## Recorded verification

On October 12, 2026, `mvn verify` passed 72 unit/application tests and 2
integration tests. Compose/Prometheus configuration checks and 11 promtool
expression checks passed. Docker Desktop/macOS verification loaded all five
dashboards and evaluated 49 panel expressions against a packaged application
running outside Docker, through both Prometheus and Grafana. The same checks
passed after recreating containers with persisted volumes and credentials.
Linux host-gateway configuration is included; the recorded run used macOS.

The subsequent monthly-ingestion implementation adds a sixth dashboard. Live
verification checked all six dashboards and 59 panel expressions; promtool
fixtures now include disabled ingestion (14 checks across five scenarios).
