#!/usr/bin/env python3

import argparse
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MODULE_BUILD_GLOB = "src/*/*/build.gradle.kts"
GLOBAL_PREFIXES = ("gradle/", "scripts/")
GLOBAL_FILES = {
    "build.gradle.kts",
    "gradle.properties",
    "settings.gradle.kts",
    "repo-config.json",
}


def git(*args: str) -> str:
    return subprocess.check_output(("git", "-C", str(ROOT), *args), text=True).strip()


def all_modules() -> dict[str, str]:
    return {
        path.parent.relative_to(ROOT).as_posix(): path.parent.name
        for path in sorted(ROOT.glob(MODULE_BUILD_GLOB))
    }


def changed_paths(base: str | None, head: str) -> list[tuple[str, str]]:
    if not base:
        return [("A", path) for path in all_modules()]
    output = git("diff", "--name-status", "--find-renames", base, head)
    changes = []
    for line in output.splitlines():
        fields = line.split("\t")
        status = fields[0]
        changes.append((status, fields[-1]))
        if status.startswith("R"):
            changes.append(("D", fields[1]))
    return changes


def module_path(path: str) -> str | None:
    parts = Path(path).parts
    if len(parts) >= 4 and parts[0] == "src":
        return Path(*parts[:3]).as_posix()
    return None


def select_modules(
    modules: dict[str, str],
    changes: list[tuple[str, str]],
    missing_base: bool = False,
) -> tuple[set[str], set[str]]:
    rebuild_all = missing_base or any(
        path in GLOBAL_FILES or path.startswith(GLOBAL_PREFIXES)
        for status, path in changes
        if status != "D" or not path.startswith("src/")
    )

    selected = set(modules) if rebuild_all else {
        candidate
        for _, path in changes
        if (candidate := module_path(path)) in modules
    }
    deleted = {
        Path(candidate).name
        for status, path in changes
        if status == "D"
        and (candidate := module_path(path)) is not None
        and candidate not in modules
    }
    return selected, deleted


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--head", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    modules = all_modules()
    changes = changed_paths(args.base, args.head)
    selected, deleted = select_modules(modules, changes, missing_base=args.base is None)

    matrix = {
        "include": [
            {
                "path": path,
                "slug": modules[path],
                "task": f":{path.replace('/', ':')}:assembleRelease",
            }
            for path in sorted(selected)
        ]
    }
    outputs = {
        "matrix": json.dumps(matrix, separators=(",", ":")),
        "modules": json.dumps(sorted(selected), separators=(",", ":")),
        "deleted": json.dumps(sorted(deleted), separators=(",", ":")),
        "has_builds": str(bool(selected)).lower(),
        "has_changes": str(bool(selected or deleted)).lower(),
    }
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as file:
            for key, value in outputs.items():
                file.write(f"{key}={value}\n")
    else:
        print(json.dumps(outputs, indent=2))


if __name__ == "__main__":
    main()
