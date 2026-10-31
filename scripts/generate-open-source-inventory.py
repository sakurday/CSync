"""Generate About inventories from resolved Cargo and Android runtime dependencies.

First run cargo metadata --locked --filter-platform x86_64-pc-windows-msvc
--format-version 1 > .build/cargo-metadata-about.json and the Gradle task
:app:exportOpenSourceDependencies. No remote metadata is fetched by this script.
"""
import html
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
metadata = json.loads((root / ".build/cargo-metadata-about.json").read_text(encoding="utf-8-sig"))
packages = {p["id"]: p for p in metadata["packages"]}
nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
start = next(p["id"] for p in packages.values() if p["name"] == "csync-windows")
seen, queue = set(), [start]
while queue:
    key = queue.pop()
    if key in seen:
        continue
    seen.add(key)
    for dep in nodes[key]["deps"]:
        if any(k["kind"] is None for k in dep["dep_kinds"]):
            queue.append(dep["pkg"])
windows = []
for key in seen:
    package = packages[key]
    if package["name"].startswith("csync-"):
        continue
    windows.append((package["name"], package["version"], package["license"] or "See project license", package.get("repository") or package.get("homepage") or ""))
web = json.loads((root / "crates/csync-windows/web/package-lock.json").read_text(encoding="utf-8"))
for key, package in web["packages"].items():
    if not key or package.get("dev"):
        continue
    name = key.split("node_modules/")[-1]
    windows.append((name, package["version"], package.get("license", "See project license"), "https://www.npmjs.com/package/" + name))
windows.sort(key=lambda entry: entry[0].lower())
rows = [f'<li><strong>{html.escape(name)}</strong> {html.escape(version)}<br><span>{html.escape(license)}</span><br><small>{html.escape(url)}</small></li>' for name, version, license, url in windows]
(root / "crates/csync-windows/web/open-source.html").write_text("<ul class=\"libraries\">" + "\n".join(rows) + "</ul>", encoding="utf-8")

cache = root / ".build/gradle-home/caches/modules-2/files-2.1"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}

def pom_licenses(group, name, version, visited=None):
    visited = visited or set()
    key = (group, name, version)
    if key in visited:
        return [], []
    visited.add(key)
    poms = list((cache / group / name / version).glob("*/*.pom"))
    if not poms:
        return [], []
    pom = ET.parse(poms[0]).getroot()
    items = pom.findall("m:licenses/m:license", ns)
    if items:
        return ([i.findtext("m:name", default="", namespaces=ns) for i in items],
                [i.findtext("m:url", default="", namespaces=ns) for i in items])
    parent = pom.find("m:parent", ns)
    if parent is not None:
        return pom_licenses(*(parent.findtext("m:" + field, default="", namespaces=ns)
                              for field in ("groupId", "artifactId", "version")), visited)
    return [], []

for variant in ("debug", "release"):
    android = []
    missing = []
    for line in (root / f".build/android-open-source-{variant}.tsv").read_text(encoding="utf-8-sig").splitlines():
        group, name, version = line.split("\t")
        license_names, urls = pom_licenses(group, name, version)
        if not license_names:
            missing.append(f"{group}:{name}:{version}")
        android.append(f"{group}:{name} {version}\n{' / '.join(license_names) or 'See project license'}\n{' '.join(urls)}")
    # Debug adds tooling libraries in a distinct, debug-only asset.
    source = "debug" if variant == "debug" else "main"
    assets = root / f"android/app/src/{source}/assets"
    assets.mkdir(parents=True, exist_ok=True)
    filename = "open_source_libraries_debug.txt" if variant == "debug" else "open_source_libraries.txt"
    (assets / filename).write_text("\n\n".join(android) + "\n", encoding="utf-8")
    print(f"Android {variant} libraries: {len(android)}")
    if missing:
        raise SystemExit("Missing license metadata: " + ", ".join(missing))
print(f"Windows libraries: {len(windows)}")
