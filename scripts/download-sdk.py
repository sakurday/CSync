"""Official SDK ZIP fallback for networks where sdkmanager downloads stall.
Downloads only SDK 35/build tools 35, verifies the Google repository checksum,
and installs under the project's .build directory. No global SDK is modified.
"""
import concurrent.futures
import hashlib
import pathlib
import shutil
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent / ".build"
BASE = "https://dl.google.com/android/repository/"


def download(url, path, size, digest):
    if path.exists() and path.stat().st_size == size and hashlib.sha1(path.read_bytes()).hexdigest() == digest:
        return
    chunk = 8 * 1024 * 1024

    def part(start):
        end = min(start + chunk, size) - 1
        target = path.with_suffix(f".part{start}")
        if target.exists() and target.stat().st_size == end - start + 1:
            return target
        for attempt in range(3):
            try:
                request = urllib.request.Request(url + f"?csync_part={start}", headers={"Range": f"bytes={start}-{end}"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    if response.status != 206:
                        raise RuntimeError("server did not honor bounded range request")
                    data = response.read(end - start + 2)
                if len(data) != end - start + 1:
                    raise RuntimeError("incomplete SDK download")
                target.write_bytes(data)
                return target
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(attempt + 1)

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        parts = list(pool.map(part, range(0, size, chunk)))
    with path.open("wb") as output:
        for piece in parts:
            with piece.open("rb") as source:
                shutil.copyfileobj(source, output)
    if hashlib.sha1(path.read_bytes()).hexdigest() != digest:
        raise RuntimeError("Google SDK checksum mismatch")


def install(package, destination, prefer):
    repository = ET.parse(ROOT / "android-repository.xml").getroot()
    candidates = []
    for item in repository:
        if item.tag.endswith("remotePackage") and item.attrib.get("path") == package:
            for archive in item.findall("archives/archive"):
                if archive.findtext("host-os") not in (None, "windows"):
                    continue
                complete = archive.find("complete")
                if complete is not None:
                    candidates.append(complete)
    complete = next((item for item in candidates if prefer in item.findtext("url", "")), candidates[0])
    filename = complete.findtext("url")
    archive = ROOT / filename
    print(f"Downloading {filename}", flush=True)
    download(BASE + filename, archive, int(complete.findtext("size")), complete.findtext("checksum").strip())
    extract = ROOT / (filename + ".extracted")
    extract.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            resolved = (extract / member.filename).resolve()
            if not resolved.is_relative_to(extract.resolve()):
                raise RuntimeError("invalid SDK archive path")
        bundle.extractall(extract)
    folders = [path for path in extract.iterdir() if path.is_dir()]
    if len(folders) != 1:
        raise RuntimeError("unexpected SDK archive structure")
    shutil.copytree(folders[0], ROOT / "android-sdk" / destination, dirs_exist_ok=True)
    print(f"Installed {package}", flush=True)


if __name__ == "__main__":
    ROOT.mkdir(exist_ok=True)
    if not (ROOT / "android-repository.xml").exists():
        urllib.request.urlretrieve(BASE + "repository2-1.xml", ROOT / "android-repository.xml")
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(install, *args) for args in [
            ("platforms;android-35", "platforms/android-35", "platform-35_r02"),
            ("build-tools;35.0.0", "build-tools/35.0.0", "windows"),
            ("platform-tools", "platform-tools", "win"),
        ]]
        for future in futures:
            future.result()
