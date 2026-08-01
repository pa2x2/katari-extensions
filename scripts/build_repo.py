#!/usr/bin/env python3

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = ROOT / "repo-config.json"
DEFAULT_BUILD_DIR = ROOT / "build" / "repo"
MODULE_METADATA_GLOB = "src/*/*/repo-metadata.json"
SUPPORTED_ENTRY_TYPES = {"MANGA", "ANIME", "BOOK"}


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def require_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def load_config() -> dict:
    with CONFIG_PATH.open(encoding="utf-8") as file:
        return json.load(file)


def load_module_metadata(metadata_path: Path) -> dict:
    with metadata_path.open(encoding="utf-8") as file:
        metadata = json.load(file)

    sources = metadata.get("sources")
    if not isinstance(sources, list) or not sources:
        raise SystemExit(f"Missing or empty sources in {metadata_path}")

    for index, source in enumerate(sources, start=1):
        missing = [key for key in ("id", "lang", "name", "baseUrl") if key not in source]
        if missing:
            raise SystemExit(f"Missing {missing} in source #{index} of {metadata_path}")

        entry_types = source.get("supportedEntryTypes")
        if entry_types is not None:
            if not isinstance(entry_types, list) or not entry_types:
                raise SystemExit(
                    f"supportedEntryTypes must be a non-empty list in source #{index} of {metadata_path}",
                )
            if not all(isinstance(entry_type, str) for entry_type in entry_types):
                raise SystemExit(
                    f"supportedEntryTypes must contain only strings in source #{index} of {metadata_path}",
                )
            unknown_types = sorted(set(entry_types) - SUPPORTED_ENTRY_TYPES)
            if unknown_types:
                raise SystemExit(
                    f"Unknown supportedEntryTypes {unknown_types} in source #{index} of {metadata_path}",
                )
            if len(entry_types) != len(set(entry_types)):
                raise SystemExit(
                    f"Duplicate supportedEntryTypes in source #{index} of {metadata_path}",
                )

    module_path = metadata_path.parent.relative_to(ROOT).as_posix()
    return {
        "path": module_path,
        "slug": metadata_path.parent.name,
        "apkName": f"{metadata_path.parent.name}.apk",
        "sources": sources,
    }


def validate_unique_slugs(modules: list[dict]) -> None:
    modules_by_slug: dict[str, list[str]] = {}
    for module in modules:
        modules_by_slug.setdefault(module["slug"], []).append(module["path"])
    duplicate_slugs = {
        slug: paths
        for slug, paths in modules_by_slug.items()
        if len(paths) > 1
    }
    if duplicate_slugs:
        details = "; ".join(
            f"{slug}: {', '.join(paths)}"
            for slug, paths in sorted(duplicate_slugs.items())
        )
        raise SystemExit(f"Module slugs must be globally unique ({details})")


def discover_modules(selected: set[str] | None = None) -> list[dict]:
    modules = [load_module_metadata(path) for path in sorted(ROOT.glob(MODULE_METADATA_GLOB))]
    validate_unique_slugs(modules)

    if selected is not None:
        modules = [module for module in modules if module["path"] in selected]
        found = {module["path"] for module in modules}
        missing = sorted(selected - found)
        if missing:
            raise SystemExit(f"Unknown module path(s): {', '.join(missing)}")
    return modules


def requested_module_paths(modules: list[str], deleted: list[str]) -> set[str] | None:
    return set(modules) if modules or deleted else None


def latest_build_tools(sdk_root: str) -> Path:
    directory = Path(sdk_root) / "build-tools"
    versions = sorted(path for path in directory.iterdir() if path.is_dir())
    if not versions:
        raise SystemExit(f"No Android build-tools found in {directory}")
    return versions[-1]


def parse_xmltree_value(raw_value: str) -> str | int:
    raw_value = raw_value.strip()
    if raw_value.startswith('"'):
        return raw_value.split('"', 2)[1]
    if raw_value.startswith("(type 0x10)"):
        return int(raw_value.removeprefix("(type 0x10)"), 16)
    return raw_value


