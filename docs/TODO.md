# RVC TODO

Last reviewed: 2026-05-29.

This document lists the remaining "do it later" work found during a review of the current RVC implementation. It focuses on RVC code and the Litematica UI paths touched by RVC.

Important rule: if an operation changes the active RVC state, it must not stop at Git or filesystem changes. It must also update the visible in-game state: world blocks, ghost overlay, and verifier state where available.

## Semantic MVP Status

New RVC projects now use the semantic chunk storage direction described in `docs/tech/rvc-semantic-storage.md`.

Done:

- Semantic manifest/local state: `rvc.json` and local-only `local.json`.
- Content-addressed `.rvcchunk` objects under `objects/sha256/`.
- Deterministic block state storage.
- Deterministic block entity NBT storage with absolute `x/y/z` removed.
- Fake-world and Minecraft `Level` capture readers.
- Singleplayer semantic init/commit capture uses integrated-server `ServerLevel` when available.
- Manual active-site semantic scan hashes current tracked chunks without writing objects, uses the same integrated-server capture path, and reports clean/dirty/unknown in the project GUI.
- Semantic active-site `Update areas` reads the current Litematica selection, updates versioned `rvc.json` regions, recaptures content, and commits.
- Semantic repo init and commit through JGit.
- Project listing supports both semantic `rvc.json` repos and legacy `index.json` repos.
- Project browser delete is implemented with confirmation and validated recursive deletion under `run/rvc-projects`.
- Project browser manual Create Project flow creates an empty semantic repo with `rvc.json`, ignored `local.json`, `.git`, and no initial commit. After the name popup closes, the user remains in Project Browser and can open Project Editor manually.
- Project/project-manager UI polish: project browser navigation rooted at `rvc-projects`, conditional scrollbar rendering, searchable/scrollable commit history, and selected commit metadata with title/author/date/version/changes.
- Project Editor MVP opens from the project page for semantic repos, exposes the single active site, edits project name/sub-regions in `rvc.json`, edits local site origin in ignored `local.json`, and returns to Project Manager for Save Version capture/commit.
- Integration coverage for semantic storage, object reuse, fake-world capture, canonical Minecraft state encoding, and semantic commits.

Not done:

- Project Editor UI rework to fully match the planned Litematica-style layout: top project/origin controls without an extra shaded box, Litematica-style sub-region list, and `Corner Mode: Corners/Expand`.
- GitMatica-owned in-world sub-region tool mode that mirrors Litematica area selection behavior for selected Project Editor sub-regions.
- Semantic export/overlay/restore.
- Semantic checkout/pull restore.
- Legacy `index.json` update areas.
- World association UX: projects remain portable, but `local.json` should eventually track current-world identity/hints and warn before using a repo in a different world.
- Optional import workflow for existing `.litematic` files into semantic RVC repos, preserving sub-region definitions so users do not need to paste, reselect, and recreate sub-regions manually.
- Rich update-area preview and explicit origin-change controls.
- Multi-site Project Editor UX; the MVP editor intentionally exposes only the active `main` site even though the manifest supports sites internally.
- Dedicated-server multiplayer support.
- Scheduled tick capture.
- Entity capture/restore.

### Import Existing `.litematic` Files

Current state:

- RVC creates semantic repos from world selections or empty browser-created projects.
- Export to `.litematic` is still pending.
- Importing an existing `.litematic` directly into RVC is not implemented.

Required behavior:

- Convert an existing `.litematic` into a semantic RVC repo.
- Preserve the `.litematic` sub-region names and bounds in `rvc.json`.
- Store block/block-entity content as semantic chunks.
- Treat overlapping sub-regions as valid tracking masks. RVC content should use union semantics, so each project coordinate is stored once even if covered by multiple sub-regions.
- If an imported file somehow contains conflicting contents for the same project coordinate across overlapping sub-regions, reject the import with a clear error instead of guessing.

Reason:

- Lets users version existing schematic files without pasting them into a world, reselecting the build, and recreating all sub-regions manually.

