# Contributing

Contributions are welcome for existing extensions and, after prior discussion, new sources.

## Commit messages

Commit subjects must use the following format:

```text
(type): summary
```

Activate the repository's commit-message hook once in each clone:

```bash
git config core.hooksPath .githooks
```

Use one of these types:

- `feat`: add or extend functionality.
- `fix`: correct faulty behavior.
- `docs`: change documentation only.
- `style`: change formatting without affecting behavior.
- `refactor`: restructure code without changing behavior.
- `perf`: improve performance.
- `test`: add or update tests.
- `build`: change the build system or dependencies.
- `ci`: change continuous-integration configuration.
- `chore`: perform maintenance not covered by another type.
- `revert`: revert an earlier change.

For example:

```text
(feat): add profile export
(fix): preserve prose reader position
(docs): explain SDK versioning
```

Git-generated subjects beginning with `Merge `, `Revert `, `fixup! `, or `squash! ` are exempt from this format. Commit-message bodies are unrestricted.

## Before opening a pull request

1. For a new source, open an issue describing the website, language, content types, and why it is suitable for public distribution.
2. Check `REMOVED_SOURCES.md`. Removed sources may not be reintroduced without explicit maintainer authorization.
3. Preserve published Android package identity, source IDs, and stored entry/child URLs.
4. Increase `versionCode` for every extension release and the final component of `versionName` for changes targeting the same Entry API family.
5. Keep each source's optional repository `supportedEntryTypes` metadata aligned with its runtime `SourceMetadata` declaration.
6. Keep the extension decomposed according to `AGENTS.md`.

## Validate locally

```bash
python3 scripts/validate_repo_metadata.py
./gradlew :src:all:rezka:assembleDebug
git diff --check
```

Replace the module path for another extension. Builds resolve the tagged Katari SDK configured in `gradle.properties` from JitPack; a local Katari checkout is not required.

For coordinated work on an unreleased Katari SDK, first publish the complete SDK from an adjacent Katari checkout:

```bash
../katari/gradlew --quiet -p ../katari publishEntrySdkToMavenLocal
```

Then add the repository's single local-development switch to the normal extension task:

```bash
./gradlew --quiet -PuseMavenLocal=true :src:en:gutenberg:assembleDebug
```

The switch enables Maven Local and selects `local-SNAPSHOT`; no build file or `gradle.properties` edits are required. Omit it for release validation and confirm the repository has returned to a stable `sdk-*` version before publishing.

## OpenCode workflow

The bundled `/develop-extension <website>` command invokes the `extension-development` skill. Its first phase is deliberately read-only: it inspects the configured SDK tag from Katari's public GitHub repository, website capabilities, media behavior, and safe request policy before proposing an implementation. Implementation requires explicit approval after that report.

## Pull requests

Pull requests must pass CI and receive approval from a reviewer with write permission. Maintainers may decline extensions that are unsafe, legally unsuitable, unmaintainable, duplicative, or incompatible with Katari's public SDK.
