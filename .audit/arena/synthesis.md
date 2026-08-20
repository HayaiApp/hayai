# Architecture arena synthesis

Candidate B won the independent review, 26/30 versus Candidate C at 9/30. It keeps J2K as the single authority for library and progress state and confines Hayai to additive tables, capabilities, and gateways.

Accepted corrections from the judge:

- Import directly within the active target database transaction. Do not rename a live SQLite database without accounting for WAL and sidecar files.
- Route every chapter-opening path through a centralized `ReaderLauncher` so novel handling is not scattered and the J2K image reader remains untouched.

Candidate C's duplicate library, progress, source, and settings stores were rejected because they would create two sources of truth and require permanent synchronization with J2K.

The implementation follows Candidate B with those corrections: `hayai-j2k.db` is active, legacy `tachiyomi.db` is read-only, and one launch router selects the Hayai text reader or upstream J2K image reader.