Use `docs/agent/rvc-mvp-slices.md` as the current thin-slice plan.

## P0 - Correctness And Safety

### Semantic Commit UX Polish

Current state:

- Semantic no-op commits return `null` at service level.
- `GuiRvcProject` currently reports generic commit success and does not clearly distinguish real commit vs no-op.
- Semantic commits also cannot reload overlay yet.

Required behavior:

- Show `nothing to commit` for semantic no-op commits.
- Show successful commit only when a new Git commit is created.
- Keep legacy `index.nbt` post-commit overlay reload behavior unchanged.
- Do not surface semantic overlay/export absence as a failed commit.

Relevant files:

- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`
- `src/main/java/me/zly2006/rvc/RvcProjectService.java`

### Extend Manual `Scan Changes` Into Preflight

Current state:

- The semantic project page has a `Scan changes` button.
- It hashes the active site's currently tracked semantic chunks without writing objects or changing `rvc.json`.
- It compares current hashes to manifest chunk refs.
- It reports clean, dirty, and unknown states.
- In singleplayer it uses the same integrated-server `ServerLevel` path as semantic init/commit.
- Existing legacy verifier path remains overlay-based and is not the long-term semantic dirty model.

Required behavior:

- Reuse this scan as preflight for future commit/checkout/pull/reset/merge flows.
- Add stale-state invalidation after world edits or time passing, so old clean scans are not treated as current.
- Extend unknown handling to dedicated-server multiplayer through a server-side RVC path.
- Keep unavailable authoritative chunks as unknown, not clean.

Relevant files:

- `src/main/java/me/zly2006/rvc/RvcCaptureEngine.java`
- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Implement Semantic Export, Overlay, And Restore

Current state:

- Semantic repos can init and commit.
- Semantic checkout, pull restore, and overlay load are explicitly blocked.

Required behavior:

- Reconstruct a Litematica/vanilla-structure view from `rvc.json` chunk refs and `.rvcchunk` objects.
- Preserve untracked gaps as `minecraft:structure_void` or equivalent non-overwrite behavior.
- Load ghost overlay/verifier for semantic repo state.
- Restore checked-out semantic state into tracked positions only.
- Cache reconstructed preview/overlay structures by immutable repo path + commit hash + site id, not branch name.
- Use a small LRU cache so frequent commit/branch inspection can reuse recent reconstructed views without rebuilding every time.
- Make preview cache entry count configurable; suggested MVP default is 3 entries with a conservative range such as 1-10. Clear cache on project close/world close, and consider memory-aware eviction later for huge builds.

Relevant files:

- `src/main/java/me/zly2006/rvc/RvcSemanticRepository.java`
- `src/main/java/me/zly2006/rvc/RvcChunkCodec.java`
- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Implement `Update areas`

Current state:

- The RVC project page has an `Update areas` button.
- For semantic repos, it reads the current Litematica area selection.
- It updates versioned `rvc.json` region definitions for the active site.
- It preserves `local.json` origin.
- It recaptures the active site content and commits the updated regions/chunks.
- It shows a basic confirmation with region count.
- Legacy repos still report unsupported for this button.

Required behavior:

- For legacy repos, update versioned `index.json` sub-region definitions.
- Add a richer preview of changed sub-regions, bounds, added/removed tracked chunks, and region renames.
- Update local-only `local.json` origin only if the user explicitly requests it.
- Refresh the in-game overlay/verifier after the update when supported for the repo format.

Relevant files:

- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`
- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/resources/assets/litematica/lang/en_us.json`
- `src/main/resources/assets/litematica/lang/zh_cn.json`

### Add Preview And Confirmation For World-Changing Operations

Current state:

- `Checkout` now updates the Git working tree and restores blocks into the current world.
- `Pull` now restores the working tree into the current world after pulling.
- These operations are destructive for tracked sub-regions and currently run immediately after a button click.

Required behavior:

- Show which commit/version will be applied.
- Show affected sub-region count and bounds.
- Warn that current world blocks inside tracked sub-regions will be overwritten.
- Require explicit confirmation before writing blocks.
- Keep the rule that untracked space between sub-regions must not be modified.

Relevant files:

- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`
- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/RvcStructure.java`

### Handle Git Dirty State, Merge Conflicts, And Failed Pulls

Current state:

- `checkoutCommitToWorkingTree()` delegates to JGit checkout.
- `pull()` delegates to JGit pull and returns `OK` or `FAILED`.
- The GUI does not distinguish dirty working tree, merge conflict, auth failure, detached HEAD surprises, or non-fast-forward cases.
- `Pull` attempts game restoration after JGit reports success, but conflict states need explicit guarding and messaging.

Required behavior:

- Check `git.status()` before checkout and pull.
- Block destructive operations when the working tree has uncommitted RVC changes unless the user explicitly chooses a recovery path.
- Detect merge conflicts and refuse to restore into the world while the repository is conflicted.
- Display actionable error messages for dirty tree, conflict, no remote, auth failure, and non-fast-forward cases.

Relevant files:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Make In-Game Restore Server-Authoritative

Current state:

- Restore uses `Level#setBlock` on the current client world.
- This is enough for local/integrated testing paths but may not be server-authoritative on multiplayer servers.
- There is no permission check, command mode, or server-side apply path.
- Semantic init/commit capture now resolves integrated-server `ServerLevel` in singleplayer when available.
- Client-only multiplayer semantic capture still falls back to the client `Level`, which is not authoritative.
- Research note: Servux is likely the best model/path for dedicated-server support. It is a server-side Fabric mod for masa client mods, server-only on Modrinth, and 0.3.x added Litematica server-side saving/pasting with full tile entity data. See https://modrinth.com/mod/servux and https://github.com/maruohon/servux.

