#!/usr/bin/env python3
"""Regenerate provisioned dashboards; metrics were checked against /q/metrics."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DS = {"type": "prometheus", "uid": "citibike-prometheus"}
S = 'job="citibike",instance="$instance"'
UP = f'up{{{S}}} == 1'
ENABLED = f'citibike_download_enabled{{{S}}} == 1'

def metric(name, extra=""):
    return f'{name}{{{S}{"," + extra if extra else ""}}}'

def rate(name, extra=""):
    return f'rate({metric(name, extra)}[$__rate_interval])'

def live(expr):
    return f'({expr}) and on() ({UP})'

def download(expr):
    return f'({expr}) and on(job,instance) ({ENABLED}) and on(job,instance) ({UP})'

def panel(title, expr, unit="short", kind="timeseries", description="", legend="{{instance}}", mappings=None):
    p = {"title": title, "type": kind, "datasource": DS, "description": description,
         "targets": [{"refId": "A", "expr": expr, "legendFormat": legend, "instant": kind == "stat"}],
         "fieldConfig": {"defaults": {"unit": unit, "noValue": "Unknown / no samples",
                          "thresholds": {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}}, "overrides": []},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}}
    if kind == "stat":
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "values": False}, "colorMode": "value", "graphMode": "none", "textMode": "auto"}
    if mappings:
        p["fieldConfig"]["defaults"]["mappings"] = [{"type": "value", "options": {str(v): {"text": text, "color": color} for v, (text,color) in mappings.items()}}]
    return p

NAMES = [("api", "API"), ("duckdb", "DuckDB"), ("csv", "CSV loading"), ("downloads", "Archive downloads"), ("jvm", "JVM"), ("ingestion", "Monthly ingestion")]

def dashboard(key, title, description, panels):
    prefix = [panel("Prometheus scrape", f'up{{{S}}}', kind="stat", description="Scrape reachability only; not analytics readiness.", mappings={0:("Unreachable","red"),1:("Reachable","green")}),
              panel("Analytics readiness", metric("citibike_analytics_ready") + f' and on(job,instance) ({UP})', kind="stat", description="One bounded DuckDB readiness probe per metrics scrape; independent of downloader status.", mappings={0:("Not ready","red"),1:("Ready","green")})]
    all_panels = prefix + panels
    for i,p in enumerate(all_panels):
        p["id"] = i+1
        p["gridPos"] = {"x": (i%2)*12, "y": (i//2)*8, "w":12, "h":8}
    d = {"uid": "citibike-"+key, "title": "Citi Bike / "+title, "description": description,
         "schemaVersion": 39, "version":1, "editable":False, "tags":["citibike"], "timezone":"browser",
         "refresh":"15s", "time":{"from":"now-1h","to":"now"}, "panels":all_panels,
         "links":[{"title":t,"type":"link","url":"/d/citibike-"+k,"keepTime":True,"includeVars":True} for k,t in NAMES],
         "templating":{"list":[{"name":"instance","type":"query","label":"Application instance","datasource":DS,
             "query":'label_values(up{job="citibike"}, instance)',"refresh":1,"multi":False,"includeAll":False,"sort":1,"options":[]}]}}
    (ROOT/"monitoring/grafana/dashboards"/(key+".json")).write_text(json.dumps(d,indent=2)+"\n")

api_total = f'sum({rate("http_server_requests_seconds_count", "uri=~\"/api/v1/.*\"")})'
api_base = 'uri=~"/api/v1/.*"'
api_error = f'(sum({rate("http_server_requests_seconds_count", api_base + ",status=~\"[45]..\"")}) or on() (0 * {api_total}))'
api_guard = f'and on() ({api_total} > 0)'
dashboard("api","API traffic and latency","Rates need at least two scrapes. Latency/error fractions are absent when no requests occur. Percentiles are histogram estimates.",[
    panel("API requests / second",live(api_total),"reqps"),
    panel("Requests by endpoint and status",live(f'sum by(uri,status) ({rate("http_server_requests_seconds_count",api_base)})'),"reqps",legend="{{uri}} {{status}}"),
    panel("4xx + 5xx error fraction",live(f'({api_error} / {api_total}) {api_guard}'),"percentunit"),
    panel("API mean latency",live(f'(sum({rate("http_server_requests_seconds_sum",api_base)}) / {api_total}) {api_guard}'),"s"),
    panel("API p95 latency",live(f'histogram_quantile(0.95, sum by(le) ({rate("http_server_requests_seconds_bucket",api_base)})) {api_guard}'),"s"),
    panel("API p99 latency",live(f'histogram_quantile(0.99, sum by(le) ({rate("http_server_requests_seconds_bucket",api_base)})) {api_guard}'),"s"),
])
query_count=f'sum by(operation) ({rate("citibike_jdbc_query_duration_seconds_count")})'
dashboard("duckdb","DuckDB queries and limits","Memory/spill panels are configured ceilings, not current native memory or disk usage. JVM heap does not include all DuckDB allocations.",[
    panel("Queries / second",live(query_count),"ops",legend="{{operation}}"),
    panel("Mean query latency",live(f'sum by(operation) ({rate("citibike_jdbc_query_duration_seconds_sum")}) / ({query_count} > 0)'),"s",legend="{{operation}}"),
    panel("Query p95 latency",live(f'histogram_quantile(0.95, sum by(operation,le) ({rate("citibike_jdbc_query_duration_seconds_bucket")})) and on(operation) ({query_count} > 0)'),"s",legend="{{operation}}"),
    panel("Query failures / second",live(f'sum by(operation) ({rate("citibike_jdbc_query_duration_seconds_count", "outcome=\"failure\"")}) or on(operation) (0 * {query_count})'),"ops",legend="{{operation}}"),
    panel("Active query connections",live(metric("citibike_jdbc_connections_active"))),
    panel("Query connection limit",live(metric("citibike_jdbc_connections_limit")),kind="stat"),
    panel("Configured DuckDB memory ceiling",live(metric("citibike_duckdb_memory_limit_bytes")),"bytes",kind="stat"),
    panel("Configured spill ceiling",live(metric("citibike_duckdb_spill_limit_bytes")),"bytes",kind="stat"),
    panel("Configured DuckDB threads",live(metric("citibike_duckdb_threads_limit")),kind="stat"),
    panel("Configured query timeout",live(metric("citibike_jdbc_query_timeout_seconds")),"s",kind="stat"),
    panel("Configured connection acquisition timeout",live(metric("citibike_jdbc_acquire_timeout_seconds")),"s",kind="stat"),
])
dashboard("csv","CSV loading","Startup imports are one-off events. Cumulative duration and count work even when Prometheus starts after loading. Failure totals reset on application restart.",[
    panel("Loaded rides",live(metric("citibike_csv_rows")),kind="stat"),
    panel("Discovered files",live(metric("citibike_csv_files_discovered")),kind="stat"),
    panel("Loaded files",live(metric("citibike_csv_files_loaded")),kind="stat"),
    panel("Mean successful startup load duration",live(f'sum({metric("citibike_csv_load_duration_seconds_sum", "outcome=\"success\"")}) / (sum({metric("citibike_csv_load_duration_seconds_count", "outcome=\"success\"")}) > 0)'),"s",kind="stat"),
    panel("Load failures since process start",live(f'sum({metric("citibike_csv_load_failures_total")})'),kind="stat"),
    panel("Mean individual file load duration",live(f'sum({metric("citibike_csv_file_load_duration_seconds_sum", "outcome=\"success\"")}) / (sum({metric("citibike_csv_file_load_duration_seconds_count", "outcome=\"success\"")}) > 0)'),"s",kind="stat"),
])
dashboard("downloads","Latest archive downloads","Downloader failure is separate from analytics readiness. Disabled panels show no samples. Timestamps remain unknown until a check; last publication age may grow during unchanged checks. No archive month/key is a label.",[
    panel("Downloader enabled",live(metric("citibike_download_enabled")),kind="stat",mappings={0:("Disabled","gray"),1:("Enabled","blue")}),
    panel("Check active",download(metric("citibike_download_active")),kind="stat",mappings={0:("Idle","blue"),1:("Running","blue")}),
    panel("Last completed check",download(metric("citibike_download_last_check_successful")),kind="stat",mappings={0:("Failed / no match","red"),1:("Verified / published","green")}),
    panel("Age of last completed check",download(f'time() - ({metric("citibike_download_last_check_timestamp_seconds")} > 0)'),"s",kind="stat"),
    panel("Age of last archive publication",download(f'time() - ({metric("citibike_download_last_success_timestamp_seconds")} > 0)'),"s",kind="stat"),
    panel("Checks by outcome since process start",download(metric("citibike_download_checks_total")),kind="stat",legend="{{outcome}}"),
    panel("Failed checks since process start",download(metric("citibike_download_checks_total", 'outcome="failed"')),"short",kind="stat"),
    panel("Check outcomes / second",download(rate("citibike_download_checks_total")),"ops",legend="{{outcome}}"),
])
dashboard("jvm","JVM and process","CPU values are fractions; memory panels reflect JVM-managed pools rather than total DuckDB native memory. GC rates need multiple scrapes.",[
    panel("JVM heap used",live(f'sum({metric("jvm_memory_used_bytes", "area=\"heap\"")})'),"bytes"),
    panel("JVM non-heap used",live(f'sum({metric("jvm_memory_used_bytes", "area=\"nonheap\"")})'),"bytes"),
    panel("JVM heap max",live(f'sum({metric("jvm_memory_max_bytes", "area=\"heap\"")} > 0)'),"bytes",kind="stat"),
    panel("Process CPU",live(metric("process_cpu_usage")),"percentunit"),
    panel("System CPU",live(metric("system_cpu_usage")),"percentunit"),
    panel("GC pauses / second",live(f'sum({rate("jvm_gc_pause_seconds_count")})'),"ops"),
    panel("GC pause time / second",live(f'sum({rate("jvm_gc_pause_seconds_sum")})'),"s"),
    panel("Process uptime",live(metric("process_uptime_seconds")),"s",kind="stat"),
])

ingestion_enabled = metric("citibike_ingestion_enabled") + " == 1"
def ingest(expr):
    return f'({expr}) and on(job,instance) ({ingestion_enabled}) and on(job,instance) ({UP})'
dashboard("ingestion","Monthly ingestion","File-backed import health is independent of analytics readiness and the download-only component. Publication wait/overdue are separate outcomes; unknown freshness is absent.",[
    panel("Monthly ingestion enabled",live(metric("citibike_ingestion_enabled")),kind="stat",mappings={0:("Disabled / memory mode","gray"),1:("Enabled","blue")}),
    panel("Pending jobs",ingest(metric("citibike_ingestion_pending")),kind="stat"),
    panel("Job running",ingest(metric("citibike_ingestion_running")),kind="stat"),
    panel("Age of last committed import",ingest(f'time() - ({metric("citibike_ingestion_last_success_timestamp_seconds")} > 0)'),"s",kind="stat"),
    panel("Jobs by outcome since process start",ingest(metric("citibike_ingestion_jobs_total")),kind="stat",legend="{{outcome}}"),
    panel("Downloaded bytes since process start",ingest(metric("citibike_ingestion_downloaded_bytes_total")),"bytes",kind="stat"),
    panel("Expanded bytes since process start",ingest(metric("citibike_ingestion_expanded_bytes_total")),"bytes",kind="stat"),
    panel("Mean job duration by outcome",live(f'sum by(outcome) ({metric("citibike_ingestion_duration_seconds_sum")}) / (sum by(outcome) ({metric("citibike_ingestion_duration_seconds_count")}) > 0)'),"s",legend="{{outcome}}"),
])
