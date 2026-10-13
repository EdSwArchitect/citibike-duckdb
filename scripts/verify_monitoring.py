#!/usr/bin/env python3
"""Validate provisioned dashboards and live PromQL without exposing credentials.

Start the application and Compose stack first. This script only reads data,
except for optional synthetic API traffic. Credentials are read from a file.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def request(url, headers=None, payload=None):
    headers = dict(headers or {})
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers, data=data), timeout=15) as response:
        return json.load(response)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--application-url", default="http://localhost:8080")
    p.add_argument("--prometheus-url", default="http://localhost:9090")
    p.add_argument("--grafana-url", default="http://localhost:3000")
    p.add_argument("--instance", default="host.docker.internal:8080")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--password-file", type=Path, default=ROOT/"monitoring/.secrets/grafana-admin-password")
    p.add_argument("--generate-traffic", action="store_true")
    p.add_argument("--wait-seconds", type=int, default=45)
    args = p.parse_args()
    dashboards = []
    for path in sorted((ROOT/"monitoring/grafana/dashboards").glob("*.json")):
        d = json.loads(path.read_text())
        assert d["uid"].startswith("citibike-") and d["schemaVersion"] == 39
        assert len({panel["id"] for panel in d["panels"]}) == len(d["panels"])
        for panel in d["panels"]:
            assert panel["datasource"]["uid"] == "citibike-prometheus"
            assert panel["fieldConfig"]["defaults"]["noValue"] == "Unknown / no samples"
        dashboards.append(d)
    assert len(dashboards) == 6, "Expected six provisioned dashboards"
    secret = args.password_file.read_text().strip()
    auth = {"Authorization": "Basic " + base64.b64encode((args.admin_user+":"+secret).encode()).decode()}
    assert request(args.grafana_url+"/api/health")["database"] == "ok"
    ds = request(args.grafana_url+"/api/datasources/uid/citibike-prometheus", auth)
    assert ds["url"] == "http://prometheus:9090"
    assert request(args.grafana_url+"/api/datasources/uid/citibike-prometheus/health", auth)["status"] == "OK"
    for d in dashboards:
        loaded = request(args.grafana_url+"/api/dashboards/uid/"+d["uid"], auth)
        assert loaded["meta"]["provisioned"]
        assert loaded["dashboard"]["title"] == d["title"]
    def query(expr):
        data = request(args.prometheus_url+"/api/v1/query?"+urllib.parse.urlencode({"query":expr}))
        assert data["status"] == "success", "PromQL query failed"
        return data["data"]["result"]
    selection = 'job="citibike",instance='+json.dumps(args.instance)
    # Populate counters across multiple scrapes; a single burst before the first
    # scrape is not enough to produce meaningful request rates.
    for tick in range(max(1,args.wait_seconds)):
        if args.generate_traffic:
            for route in ["/api/v1/rides/summary", "/api/v1/rides/summary?bikeType=invalid"]:
                try:
                    with urllib.request.urlopen(args.application_url+route,timeout=10) as r: r.read()
                except urllib.error.HTTPError as e:
                    assert e.code == 400
        if tick+1 < args.wait_seconds: time.sleep(1)
    ready=query("up{"+selection+"}")
    assert len(ready)==1 and float(ready[0]["value"][1])==1, "Application target not scraping"
    assert float(query("citibike_analytics_ready{"+selection+"}")[0]["value"][1])==1
    with urllib.request.urlopen(args.application_url+"/q/metrics",timeout=10) as r:
        exported=r.read().decode()
    observed=set(re.findall(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{| )",exported,re.M)) | {"up"}
    count=0
    grafana_queries=[]
    for d in dashboards:
        for panel in d["panels"]:
            for target in panel["targets"]:
                expression=target["expr"].replace("$instance",args.instance).replace("$__rate_interval","5m")
                references=set(re.findall(r"\b(?:citibike_|http_|jvm_|process_|system_)[a-zA-Z0-9_:]+",expression))
                assert references <= observed, "Dashboard references missing metrics: "+str(references-observed)
                query(expression)  # Real Prometheus parser + evaluation engine.
                grafana_queries.append({"refId":str(count), "datasource":{"type":"prometheus","uid":"citibike-prometheus"},
                    "expr":expression,"instant":True,"range":False,"intervalMs":15000,"maxDataPoints":1})
                count+=1
    now=int(time.time()*1000)
    through_grafana=request(args.grafana_url+"/api/ds/query",auth,{"queries":grafana_queries,"from":str(now-60000),"to":str(now)})
    assert len(through_grafana["results"])==count
    for result in through_grafana["results"].values():
        assert not result.get("error"), "Grafana datasource query failed"
        assert result.get("status",200)==200
    rate=query('sum(rate(http_server_requests_seconds_count{'+selection+',uri=~"/api/v1/.*"}[5m]))')
    if args.generate_traffic:
        assert rate and float(rate[0]["value"][1])>0, "No traffic sampled; increase --wait-seconds"
    enabled=query('citibike_download_enabled{'+selection+'}')
    if enabled and float(enabled[0]["value"][1])==0:
        d=next(d for d in dashboards if d["uid"]=="citibike-downloads")
        for panel in d["panels"][3:]:
            expr=panel["targets"][0]["expr"].replace("$instance",args.instance).replace("$__rate_interval","5m")
            assert not query(expr), "Disabled downloader should not show healthy/zero freshness"
    print(f"Validated {len(dashboards)} provisioned dashboards and {count} PromQL expressions; datasource and application scrape healthy.")

if __name__ == "__main__":
    main()
