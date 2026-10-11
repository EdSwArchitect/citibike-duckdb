#!/usr/bin/env python3
"""Verify automatic CSV indexing in an isolated build, fast JAR, and dev mode.

Requires Python 3, Java 25, and Maven 3.9+. Leaves the working tree untouched.
"""
import csv
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]


def stop(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def verify_mode(project, command, mode, expected_rows, expected_files):
    log_path = project / f"{mode}.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(command, cwd=project, stdout=log, stderr=subprocess.STDOUT,
                                   stdin=subprocess.DEVNULL, start_new_session=True)
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"{mode} exited during startup:\n{log_path.read_text()[-6000:]}")
                addresses = re.findall(r"Listening on: http://127\.0\.0\.1:(\d+)", log_path.read_text())
                if addresses:
                    base = "http://127.0.0.1:" + addresses[-1]
                    try:
                        with urllib.request.urlopen(base + "/api/v1/data-quality", timeout=5) as response:
                            quality = json.load(response)
                        break
                    except (OSError, ValueError):
                        pass
                time.sleep(0.2)
            else:
                raise RuntimeError(f"{mode} startup timed out:\n{log_path.read_text()[-6000:]}")
            assert quality["totalRows"] == expected_rows, quality
            assert quality["filesDiscovered"] == len(expected_files), quality
            assert quality["filesLoaded"] == len(expected_files), quality
            assert [source["filename"] for source in quality["sources"]] == expected_files, quality
            assert quality["duplicateRideIds"] == 0, quality
            with urllib.request.urlopen(base + "/api/v1/rides/summary", timeout=5) as response:
                assert json.load(response)["rideCount"] == expected_rows
            with urllib.request.urlopen(base + "/q/health/ready", timeout=5) as response:
                assert json.load(response)["status"] == "UP"
            print(f"{mode}: loaded {expected_rows} rows from {expected_files}; nested CSV excluded", flush=True)
        finally:
            stop(process)


def main():
    with tempfile.TemporaryDirectory(prefix="citibike-discovery-") as temporary:
        project = Path(temporary)
        shutil.copy(PROJECT / "pom.xml", project / "pom.xml")
        shutil.copytree(PROJECT / "src/main", project / "src/main")
        resources = project / "src/main/resources"
        csv_files = sorted(resources.glob("*.csv"))
        if not csv_files:
            raise RuntimeError("At least one existing CSV is required")
        rows = 0
        first_row = None
        existing_ids = set()
        for path in csv_files:
            with path.open(newline="") as source:
                reader = csv.DictReader(source)
                header = reader.fieldnames
                for row in reader:
                    rows += 1
                    existing_ids.add(row["ride_id"])
                    if first_row is None:
                        first_row = row
        extra_name = "verification-extra.csv"
        if (resources / extra_name).exists():
            raise RuntimeError("Verification filename already exists")
        extra_id = "DISCOVERY-VERIFICATION-RIDE"
        while extra_id in existing_ids:
            extra_id += "-NEW"
        first_row["ride_id"] = extra_id
        with (resources / extra_name).open("w", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=header)
            writer.writeheader()
            writer.writerow(first_row)
        (resources / "nested").mkdir(exist_ok=True)
        (resources / "nested/invalid.csv").write_text("incompatible,header\nnot,a,ride\n")
        expected_files = sorted([path.name for path in csv_files] + [extra_name])
        build_log = project / "build.log"
        print("Building isolated copy with an added top-level CSV and invalid nested CSV", flush=True)
        with build_log.open("w") as log:
            result = subprocess.run(["mvn", "-B", "-DskipTests", "package"], cwd=project,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=120)
        if result.returncode:
            raise RuntimeError(build_log.read_text()[-6000:])
        index = (project / "target/classes/META-INF/duckdb-learn/citibike-csv.index").read_text().splitlines()
        assert index == expected_files, index
        properties = ["-Dquarkus.http.port=0", "-Dquarkus.http.host=127.0.0.1"]
        verify_mode(project, ["java", "--enable-native-access=ALL-UNNAMED", *properties, "-jar",
                              "target/quarkus-app/quarkus-run.jar"], "fast-jar", rows + 1, expected_files)
        verify_mode(project, ["mvn", "-B", "-Ddebug=false", "-Dquarkus.analytics.disabled=true",
                              "-Dquarkus.console.enabled=false", *properties, "quarkus:dev"],
                    "dev-mode", rows + 1, expected_files)


if __name__ == "__main__":
    main()
