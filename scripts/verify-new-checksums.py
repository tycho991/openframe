#!/usr/bin/env python3
"""Compares the checksums that a regenerated gradle/verification-metadata.xml adds with the ones the repositories publish.

Usage: verify-new-checksums.py OLD.xml NEW.xml

Every SHA-256 that NEW has and OLD has not must match the .sha256 file of the artifact in one of the build's
repositories (settings.gradle.kts). Where a repository publishes only a .sha1, the artifact is downloaded: its SHA-256
must be the recorded one and its SHA-1 the published one. This is the check the README asks for after regenerating;
renovate-artifacts.yml runs it before it pushes the file to a Renovate branch.
"""

import hashlib
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

NS = "{https://schema.gradle.org/dependency-verification}"
# In the order of pluginManagement: a plugin marker can differ between the Plugin Portal and Maven Central.
REPOSITORIES = [
    "https://plugins.gradle.org/m2",
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
]


def entries(path):
    found = set()
    for component in ET.parse(path).getroot().iter(NS + "component"):
        coordinates = (component.get("group"), component.get("name"), component.get("version"))
        for artifact in component.findall(NS + "artifact"):
            for sha in artifact.findall(NS + "sha256"):
                values = [sha.get("value")] + [t.get("value") for t in sha.findall(NS + "also-trust")]
                for value in values:
                    found.add((*coordinates, artifact.get("name"), value.lower()))
    return found


def fetch(url):
    """The body, or None if the repository does not have the file."""
    for attempt in range(5):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "openframe-verify-new-checksums"})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code in (403, 404):
                return None
            if e.code not in (429, 500, 502, 503, 504):
                raise
        except urllib.error.URLError:
            pass
        time.sleep(2**attempt)
    raise RuntimeError(f"{url}: no answer after 5 attempts")


def published(url):
    body = fetch(url)
    return body.decode().split()[0].lower() if body and body.strip() else None


def check(entry):
    group, name, version, file, sha256 = entry
    seen = []
    for repository in REPOSITORIES:
        url = f"{repository}/{group.replace('.', '/')}/{name}/{version}/{file}"
        expected = published(url + ".sha256")
        if expected is not None:
            if expected == sha256:
                return entry, f"ok ({url}.sha256)"
            seen.append(f"{url}.sha256 says {expected}")
            continue
        sha1 = published(url + ".sha1")
        if sha1 is None:
            continue
        body = fetch(url)
        if body is None:
            continue
        actual = hashlib.sha256(body).hexdigest()
        if actual == sha256 and hashlib.sha1(body).hexdigest() == sha1:
            return entry, f"ok ({url}.sha1)"
        seen.append(f"{url} has SHA-256 {actual}")
    return entry, "MISMATCH: " + ("; ".join(seen) if seen else "in none of the repositories")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip().splitlines()[2])
    new = sorted(entries(sys.argv[2]) - entries(sys.argv[1]))
    print(f"{len(new)} new checksums")
    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(check, new))
    failed = 0
    for (group, name, version, file, sha256), result in results:
        print(f"{group}:{name}:{version} {file} {sha256[:12]}… {result}")
        failed += not result.startswith("ok")
    if failed:
        sys.exit(f"{failed} of {len(new)} new checksums do not match what the repositories publish")


if __name__ == "__main__":
    main()