Required behavior:

- Decide the supported restore modes: single-player direct world write, integrated-server task, multiplayer command placement, or server-side RVC support.
- Prefer investigating a Servux-backed or Servux-compatible server protocol before inventing a separate server mod path.
- Refuse checkout/pull restore when the current world cannot be modified authoritatively.
- Report a clear message instead of silently creating client-only visual changes.
- For dedicated servers, require server-side RVC support for reliable scan/commit/restore.

Relevant files:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Entity Restore Needs Cleanup Semantics

Current state:

- Schematic export masks entities outside tracked sub-regions.
- Restore can place entities from the schematic.
- Existing entities in the target sub-regions are not cleared or reconciled before restore.

Risk:

- Checkout/pull can duplicate entities or leave stale entities that were removed in the checked-out version.

Required behavior:

- Define whether RVC tracks entities by default.
- If entities are tracked, remove/reconcile existing entities inside tracked sub-regions before spawning restored entities.
- If entities are not tracked, disable entity placement during restore and document that behavior.

Relevant file:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`

## P1 - User Workflows

### Upgrade `Inspect` From A Message To A Real View

Current state:

- History rows have an `Inspect` button.
- It only displays the short commit id and message in the GUI message area.

Required behavior:

- Show commit id, parent ids, author, time, message, and RVC metadata.
- Show changed files and whether `index.json` or `index.nbt` changed.
- Show sub-region metadata at that commit.
- Provide entry points for diff and checkout preview.

Relevant file:

- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Add Diff Workflow

Current state:

- No diff button or diff view exists.
- The PRD mentions history inspection workflows, but no schematic diff is implemented.

Required behavior:

- Compare two commits.
- Show metadata changes from `index.json`.
- Show schematic/block changes at least as counts per sub-region.
- Ideally reuse Litematica verifier/overlay concepts to visualize changed blocks.

### Add Branch Awareness

Current state:

- The history list uses `git log --all`, so it can show commits from multiple refs and detached checkout history.
- The UI does not show the current branch, detached HEAD state, remote branch, or active commit.
- Checkout currently detaches HEAD when checking out a commit id.

Required behavior:

- Show current branch or detached HEAD state in the project page.
- Highlight the active commit.
- Provide a safe way to create a branch from a checked-out commit.
- Avoid confusing all-ref history with the active branch history.

Relevant files:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Improve Push/Pull UX

Current state:

- First push prompts for a remote URL.
- Push result statuses are collected but not displayed.
- Pull only reports `OK` or `FAILED`.
- SSH auth progress and failure details are not surfaced cleanly.

Required behavior:

- Display remote URL and current branch.
- Show per-ref push status.
- Show pull result details: fast-forward, merge, already up to date, conflict, failed.
- Rework GitHub account/auth connection for JGit into an explicit MVP flow instead of relying on raw remote URL prompts and opaque SSH failures.
- Add a connection/setup UI that can guide GitHub remote auth, validate credentials/keys, and test JGit push/pull before the user depends on it.
- Consider a remote settings button instead of only prompting on first push.

Relevant file:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Replace Current SSH Key Handling And Remove EdDSA Crypto Dependency

Current state:

- SSH push/pull uses JGit with Apache SSHD.
- Ed25519 keys may require the optional `net.i2p.crypto:eddsa:0.3.0` helper library.
- That library is flagged by scanners for CVE-2020-36843, so it should not remain part of the long-term shipped dependency set.

Required behavior:

- Prefer a supported MVP SSH path based on RSA keys that does not require the vulnerable EdDSA helper library.
- Document the MVP GitHub setup around RSA/OpenSSH-compatible keys.
- Replace the SSH implementation with a safer path before broader release: local-only SSH key path config, clear connection test, passphrase handling if needed, and no vulnerable crypto dependency.
- Remove `net.i2p.crypto:eddsa:0.3.0` from `build.gradle` once the replacement path is implemented and verified.

Relevant files:

- `build.gradle`
- `src/main/java/me/zly2006/rvc/RvcProjectService.java`

### Open Newly Created Project Directly With Tracking State

Current state:

- Creating an RVC project from the Save Schematic page creates the repo and then opens the project manager.
- The world already contains the selected build, but the RVC project page and tracking overlay are not opened immediately.

Required behavior:

- Decide whether create should open the project manager or the project page.
- If opening the project manager remains required, consider auto-selecting/highlighting the new project.
- If opening the project page, load the tracking overlay/verifier for the initial commit.

Relevant file:

- `src/main/java/fi/dy/masa/litematica/gui/GuiSchematicSaveBase.java`

## P1 - Data Model And Compatibility

### Remove Or Migrate Legacy `local_selection`

Current state:

- `local.json` can still contain `local_selection`.
- `readProjectAreaSelection()` falls back to `local_selection` when `index.json` sub-regions or `master_origin` are missing.

Risk:

- The fallback is useful for old local repos, but it keeps a second selection representation alive.

Required behavior:

- Add an explicit migration from `local_selection` to `index.json` sub-regions plus `master_origin`.
- After migration, keep fallback only for read-only recovery or remove it.

Relevant file:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`

