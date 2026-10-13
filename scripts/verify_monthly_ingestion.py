#!/usr/bin/env python3
"""Verify packaged file-mode ingestion with small local HTTP archives and a temporary volume.

Exercises authenticated backfill/manual routes, persistence, revisions, failed
imports, readiness, snapshot reporting, backup, and restore. No public S3 access.
"""
import base64
import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

PROJECT=Path(__file__).resolve().parents[1]
HEADER='ride_id,rideable_type,started_at,ended_at,start_station_name,start_station_id,end_station_name,end_station_id,start_lat,start_lng,end_lat,end_lng,member_casual\n'
def row(identifier,month):return f'{identifier},classic_bike,{month}-01 08:00:00,{month}-01 08:10:00,A,001.020,B,002.030,40.7,-73.9,40.71,-73.91,member\n'
def archive(name,body):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:z.writestr(name,body)
    return out.getvalue()

class Stub(http.server.BaseHTTPRequestHandler):
    objects={};downloads=0
    def log_message(self,*args):pass
    def do_HEAD(self):self.serve(False)
    def do_GET(self):self.serve(True)
    def serve(self,body):
        parsed=urllib.parse.urlparse(self.path)
        if parsed.path=='/tripdata':
            prefix=urllib.parse.parse_qs(parsed.query).get('prefix',[''])[0]
            xml='<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
            for key,data in sorted(self.objects.items()):
                if key.startswith(prefix):xml+=f'<Contents><Key>{key}</Key><Size>{len(data)}</Size><ETag>{hashlib.sha256(data).hexdigest()}</ETag></Contents>'
            data=(xml+'<IsTruncated>false</IsTruncated></ListBucketResult>').encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        key=parsed.path.removeprefix('/tripdata/');data=self.objects.get(key)
        if data is None:self.send_response(404);self.end_headers();return
        etag=hashlib.sha256(data).hexdigest()
        if self.headers.get('If-Match',etag)!=etag:self.send_response(412);self.end_headers();return
        self.send_response(200);self.send_header('Content-Length',str(len(data)));self.send_header('ETag',etag);self.end_headers()
        if body:type(self).downloads+=1;self.wfile.write(data)

def stop(p):
    if p.poll() is None:
        os.killpg(p.pid,signal.SIGTERM)
        try:p.wait(timeout=30)
        except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)