def parse_manifest(aapt: Path, apk_path: Path) -> dict:
    output = run(str(aapt), "dump", "xmltree", str(apk_path), "AndroidManifest.xml")
    result = {"package": None, "versionCode": None, "versionName": None, "label": None, "nsfw": 0}
    element_stack: list[tuple[int, str]] = []
    current_meta_name = None

    for raw_line in output.splitlines():
        indent = len(raw_line) - len(raw_line.lstrip())
        line = raw_line.strip()
        if line.startswith("E: "):
            while element_stack and element_stack[-1][0] >= indent:
                element_stack.pop()
            element_name = line[3:].split(" ", 1)[0]
            element_stack.append((indent, element_name))
            if element_name == "meta-data":
                current_meta_name = None
            continue
        if not line.startswith("A: ") or not element_stack:
            continue

        attribute = line[3:]
        attribute_name, _, raw_value = attribute.partition("=")
        attribute_name = attribute_name.split("(", 1)[0]
        value = parse_xmltree_value(raw_value)
        current_element = element_stack[-1][1]
        if current_element == "manifest":
            if attribute_name == "package":
                result["package"] = value
            elif attribute_name == "android:versionCode":
                result["versionCode"] = value
            elif attribute_name == "android:versionName":
                result["versionName"] = value
        elif current_element == "application" and attribute_name == "android:label":
            result["label"] = value
        elif current_element == "meta-data":
            if attribute_name == "android:name":
                current_meta_name = value
            elif attribute_name == "android:value" and current_meta_name == "tachiyomi.extension.nsfw":
                result["nsfw"] = value

    missing = [key for key, value in result.items() if value is None]
    if missing:
        raise SystemExit(f"Failed to read {missing} from {apk_path}")
    return result


def locate_unsigned_apk(module: dict, artifacts_dir: Path | None) -> Path:
    if artifacts_dir:
        matches = sorted(artifacts_dir.glob(f"**/{module['slug']}-release-unsigned.apk"))
        if len(matches) != 1:
            raise SystemExit(f"Expected one unsigned APK for {module['path']}, found {len(matches)}")
        return matches[0]

    release_dir = ROOT / module["path"] / "build" / "outputs" / "apk" / "release"
    metadata_path = release_dir / "output-metadata.json"
    with metadata_path.open(encoding="utf-8") as file:
        metadata = json.load(file)
    return release_dir / metadata["elements"][0]["outputFile"]


def sign_and_align_apk(
    input_apk: Path,
    final_apk: Path,
    build_tools: Path,
    keystore_path: str,
    store_password: str,
    key_alias: str,
) -> None:
    aligned_apk = final_apk.with_stem(f"{final_apk.stem}-aligned")
    run(str(build_tools / "zipalign"), "-f", "4", str(input_apk), str(aligned_apk))
    run(
        str(build_tools / "apksigner"),
        "sign",
        "--v4-signing-enabled",
        "false",
        "--ks",
        keystore_path,
        "--ks-key-alias",
        key_alias,
        "--ks-pass",
        f"pass:{store_password}",
        "--key-pass",
        f"pass:{store_password}",
        "--out",
        str(final_apk),
        str(aligned_apk),
    )
    aligned_apk.unlink(missing_ok=True)


def extract_fingerprint(build_tools: Path, apk_path: Path) -> str:
    output = run(str(build_tools / "apksigner"), "verify", "--print-certs-pem", str(apk_path))
    begin_marker = "-----BEGIN CERTIFICATE-----"
    end_marker = "-----END CERTIFICATE-----"
    begin = output.find(begin_marker)
    end = output.find(end_marker, begin + len(begin_marker))
    if begin == -1 or end == -1:
        raise SystemExit(f"Could not extract signing certificate from {apk_path}")

    encoded_certificate = "".join(
        output[begin + len(begin_marker) : end].split(),
    )
    try:
        certificate = base64.b64decode(encoded_certificate, validate=True)
    except ValueError as error:
        raise SystemExit(f"Could not decode signing certificate from {apk_path}: {error}") from error

    return hashlib.sha256(certificate).hexdigest()


def extension_lib(version_name: str) -> str:
    return version_name.rsplit(".", 1)[0]


def source_to_v2(source: dict) -> dict:
    result = {
        "id": source["id"],
        "name": source["name"],
        "language": source["lang"],
        "homeUrl": source["baseUrl"],
    }
    if "supportedEntryTypes" in source:
        result["supportedEntryTypes"] = source["supportedEntryTypes"]
    return result


def apk_name_from_item(item: dict) -> str:
    return Path(urlparse(item["resources"]["apkUrl"]).path).name


def load_existing_items(output_dir: Path) -> tuple[list[dict], str | None]:
    index_path = output_dir / "index.json"
    if not index_path.exists():
        return [], None
    with index_path.open(encoding="utf-8") as file:
        store = json.load(file)
    return store.get("extensionList", {}).get("extensions", []), store.get("signingKey")


