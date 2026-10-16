# Repository Guidelines

## Project Structure & Modules

This is a Gradle multi-module Android project. `app/` contains the Compose UI, Android entry point, workers, resources, and app tests. `domain/` holds platform-independent Kotlin business logic and JVM tests. `data/` contains data and repository adapters with Android unit tests. `tools/` contains Python collection, analysis, and backtest scripts; `docs/` contains product, architecture, and engineering specifications. Keep domain calculations independent of Android APIs and put platform or persistence code in the appropriate outer module.

## Build, Test, and Development

Use JDK 21 and Android SDK 35 as described in `README.md`; open the repository root in Android Studio or use the wrapper:

- `./gradlew :app:assembleDebug` builds the debug APK.
- `./gradlew :domain:test` runs the domain JVM tests.
- `./gradlew :data:testDebugUnitTest :app:testDebugUnitTest` runs Android module unit tests on the host JVM.
- `python3 tools/healthcheck.py` runs the data health checks; inspect `tools/README.md` before running collection or maintenance scripts, which may access external sources or local data.

## Coding Style & Naming

Follow nearby Kotlin style with four-space indentation. Use `UpperCamelCase` for types and `lowerCamelCase` for functions and properties; name unit test classes with a `Test` suffix. The engineering plan sets a 120-column Kotlin target, though no formatter task is currently configured. For Python, follow the surrounding module style. Do not name scripts after Python standard-library modules; see `tools/README.md` for examples and the full list.

When a nullable public property comes from another Gradle module, copy it to a local `val` before checking and using it; Kotlin cannot smart-cast such properties across module boundaries.

## Testing Guidelines

Tests use JUnit 4 and live under each module's `src/test/` tree, mirroring production package paths. Add focused tests for changed calculations, data mappings, and edge cases; keep numerical test inputs deterministic. No repository-wide coverage threshold or Python test command is currently configured.

## Commits and Pull Requests

Git history is unavailable in this checkout, so no existing commit convention could be verified. Use a short imperative subject scoped by module, for example `domain: handle empty cash flows`. PRs should describe user-visible and data-model changes, list validation commands and results, link related work, and include screenshots for UI changes.

## Configuration and Data Safety

Keep machine-specific SDK paths in the ignored `local.properties`; never commit credentials, private portfolio data, local databases, or generated build output. Preserve the product rules in `README.md`: do not add trading or investment advice, and retain data source and timestamp metadata for market quotes.