def main():
    if not (PROJECT/'target/quarkus-app/quarkus-run.jar').exists():raise RuntimeError('Run mvn verify first')
    password=secrets.token_urlsafe(32);auth='Basic '+base64.b64encode(('operator:'+password).encode()).decode()
    Stub.objects={'202306-citibike-tripdata.zip':archive('june.csv',HEADER+row('JUNE1','2023-06')),'202307-citibike-tripdata.zip':archive('july.csv',HEADER+row('JULY1','2023-07'))}
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Stub);threading.Thread(target=server.serve_forever,daemon=True).start()
    with tempfile.TemporaryDirectory(prefix='citibike-packaged-ingestion-') as tmp:
        root=Path(tmp);data=root/'data';process=None;base=None
        def request(path,body=None,authorized=False):
            headers={'Content-Type':'application/json'}
            if authorized:headers['Authorization']=auth
            encoded=None if body is None else json.dumps(body).encode()
            try:
                with urllib.request.urlopen(urllib.request.Request(base+path,data=encoded,headers=headers),timeout=5) as r:
                    raw=r.read();return r.status,json.loads(raw) if raw else {}
            except urllib.error.HTTPError as e:
                raw=e.read();return e.code,json.loads(raw) if raw else {}
        def start(storage):
            nonlocal process,base
            log=root/'application.log';env=dict(os.environ);env['INGESTION_TEST_PASSWORD']=password
            args=['java','--enable-native-access=ALL-UNNAMED','-Dquarkus.http.host=127.0.0.1','-Dquarkus.http.port=0',
                  '-Dcitibike.storage.mode=file','-Dcitibike.storage.data-directory='+str(storage),'-Dcitibike.ingestion.enabled=true',
                  '-Dcitibike.ingestion.admin-enabled=true','-Dcitibike.ingestion.check-on-startup=false',
                  '-Dcitibike.ingestion.monthly-cron=off','-Dcitibike.ingestion.catch-up-cron=off',
                  '-Dcitibike.ingestion.min-free-disk-bytes=0','-Dcitibike.ingestion.max-expanded-bytes=1048576',
                  '-Dcitibike.ingestion.source-uri=http://127.0.0.1:'+str(server.server_port)+'/tripdata',
                  '-Dquarkus.security.users.embedded.users.operator=${INGESTION_TEST_PASSWORD}',
                  '-Dquarkus.security.users.embedded.roles.operator=ingestion-admin','-jar','target/quarkus-app/quarkus-run.jar']
            with log.open('w') as out:process=subprocess.Popen(args,cwd=PROJECT,env=env,stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                if process.poll() is not None:raise RuntimeError('Packaged application failed to start')
                ports=re.findall(r'Listening on: http://127\.0\.0\.1:(\d+)',log.read_text())
                if ports:
                    base='http://127.0.0.1:'+ports[-1]
                    try:
                        if request('/q/health/live')[0]==200:return
                    except OSError:pass
                time.sleep(.1)
            raise RuntimeError('Packaged application startup timed out')
        def await_job(job):
            deadline=time.monotonic()+15
            while time.monotonic()<deadline:
                status,result=request('/api/v1/admin/ingestion/jobs/'+job['id'],authorized=True)
                if status==200 and result['status'] not in ['QUEUED','RUNNING','WAITING']:return result
                time.sleep(.05)
            raise RuntimeError('Ingestion job did not reach a terminal state')
        def check(rows):
            status,quality=request('/api/v1/data-quality');assert status==200 and quality['totalRows']==rows and quality['invalidRows']==0
            assert sum(s['rowsLoaded'] for s in quality['sources'])==rows,quality
            assert request('/q/health/ready')[0]==200
        try:
            start(data);assert request('/q/health/ready')[0]==503;assert request('/api/v1/admin/ingestion/status')[0]==401
            status,jobs=request('/api/v1/admin/ingestion/backfills',{'from':'2023-06','to':'2023-07'},True);assert status==202
            for job in jobs:assert await_job(job)['status']=='COMMITTED'
            check(2);before=Stub.downloads;stop(process);start(data);check(2);assert Stub.downloads==before
            status,job=request('/api/v1/admin/ingestion/months',{'month':'2023-06'},True);assert status==202 and await_job(job)['status']=='UNCHANGED';assert Stub.downloads==before
            Stub.objects['202306-citibike-tripdata.zip']=archive('revision.csv',HEADER+row('JUNE2','2023-06')+row('JUNE3','2023-06'))
            status,job=request('/api/v1/admin/ingestion/months',{'month':'2023-06'},True);assert status==202 and await_job(job)['status']=='COMMITTED';check(3)
            Stub.objects['202307-citibike-tripdata.zip']=archive('legacy.csv','legacy,header\nunsupported,row\n')
            status,job=request('/api/v1/admin/ingestion/months',{'month':'2023-07'},True);assert status==202
            failure=await_job(job);assert failure['status']=='FAILED' and failure['reason']=='unsupported_schema';check(3)
            stop(process);backup=root/'backup';restored=root/'restored'
            subprocess.run(['python3',str(PROJECT/'scripts/backup_ingestion.py'),str(data),str(backup)],check=True)
            subprocess.run(['python3',str(PROJECT/'scripts/backup_ingestion.py'),str(backup),str(restored),'--restore'],check=True)
            start(restored);check(3)
            print('Packaged ingestion: backfill, idempotency, restart, corrections, failed revision/readiness, metadata snapshots, backup and restore passed')
        finally:
            if process is not None:stop(process)
            server.shutdown();server.server_close()

if __name__=='__main__':main()
