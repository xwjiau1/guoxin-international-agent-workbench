"""Pull Docker Hub linux/amd64 images using verified HTTPS and the existing system proxy.

Does not modify host DNS, hosts files, Docker settings, or TLS verification.
Only Docker Hub manifests/auth and their HTTPS CDN redirects are used.
Cache contains public image bytes and digest/size metadata, never bearer tokens.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time
import urllib.parse
import urllib.request
import urllib.error

CHUNK = 512 * 1024
ACCEPT = ', '.join(['application/vnd.oci.image.index.v1+json',
    'application/vnd.docker.distribution.manifest.list.v2+json',
    'application/vnd.oci.image.manifest.v1+json',
    'application/vnd.docker.distribution.manifest.v2+json'])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ProxyResponse:
    def __init__(self, response):
        self.response = response
        self.status = response.status
        self.url = response.geturl()
    def getheader(self, key, default=None):
        return self.response.headers.get(key, default)
    def read(self, *args):
        return self.response.read(*args)


class HubClient:
    def __init__(self):
        # Retain the existing process/Windows user proxy; no global DNS changes.
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, url, headers=None, redirects=0):
        if redirects > 8:
            raise RuntimeError('Too many official registry redirects')
        target = urllib.parse.urlsplit(url)
        if target.scheme != 'https' or not target.hostname or target.port not in (None, 443):
            raise ValueError('Only verified HTTPS on port 443 is supported')
        host = target.hostname
        allowed = host in ('auth.docker.io', 'registry-1.docker.io') or any(host.endswith('.' + suffix) for suffix in ('docker.com', 'docker.io', 'cloudflarestorage.com', 'cloudfront.net'))
        if not allowed:
            raise ValueError('Registry redirect is outside the official CDN allowlist: ' + host)
        for attempt in range(4):
            response = None
            try:
                try:
                    response = self.opener.open(urllib.request.Request(url, headers=headers or {}), timeout=45)
                except urllib.error.HTTPError as exc:
                    response = exc
                status = response.status
                if status in (301, 302, 303, 307, 308):
                    location = response.headers.get('Location')
                    response.close()
                    redirected = urllib.parse.urljoin(url, location)
                    new_headers = dict(headers or {})
                    if urllib.parse.urlsplit(redirected).hostname != host:
                        new_headers.pop('Authorization', None)
                    return self.request(redirected, new_headers, redirects + 1)
                if status == 429 or status >= 500:
                    delay = min(30, int(response.headers.get('Retry-After', '2')))
                    response.close()
                    time.sleep(delay)
                    continue
                if status not in (200, 206):
                    response.close()
                    raise RuntimeError('Official endpoint ' + host + ' returned HTTP ' + str(status))
                return response, ProxyResponse(response)
            except (OSError, http.client.HTTPException):
                if response:
                    response.close()
                if attempt == 3:
                    raise RuntimeError('Official HTTPS request failed for ' + host) from None
                time.sleep(0.5 * (attempt + 1))
        raise RuntimeError('Official HTTPS retries exhausted for ' + host)

    def json_bytes(self, url, headers=None, expected_digest=None):
        conn, response = self.request(url, headers)
        try:
            data = response.read()
            digest = expected_digest or response.getheader('Docker-Content-Digest')
            if digest and digest != 'sha256:' + hashlib.sha256(data).hexdigest():
                raise ValueError('Manifest SHA256 mismatch')
            return data, json.loads(data)
        finally:
            conn.close()


def valid_blob(path, digest, size):
    if not path.exists() or path.stat().st_size != size:
        return False
    with path.open('rb') as stream:
        return 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest() == digest


def download_blob(client, repository, token, descriptor, cache, workers):
    digest, size = descriptor['digest'], descriptor['size']
    if not digest.startswith('sha256:'):
        raise ValueError('Unsupported digest algorithm')
    path = cache / 'blobs' / digest.replace(':', '-')
    path.parent.mkdir(parents=True, exist_ok=True)
    if valid_blob(path, digest, size):
        print('Cached verified blob', digest[:23], size, flush=True)
        return path
    parts = cache / 'parts' / digest.replace(':', '-')
    parts.mkdir(parents=True, exist_ok=True)
    (parts / 'resume.json').write_text(json.dumps({'digest': digest, 'size': size, 'chunkSize': CHUNK}), encoding='utf-8')
    url = 'https://registry-1.docker.io/v2/' + repository + '/blobs/' + digest
    # Resolve the signed CDN redirect once, instead of re-authenticating every range.
    probe_conn, probe = client.request(url, {'Authorization': 'Bearer ' + token, 'Range': 'bytes=0-0'})
    download_url = probe.url
    probe_conn.close()
    download_headers = {'Authorization': 'Bearer ' + token} if urllib.parse.urlsplit(download_url).hostname == 'registry-1.docker.io' else {}

    def chunk(index):
        start, end = index * CHUNK, min((index + 1) * CHUNK, size) - 1
        destination = parts / ('%06d.part' % index)
        length = end - start + 1
        if destination.exists() and destination.stat().st_size == length:
            return destination
        for attempt in range(5):
            conn = None
            try:
                conn, response = client.request(download_url, dict(download_headers, Range='bytes=%d-%d' % (start, end)))
                data = response.read(length + 1)
                if len(data) != length:
                    raise ValueError('Truncated or unsupported range for ' + digest[:23])
                if start and response.status != 206:
                    raise ValueError('CDN ignored range request')
                temporary = destination.with_suffix('.tmp')
                temporary.write_bytes(data)
                os.replace(temporary, destination)
                return destination
            except (OSError, ValueError, RuntimeError, http.client.HTTPException):
                if attempt == 4:
                    raise
                time.sleep(0.5 * (attempt + 1))
            finally:
                if conn:
                    conn.close()
    count = (size + CHUNK - 1) // CHUNK
    print('Downloading official blob', digest[:23], size, 'bytes', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        completed = list(executor.map(chunk, range(count)))
    temporary = path.with_suffix('.assembling')
    with temporary.open('wb') as output:
        for part in completed:
            with part.open('rb') as source:
                shutil.copyfileobj(source, output)
    if not valid_blob(temporary, digest, size):
        # Untrusted partial cache must not survive a failed whole-blob digest check.
        for part in completed:
            part.unlink()
        temporary.unlink()
        raise ValueError('Blob SHA256 mismatch; rejected cached chunks')
    os.replace(temporary, path)
    return path


def pull(image, cache, workers, docker):
    if '@' in image:
        raise ValueError('Use an official image tag; digest-only imports cannot restore Docker RepoDigests via docker load')
    name, separator, reference = image.rpartition(':')
    if not separator:
        name, reference = image, 'latest'
    if name.startswith('docker.io/'):
        name = name[len('docker.io/'):]
    if '/' not in name:
        name = 'library/' + name
    if not name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789._-/' for c in name):
        raise ValueError('Only Docker Hub repository names are supported')
    client = HubClient()
    auth_url = 'https://auth.docker.io/token?' + urllib.parse.urlencode({'service': 'registry.docker.io', 'scope': 'repository:' + name + ':pull'})
    _, auth = client.json_bytes(auth_url)
    token = auth.get('token') or auth['access_token']
    headers = {'Authorization': 'Bearer ' + token, 'Accept': ACCEPT}
    base = 'https://registry-1.docker.io/v2/' + name + '/manifests/'
    raw, manifest = client.json_bytes(base + reference, headers, reference if reference.startswith('sha256:') else None)
    if 'manifests' in manifest:
        candidates = [d for d in manifest['manifests'] if d.get('platform', {}).get('os') == 'linux' and d.get('platform', {}).get('architecture') == 'amd64']
        if not candidates:
            raise ValueError('Official image has no linux/amd64 manifest')
        raw, manifest = client.json_bytes(base + candidates[0]['digest'], headers, candidates[0]['digest'])
    manifest_digest = hashlib.sha256(raw).hexdigest()
    work = cache / 'images' / manifest_digest
    work.mkdir(parents=True, exist_ok=True)
    (work / 'oci-manifest.json').write_bytes(raw)
    descriptors = [manifest['config']] + manifest['layers']
    paths = [download_blob(client, name, token, descriptor, cache, workers) for descriptor in descriptors]
    config_name = manifest['config']['digest'].split(':')[1] + '.json'
    shutil.copyfile(paths[0], work / config_name)
    layers = []
    for descriptor, blob in zip(manifest['layers'], paths[1:]):
        layer = descriptor['digest'].split(':')[1] + '/layer.tar'
        destination = work / layer
        destination.parent.mkdir(exist_ok=True)
        # Verify uncompressed layers against config rootfs diff_ids too.
        media = descriptor.get('mediaType', '')
        if media.endswith('+gzip') or media.endswith('.gzip'):
            source = gzip.open(blob, 'rb')
        elif media.endswith('+zstd'):
            from compression import zstd
            source = zstd.open(blob, 'rb')
        else:
            source = blob.open('rb')
        with source, destination.open('wb') as output:
            shutil.copyfileobj(source, output)
        layers.append(layer)
    config = json.loads((work / config_name).read_bytes())
    if config.get('architecture') != 'amd64' or config.get('os') != 'linux':
        raise ValueError('Image config is not linux/amd64')
    expected = config.get('rootfs', {}).get('diff_ids', [])
    if len(expected) != len(layers):
        raise ValueError('Image config layer count mismatch')
    for layer, digest in zip(layers, expected):
        with (work / layer).open('rb') as source:
            if 'sha256:' + hashlib.file_digest(source, 'sha256').hexdigest() != digest:
                raise ValueError('Uncompressed layer diff_id mismatch')
    tag = image if ':' in image.rsplit('/', 1)[-1] and '@' not in image else name.removeprefix('library/') + ':' + reference
    save_manifest = [{'Config': config_name, 'RepoTags': [tag], 'Layers': layers}]
    (work / 'manifest.json').write_text(json.dumps(save_manifest), encoding='utf-8')
    archive = work / 'docker-save.tar'
    with tarfile.open(archive, 'w') as tar:
        for relative in [config_name, 'manifest.json'] + layers:
            tar.add(work / relative, arcname=relative)
    print('Verified official linux/amd64 manifest sha256:' + manifest_digest, flush=True)
    subprocess.run([docker, 'load', '--input', str(archive)], check=True)
    subprocess.run([docker, 'image', 'inspect', tag, '--format', '{{.Id}} {{.Os}}/{{.Architecture}}'], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('images', nargs='+', help='Official Docker Hub image tags')
    parser.add_argument('--cache', type=Path, default=Path('D:/GuoxinAssistantRuntime/oci'))
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--docker', default=shutil.which('docker') or 'docker')
    args = parser.parse_args()
    if not 1 <= args.workers <= 16:
        parser.error('--workers must be between 1 and 16')
    for image in args.images:
        print('Pulling official image', image, flush=True)
        pull(image, args.cache, args.workers, args.docker)


if __name__ == '__main__':
    main()