def copy_icon(module_path: Path, icon_dir: Path, package_name: str) -> None:
    candidates = [
        module_path / "res" / density / "ic_launcher.png"
        for density in ("mipmap-xxxhdpi", "mipmap-xxhdpi", "mipmap-xhdpi", "mipmap-hdpi", "mipmap-mdpi")
    ]
    source = next((path for path in candidates if path.exists()), None)
    if source is None:
        raise SystemExit(f"No launcher icon found in {module_path}")
    shutil.copy2(source, icon_dir / f"{package_name}.png")


def write_index(config: dict, output_dir: Path, signing_key: str, items: list[dict]) -> None:
    meta = config["meta"]
    store = {
        "name": meta["name"],
        "badgeLabel": meta.get("shortName") or meta["name"],
        "signingKey": signing_key,
        "contact": {"website": meta["website"]},
        "extensionList": {"extensions": sorted(items, key=lambda item: item["packageName"])},
    }
    with (output_dir / "index.json").open("w", encoding="utf-8") as file:
        json.dump(store, file, ensure_ascii=False, separators=(",", ":"))
        file.write("\n")


def build_repository(args: argparse.Namespace) -> None:
    config = load_config()
    output_dir = args.output.resolve()
    apk_dir = output_dir / "apk"
    icon_dir = output_dir / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)

    selected = requested_module_paths(args.modules, args.delete)
    modules = discover_modules(selected)
    items, existing_fingerprint = load_existing_items(output_dir)

    deleted_apks = {f"{slug}.apk" for slug in args.delete}
    removed_items = [item for item in items if apk_name_from_item(item) in deleted_apks]
    items = [item for item in items if apk_name_from_item(item) not in deleted_apks]
    for item in removed_items:
        (icon_dir / f"{item['packageName']}.png").unlink(missing_ok=True)
    for apk_name in deleted_apks:
        (apk_dir / apk_name).unlink(missing_ok=True)

    if not modules and not args.delete:
        raise SystemExit("No changed or deleted modules were supplied")

    build_tools = latest_build_tools(require_env("ANDROID_SDK_ROOT"))
    aapt = build_tools / "aapt"
    fingerprint = existing_fingerprint
    configured_fingerprint = config["meta"].get("signingKeyFingerprint", "").lower() or None
    artifact_base_url = config["meta"]["artifactBaseUrl"].rstrip("/")

    for module in modules:
        unsigned_apk = locate_unsigned_apk(module, args.artifacts)
        final_apk = apk_dir / module["apkName"]
        sign_and_align_apk(
            unsigned_apk,
            final_apk,
            build_tools,
            require_env("EXT_KEYSTORE_PATH"),
            require_env("EXT_KEYSTORE_PASSWORD"),
            require_env("EXT_KEY_ALIAS"),
        )
        actual_fingerprint = extract_fingerprint(build_tools, final_apk)
        expected = configured_fingerprint or fingerprint
        if expected and actual_fingerprint != expected:
            raise SystemExit(f"Signing fingerprint mismatch: expected {expected}, got {actual_fingerprint}")
        fingerprint = actual_fingerprint

        manifest = parse_manifest(aapt, final_apk)
        copy_icon(ROOT / module["path"], icon_dir, str(manifest["package"]))
        items = [item for item in items if apk_name_from_item(item) != module["apkName"]]
        items.append(
            {
                "name": manifest["label"],
                "packageName": manifest["package"],
                "resources": {
                    "apkUrl": f"{artifact_base_url}/apk/{module['apkName']}",
                    "iconUrl": f"{artifact_base_url}/icon/{manifest['package']}.png",
                },
                "extensionLib": extension_lib(str(manifest["versionName"])),
                "versionCode": manifest["versionCode"],
                "versionName": manifest["versionName"],
                "contentWarning": "CONTENT_WARNING_NSFW" if int(manifest["nsfw"]) == 1 else "CONTENT_WARNING_SAFE",
                "sources": [source_to_v2(source) for source in module["sources"]],
            }
        )

    if not fingerprint:
        raise SystemExit("Cannot publish an empty repository without an established signing fingerprint")
    write_index(config, output_dir, fingerprint, items)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sign extensions and incrementally update a Katari repository")
    parser.add_argument("--module", dest="modules", action="append", default=[], help="Module path relative to the repository root")
    parser.add_argument("--delete", action="append", default=[], help="Deleted module slug")
    parser.add_argument("--artifacts", type=Path, help="Directory containing CI-built unsigned APKs")
    parser.add_argument("--output", type=Path, default=DEFAULT_BUILD_DIR, help="Existing/generated repository directory")
    return parser.parse_args()


if __name__ == "__main__":
    build_repository(parse_args())
