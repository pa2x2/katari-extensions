#!/usr/bin/env python3

import argparse
import json
import re
import subprocess
from pathlib import Path

from build_repo import ROOT, discover_modules, source_id_field


MODULE_BUILD_FILE = "src/*/*/build.gradle.kts"
MODULE_METADATA_FILE = "src/*/*/repo-metadata.json"


def validate_source_id_references(modules: list[dict]) -> None:
    for module in modules:
        module_path = ROOT / module["path"]
        kotlin = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted((module_path / "src").rglob("*.kt"))
        )
        for source in module["sources"]:
            field = source_id_field(source["key"])
            reference = f"BuildConfig.{field}"
            if kotlin.count(reference) != 1:
                raise SystemExit(f"{module['path']} must consume {reference} exactly once")
            assignment = re.compile(
                rf"(?:override\s+val\s+id(?:\s*:\s*Long)?|sourceId)\s*=\s*{re.escape(reference)}\b",
            )
            if not assignment.search(kotlin):
                raise SystemExit(
                    f"{reference} must be assigned to a runtime source ID in {module['path']}",
                )


def validate_unchanged_source_ids(modules: list[dict], base: str | None) -> None:
    if not base:
        return
    for module in modules:
        metadata_path = f"{module['path']}/repo-metadata.json"
        try:
            previous_raw = subprocess.check_output(
                ("git", "-C", str(ROOT), "show", f"{base}:{metadata_path}"),
                text=True,
                stderr=subprocess.DEVNULL,
            )
        except subprocess.CalledProcessError:
            continue
        previous_sources = json.loads(previous_raw).get("sources", [])
        previous_ids = {
            source["key"]: source["id"]
            for source in previous_sources
            if isinstance(source, dict) and "key" in source and "id" in source
        }
        for source in module["sources"]:
            previous_id = previous_ids.get(source["key"])
            if previous_id is not None and previous_id != source["id"]:
                raise SystemExit(
                    f"Source ID is immutable for {module['path']}:{source['key']} "
                    f"({previous_id} -> {source['id']})",
                )


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate extension repository metadata")
    parser.add_argument("--base", help="Git revision used to reject changes to established source IDs")
    args = parser.parse_args()

    module_dirs = {path.parent for path in ROOT.glob(MODULE_BUILD_FILE)}
    metadata_dirs = {path.parent for path in ROOT.glob(MODULE_METADATA_FILE)}

    missing_metadata = sorted(module_dirs - metadata_dirs)
    if missing_metadata:
        missing_paths = ", ".join(path.relative_to(ROOT).as_posix() for path in missing_metadata)
        raise SystemExit(f"Missing repo-metadata.json for module(s): {missing_paths}")

    orphaned_metadata = sorted(metadata_dirs - module_dirs)
    if orphaned_metadata:
        orphaned_paths = ", ".join(path.relative_to(ROOT).as_posix() for path in orphaned_metadata)
        raise SystemExit(f"Found repo-metadata.json without build.gradle.kts for module(s): {orphaned_paths}")

    modules = discover_modules()
    validate_source_id_references(modules)
    validate_unchanged_source_ids(modules, args.base)


if __name__ == "__main__":
    main()