### Add `index.json` Schema Validation And Migration

Current state:

- `index.json` has `rvc_version`, `name`, and `sub_regions`.
- Parsing is permissive and silently skips malformed sub-regions.
- There is no schema validation, migration path, or user-facing repair message.

Required behavior:

- Validate `rvc_version`.
- Validate sub-region names and coordinate arrays.
- Report invalid project metadata clearly in the GUI.
- Add migration hooks before bumping `rvc_version`.

Relevant file:

- `src/main/java/me/zly2006/rvc/RvcProjectService.java`

### Maintain Semantic Chunk Storage As The Canonical Large-Project Path

Current state:

- New projects use semantic `.rvcchunk` objects and `rvc.json`.
- Legacy repos may still commit `index.nbt` directly into Git.

Risk:

- Accidentally re-centering new work on `index.nbt` would lose the scalability benefits of semantic chunks.

Required behavior:

- Treat `rvc.json` plus content-addressed chunks as canonical for new MVP work.
- Keep `index.nbt` as legacy/compatibility or generated export/cache format.
- Do not add `history.json`.
- Do not adopt PRD directory suggestions unless they fit the Git-backed semantic model.

Relevant files:

- `src/main/java/me/zly2006/rvc/RvcSemanticRepository.java`
- `src/main/java/me/zly2006/rvc/RvcChunkStore.java`
- `docs/tech/rvc-semantic-storage.md`

