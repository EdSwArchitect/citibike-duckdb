#!/usr/bin/env python3
"""Back up stopped file-mode ingestion storage, or verify/restore a backup.

Copies the entire owned data directory, including all retained source archives.
Requires a clean shutdown (no WAL), obtains the application's ownership lock,
and publishes a checksummed backup only after the complete copy succeeds.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        while block:=f.read(1024*1024): h.update(block)
    return h.hexdigest()

def verify(root):
    document=root/'backup-manifest.json'
    if document.is_symlink():raise RuntimeError('Backup manifest cannot be a symlink')
    manifest=json.loads(document.read_text())
    expected=set(manifest['sha256'])|{'backup-manifest.json'}
    for candidate in root.rglob('*'):
        if candidate.is_symlink():raise RuntimeError('Backup cannot contain symlinks')
        if candidate.is_file() and str(candidate.relative_to(root)) not in expected:raise RuntimeError('Backup contains unlisted files')
    for relative,hash_value in manifest['sha256'].items():
        name=Path(relative)
        if name.is_absolute() or ".." in name.parts:raise RuntimeError("Unsafe backup manifest path")
        path=root/name
        if path.is_symlink() or not path.is_file() or digest(path)!=hash_value:
            raise RuntimeError('Backup checksum verification failed')
    return manifest

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source',type=Path);p.add_argument('destination',type=Path)
    p.add_argument('--restore',action='store_true');args=p.parse_args()
    if args.source.is_symlink():raise RuntimeError("Source cannot be a symlink")
    source=args.source.resolve();destination=args.destination.resolve()
    if destination.exists(): raise RuntimeError('Destination must not already exist')
    destination.parent.mkdir(parents=True,exist_ok=True)
    if source==destination or source in destination.parents:raise RuntimeError('Destination must be outside source storage')
    if args.restore:
        verify(source)
        shutil.copytree(source,destination,ignore=shutil.ignore_patterns('backup-manifest.json','owner.lock'))
        print('Restored verified ingestion backup');return
    if source.is_symlink() or not source.is_dir():raise RuntimeError('Source must be an owned data directory')
    with (source/'owner.lock').open('a+b') as ownership:
        try:fcntl.lockf(ownership,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Stop the application before backup') from None
        if list(source.glob('*.wal')):raise RuntimeError('Recover the database and shut down cleanly before backup')
        stage=Path(tempfile.mkdtemp(prefix='citibike-backup-',dir=destination.parent))
        try:
            hashes={};total=0
            for file in source.rglob('*'):
                if file.is_symlink():raise RuntimeError('Symlinks are not supported in backups')
                if not file.is_file() or file.name in ('owner.lock','backup-receipt.json') or file.suffix=='.part':continue
                relative=file.relative_to(source);target=stage/relative;target.parent.mkdir(parents=True,exist_ok=True)
                total+=file.stat().st_size
                if shutil.disk_usage(stage).free<file.stat().st_size:raise RuntimeError('Insufficient backup disk space')
                shutil.copy2(file,target);hashes[str(relative)]=digest(target)
            (stage/'backup-manifest.json').write_text(json.dumps({'format':1,'bytes':total,'sha256':hashes},indent=2)+'\n')
            verify(stage);stage.rename(destination)
            receipt=source/'backup-receipt.json.part'
            with receipt.open('w') as marker:
                json.dump({'backupDirectory':str(destination),'sha256':hashes},marker)
                marker.flush();os.fsync(marker.fileno())
            receipt.replace(source/'backup-receipt.json')
            print(f'Published verified ingestion backup: {len(hashes)} files, {total} bytes')
        finally:
            if stage.exists():shutil.rmtree(stage)

if __name__=='__main__':main()
