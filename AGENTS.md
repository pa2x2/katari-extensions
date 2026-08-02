# Repository Notes

- This is the public Gradle Android extension repository for Katari.
- The module layout is nonstandard: each module keeps `AndroidManifest.xml` at the module root, Kotlin under `src/`, and resources under `res/`. Do not switch to `src/main`.
- Extension modules are discovered automatically from `src/*/*/build.gradle.kts`; do not add manual `include(...)` entries to `settings.gradle.kts`.
- `extClass` in each manifest must point at the module's `*Factory` class. Factories implement `EntrySourceFactory` and return `UnifiedSource` instances.
- Every shipped module needs a sibling `repo-metadata.json`. Each `sources[]` entry requires a stable `key`, `id`, `lang`, `name`, and `baseUrl`. The shared Gradle configuration generates `BuildConfig.SOURCE_ID_<KEY>` from that metadata; source implementations must use the generated value for their runtime ID instead of duplicating numeric IDs.
- Run `python3 scripts/validate_repo_metadata.py` as the fast metadata preflight. It enforces module coverage, globally unique IDs, and one runtime ID assignment per metadata source; CI also supplies `--base` to make established IDs immutable.
- Builds normally resolve the tagged Katari SDK version in `gradle.properties` from JitPack. Coordinated unreleased SDK work uses `-PuseMavenLocal=true`, which selects `local-SNAPSHOT`; otherwise extension development does not require a local Katari checkout.
- Toolchain versions are pinned in the repository. Java 17 is required.

## Extension Structure

- Keep `<Name>Factory.kt` separate from `<Name>Source.kt`; the factory should only construct sources.
- Keep `<Name>Source.kt` focused on Entry SDK overrides and high-level request/response orchestration.
- Move coherent nontrivial concerns into responsibility-named files such as models, filters, parsers, preferences, interceptors, or configuration.
- Create only files representing real concerns. Do not add empty placeholders, arbitrary one-type-per-file splits, or catch-all utility files.
- When materially changing a monolithic extension, decompose the affected responsibilities as part of the change.

## Testing

- Keep extension modules free of tests, test source sets, and test dependencies unless the user explicitly requests them.
- Validate ordinary extension changes with the metadata preflight and focused extension builds.

## Public Repository Policy

- Preserve application IDs, signing identity, source IDs, and stored URL identity after publication.
- Do not restore a source listed in `REMOVED_SOURCES.md`.
