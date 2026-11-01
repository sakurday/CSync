"""Validate translator-owned files without building either application."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PARAMETER = re.compile(r"\{([A-Za-z][A-Za-z0-9_]*)}")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def validate(path, schema):
    language = read(path)
    meta = language["_meta"]
    assert meta["locale"] == path.stem, "filename must match _meta.locale"
    assert isinstance(meta["nativeName"], str) and meta["nativeName"].strip(), "nativeName is required"
    assert meta["direction"] in ("ltr", "rtl"), "direction must be ltr or rtl"
    messages = language["messages"]
    assert isinstance(messages, dict), "messages must be an object"
    assert not (messages.keys() - schema.keys()), f"unknown keys: {messages.keys() - schema.keys()}"
    if meta["locale"] == "qps-keys":
        assert not messages, "qps-keys is reserved for displaying keys and must stay empty"
    for key, value in messages.items():
        expected = set(schema[key]["parameters"])
        if isinstance(value, str):
            forms = [value]
        else:
            assert isinstance(value, dict) and "other" in value and "count" in expected, f"{key}: invalid plural object"
            assert value.keys() <= {"zero", "one", "two", "few", "many", "other"}, f"{key}: invalid plural category"
            forms = value.values()
        for text in forms:
            assert isinstance(text, str), f"{key}: translation must be text"
            if not text:
                continue
            assert set(PARAMETER.findall(text)) == expected, f"{key}: expected parameters {sorted(expected)}"
            assert "{" not in PARAMETER.sub("", text) and "}" not in PARAMETER.sub("", text), f"{key}: malformed placeholder"
    translated = sum(bool(v) for v in messages.values())
    print(f"{path.name}: {translated}/{len(schema)} translated; missing/empty entries display keys")


def check_source_keys(schema):
    paths = list((ROOT / "android/app/src/main/java").rglob("*.kt"))
    paths += [p for p in (ROOT / "crates/csync-windows/src").glob("*.rs") if "test" not in p.stem]
    paths += [ROOT / "crates/csync-windows/web/app.js", ROOT / "crates/csync-windows/web/index.html"]
    for path in paths:
        source = path.read_text(encoding="utf-8").split("#[cfg(test)]\nmod tests", 1)[0]
        keys = re.findall(r'''(?:\b(?:tr|msg|t)\(\s*["']|data-i18n(?:-[\w-]+)?=")([a-z][a-z0-9_.]+)["']''', source)
        for key in keys:
            assert key in schema, f"{path.relative_to(ROOT)}: unregistered key {key}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="*", type=Path, help="Optional language JSON files; partial translations are valid")
    args = parser.parse_args()
    schema = read(ROOT / "locales/messages.json")
    for key, value in schema.items():
        assert re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", key), f"invalid message key: {key}"
        assert value["description"].strip(), f"{key}: missing context"
    paths = args.files or sorted(p for p in (ROOT / "locales").glob("*.json") if p.name != "messages.json")
    for path in paths:
        validate(path, schema)
    check_source_keys(schema)
    print("Locale validation passed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, AssertionError) as error:
        raise SystemExit(f"Locale validation failed: {error}")