## P2 - UI Polish

### Keep RVC UI Organized Like Litematica UI

Current state:

- `GuiRvcProjectManager` follows Litematica's `GuiListBase` + browser widget pattern.
- `WidgetRvcProjectBrowser` follows the Litematica browser pattern with RVC-specific repository filtering, selected-project summary, deletion refresh behavior, full-width rows, and conditional scrollbar rendering.
- `GuiRvcProject` is still monolithic and owns history drawing, action buttons, remote flows, scan, update areas, checkout, pull, and confirmation listeners.

Required behavior:

- Keep screen-level workflow in `GuiRvc*` classes.
- Move reusable list/browser rendering into `WidgetRvc*` classes.
- Extract commit history into `WidgetRvcCommitList` and `WidgetRvcCommitEntry` when real history/diff/inspect work starts.
- Avoid broad UI refactors until they directly support MVP workflows.
- Consider package split later, for example `me.zly2006.rvc.gui` and `me.zly2006.rvc.gui.widget`, once the UI surface grows past a few screens.

Priority note:

- This is not an MVP blocker.
- First good time to do it is while replacing the Inspect stub or adding real diff/history views.

Relevant files:

- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`
- `src/main/java/me/zly2006/rvc/GuiRvcProjectManager.java`
- `src/main/java/me/zly2006/rvc/WidgetRvcProjectBrowser.java`

### Replace Raw Text History With A Proper List Widget

Current state:

- `GuiRvcProject` manually draws compact history rows with search, selection, mouse-wheel scrolling, and a stable scrollbar gutter.
- History actions are currently handled by selected-row buttons outside the row, not row-level widgets.

Required behavior:

- Eventually extract history into a proper list widget when implementing real inspect/diff/history views.
- Preserve current behavior: stable row layout, selected-row metadata, scroll support, and no row width jump when the scrollbar appears.
- Add hover text for commit ids and actions.

Relevant file:

- `src/main/java/me/zly2006/rvc/GuiRvcProject.java`

### Add RVC Translations For All Supported Languages

Current state:

- RVC strings are added in `en_us.json` and `zh_cn.json`.
- Other language files do not have RVC-specific translations.

Required behavior:

- Add fallback-safe translations or ensure missing language keys degrade acceptably.
- At minimum, mirror English strings into other language files if this project expects complete key coverage.

Relevant directory:

- `src/main/resources/assets/litematica/lang/`

## P2 - Testing

### Add Real Game-State Integration Tests

Current state:

- Integration tests cover Git commits, metadata, checkout working tree behavior, and vanilla structure serialization.
- They do not verify real in-game block restoration because there is no dedicated fake/client world test harness yet.

Required behavior:

- Build a test harness that can assert world block changes without mocks.
- Test checkout restores tracked blocks.
- Test checkout does not touch untracked gaps between sub-regions.
- Test pull restores the world after a successful pull.
- Test tile entity and entity behavior once semantics are defined.

Relevant tests:

- `src/integrationTest/java/me/zly2006/rvc/RvcRepositoryIntegrationTest.java`

### Add GUI Interaction Tests

Current state:

- RVC GUI compiles and can be manually tested in-game.
- There are no automated GUI interaction tests.

Required behavior:

- Test create project button flow from Save Schematic page.
- Test Project Manager project list and Open button.
- Test Project page Commit, Pull, Push, Inspect, and Checkout flows.
- Test confirm dialogs once added.

## Non-RVC TODOs Observed During Review

These are not part of the current RVC task but appeared in the searched files:

- `GuiSchematicSave.java` has an existing `// TODO` around `SchematicSaveInfo`.
- `WidgetSchematicVerificationResult.java` has an existing `// FIXME`.

They should be tracked separately unless they block RVC workflows.
