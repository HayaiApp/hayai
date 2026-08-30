# Novel, storage, and issue repair architecture

## Chosen direction

Use the derived content identity design from candidate B. Hayai already retains novel source IDs in `hayai_novel_plugin_sources`, and Local Novel is stable, so identity can be recovered from durable existing evidence without adding another database table.

The cross-model judge preferred candidate A because its materialized identity table could outlive an uninstalled APK source. That assumption does not match Hayai's source model: novel sources are LNReader plugins remembered in Hayai storage, not disposable APK-only registrations. A new table would create another migration and synchronization surface while duplicating evidence that is already authoritative.

## Adopted additions

- Use one content identity service in library, statistics, and migration.
- Centralize source icons and authenticated cover requests so every migration surface gets the same artwork behavior.
- Represent download and backup locations as validated storage capabilities, including persisted SAF permissions and recoverable failures.
- Store filter snapshots with semantic keys and a schema fingerprint.
- Version offline asset metadata while retaining backward compatibility with old manifests.

## Rejected additions

- No second library or content identity table.
- No duplicate novel migration controller or reader activity.
- No broad storage rewrite that moves existing user files without an explicit choice.

## Verification contract

Each slice needs focused unit coverage, compilation, the upstream boundary check, and emulator evidence for UI or Android storage behavior. Issues are only closed after the matching behavior is present in a published nightly.
