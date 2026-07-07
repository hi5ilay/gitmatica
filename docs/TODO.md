# LVC TODO

Last reviewed: 2026-06-08.

This document lists the remaining "do it later" work found during a review of the current LVC implementation. It focuses on LVC code and the Litematica UI paths touched by LVC.

Important rule: if an operation changes the active LVC state, it must not stop at Git or filesystem changes. It must also update the visible in-game state: world blocks, ghost overlay, and verifier state where available.

## Semantic MVP Status

New LVC projects now use the semantic chunk storage direction described in `docs/tech/lvc-semantic-storage.md`.

Done:

- Semantic manifest/local state: `lvc.json` and local-only `local.json`.
- Compressed binary hash indexes: `indexes/*.lvcidx` stores full/tracked chunk hash refs outside `lvc.json`.
- Compressed content-addressed `.lvcchunk` objects under `objects/sha256/`.
- Semantic content commits candidate-prune current-tree `.lvcchunk` files whose old chunk refs are no longer referenced by the resulting indexes; historical commit objects remain preserved by Git.
- Deterministic block state storage.
- Deterministic block entity NBT storage with absolute `x/y/z` removed.
- Tracked hashes include canonical block entity NBT for tracked positions, so inventories contribute to dirty/no-op/diff state.
- Deterministic non-player entity NBT storage in full `.lvcchunk` snapshots with project-relative Litematica-style entity coordinates. Tracked hashes, scan dirty state, no-op commit detection, diffs, and MVP merge conflicts still ignore entity bytes.
- Fake-world and Minecraft `Level` capture readers.
- Singleplayer semantic init/commit capture uses integrated-server `ServerLevel` when available.
- Manual active-site semantic scan is now server-authoritative and three-way: it hashes server tracked chunks against the commit baseline for clean/dirty/unknown, scans the client copy for stale tracked chunks, then packet-syncs exact client-stale positions from the server before reporting.
- Semantic active-site `Update areas` reads the current Litematica selection, updates versioned `lvc.json` regions, recaptures content, and commits.
- Semantic project open/commit/update-area reconstructs the active working-tree state from full hash refs loaded from `.lvcidx` into a file-backed Litematica schematic cache under `.git/lvc-cache/` and adds a normal placement so Litematica can persist it across restarts. Initial project open reuses matching restart-persisted placements without restarting the verifier; Clear Area and Discard Changes reuse a descriptor-matching overlay and restart verifier, falling back to reload/rebuild when the overlay is missing or stale. If either operation shows reliability issues, revert them to always do the full reload/rebuild.
- Semantic project page can export the selected commit to the schematics folder as a deterministic `<ProjectName>-<shortCommitId>.litematic`, overwriting that same generated file on repeat export without checking out or mutating the world.
- Semantic selected-commit checkout runs a server-authoritative preflight scan, checks out the commit, refreshes tracked blocks/block entities/entities into the integrated-server world, and refreshes the overlay.
- Semantic branch checkout from the project page branch dropdown is implemented. Same-tip branch switches attach to the selected branch without restore and preserve uncommitted changes; different-tip switches use the same server-authoritative checkout preflight plus convergent restore path as selected-commit checkout and confirm before overwriting dirty Git/world state.
- Semantic Clear Area runs a server-authoritative preflight scan, clears immediately when clean, requires confirmation when dirty, uses the convergent restore engine with an air target, removes live non-player entities inside tracked sub-region bounds only when tracked block rewrites are needed, and refreshes verifier against the existing overlay when valid.
- Semantic Discard Changes requires confirmation, scans target chunks chunk-by-chunk, removes live non-player entities inside tracked sub-region bounds only when block/block-entity rewrites are needed, repeats that cleanup at later rewrite/final-restore/failure boundaries, rewrites mismatched block states and block entities grouped by real Minecraft chunk column, verifies the full tracked site, rewrites still-dirty LVC subchunks grouped by real Minecraft chunk column for up to three passes, fully refreshes stored non-player entities after clean verification, schedules a delayed authoritative server block update for tracked positions to clear client/verifier desync, resets Git files when needed, and refreshes verifier against the existing overlay when valid.
- Branch UI/actions: the project page top branch control is a searchable dropdown with branch/check icons, ellipsized long names, conditional scrolling, and right-click branch action popup. Create Branch validates names, creates/checks out from current HEAD, stores branch-start metadata for focused history, and avoids extra dirty-state messaging. Delete Branch opens a searchable dropdown of non-current branches and deletes the selected branch. Rename Branch opens a searchable branch dropdown plus new-name input, validates the target name, renames current or non-current local branches, and preserves branch-start metadata. Merge Branch opens a searchable dropdown of non-current branches, blocks detached HEAD and dirty Git/world state, performs fast-forward or semantic three-way merge, creates real two-parent Git merge commits for diverged branches, shows an MVP all-conflicts choice popup for Base/Incoming/Yours/Cancel when conflicts are detected, restores the merged active state, and refreshes overlay/verifier.
- Interrupted-operation recovery is user-controlled for world-mutating operations. Pending checkout/discard/clear/merge journals prompt on the next attempted operation with Restart, Abort, or Cancel. Checkout journals record the previous HEAD/branch before Git moves, so Abort rolls back through a restore/overlay refresh instead of merely clearing recovery data. Merge journals record the previous HEAD before Git moves, so Abort rolls the target branch back and restores the world/overlay to that previous version. Non-world interrupted operations are silently cleaned on world join and report a concise cancellation dialog. Operation journals are checksummed, atomically written with backup generations, corrupt journals are quarantined, merge journals cover the Git-mutation gap, and `.git/lvc-refresh-needed.json` forces overlay/verifier refresh after crashes that happen after world mutation but before visible-state refresh.
- Semantic repo init and commit through JGit.
- Project listing supports semantic `lvc.json` repos only.
- Project browser delete is implemented with confirmation and validated recursive deletion under `run/lvc-projects`.
- Project browser manual Create Project flow creates an empty semantic repo with `lvc.json`, ignored `local.json`, `.git`, and no initial commit. After the name popup closes, the user remains in Project Browser and can open Project Editor manually.
- Project/project-manager UI polish: project browser navigation rooted at `lvc-projects`, conditional scrollbar rendering, searchable/scrollable commit history, and selected commit metadata with title/author/date/version/changes.
- Project Placement page opens from the project page for semantic repos, shows read-only project/version/tracked-box metadata, and edits only the clone-local world origin in ignored `local.json`.
- Integration coverage for semantic storage, object reuse, fake-world capture, canonical Minecraft state encoding, and semantic commits.

Not done:

- Full failpoint-based crash testing is not implemented yet. Future coverage should add inert main-code failpoint hooks at every operation phase boundary, enabled only by an explicit test flag, then run child-process integration tests that hard-exit at each failpoint and verify relog/open, Restart, Abort, cleanup, and visible-state refresh behavior.
- Semantic selected-commit checkout, branch checkout, discard, and clear now use a no-freeze convergent scan/rewrite/verify/client-sync path. Semantic pull restore still needs the same hardening. Dedicated-server restore support, scheduled tick capture/restore, entity identity-aware diffing, and Litematica paste-parity testing remain unresolved before treating restore paths as production-correct.
- Bugfix/polish pass for recent semantic MVP batch before starting new feature work: tracking overlay persistence/dedup, selected-commit export, selected-commit checkout restore, branch checkout restore, Clear Area, scan/preflight messaging, and project page button flows.
- Semantic pull restore.
- MVP mod-level config page for GitMatica/LVC settings: logging controls, overlay/change colors, and hotkeys for opening main LVC screens.
- World association UX: projects remain portable, but `local.json` should eventually track current-world identity/hints and warn before using a repo in a different world.
- Optional import workflow for existing `.litematic` files into semantic LVC repos, preserving sub-region definitions so users do not need to paste, reselect, and recreate sub-regions manually.
- Rich update-area preview and explicit origin-change controls.
- Multi-site Project Editor UX; the MVP editor intentionally exposes only the active `main` site even though the manifest supports sites internally.
- Dedicated-server multiplayer support.
- Later MVP world-I/O optimization: add a reusable execution planner that keeps semantic storage in project-relative `LvcChunkCoordinate` chunks, but maps tracked LVC positions/subchunks to translated real-world `ChunkPos` columns and chunk sections so capture/restore/scan execution, unloaded chunk handling, neighbor chunk requirements, and world access locality match Minecraft/Litematica behavior more closely. Reference Litematica's chunk planning/paste flow while designing this.
- Storage compression experiment: on a separate test branch, remove compression from stored `.lvcidx`/`.lvcchunk` files and measure whether Git stores project changes more efficiently through its own delta/object compression.
- Scheduled tick capture. Discard/checkout/clear no longer freeze or suppress scheduled work; they rely on bounded convergence and Litematica paste-style update suppression around block writes. Exact committed scheduled tick capture/restore is still not implemented.

### Import Existing `.litematic` Files

Current state:

- LVC creates semantic repos from world selections or empty browser-created projects.
- Export from semantic commits to `.litematic` is implemented from the project page.
- Importing an existing `.litematic` directly into LVC is not implemented.

Required behavior:

- Convert an existing `.litematic` into a semantic LVC repo.
- Preserve the `.litematic` sub-region names and bounds in `lvc.json`.
- Store block/block-entity content as semantic chunks.
- Treat overlapping sub-regions as valid tracking masks. LVC content should use union semantics, so each project coordinate is stored once even if covered by multiple sub-regions.
- If an imported file somehow contains conflicting contents for the same project coordinate across overlapping sub-regions, reject the import with a clear error instead of guessing.

Reason:

- Lets users version existing schematic files without pasting them into a world, reselecting the build, and recreating all sub-regions manually.

Use `docs/agent/lvc-mvp-slices.md` as the current thin-slice plan.

## P0 - Correctness And Safety

### Crash/Fault Injection Coverage

Current state:

- Recovery primitives are covered by integration tests for journal primary/backup/temp behavior, checksum corruption, corrupt-journal quarantine, staging cleanup, refresh marker lifecycle, and merge restore journaling.
- The current tests do not yet hard-crash a running JVM at every task phase boundary.

Required behavior:

- Add a small `LvcCrashFailpoints.hit("operation.phase")` helper in main code that is a no-op unless an explicit JVM property/env flag enables crash testing.
- Keep failpoint hooks in mainline code at real phase boundaries so tests exercise the same code users run. Do not keep crash-only behavior on a long-lived separate branch.
- Use a child-process integration harness that launches a JVM with one failpoint enabled, lets it halt hard, reopens the repo/project in the parent, and verifies both Restart and Abort behavior where applicable.
- Cover save/init/update, checkout, branch checkout, discard, clear, merge, overlay refresh marker handling, corrupt recovery data, and non-world operation cleanup.
- Never expose crash failpoints in normal UI/config, and make accidental activation impossible without explicit test flags.

Relevant files:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectController.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectTaskActions.java`
- `src/main/java/me/zly2006/lvc/task/LvcOperationJournal.java`
- `src/main/java/me/zly2006/lvc/task/LvcRefreshMarker.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticCheckoutTask.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticDiscardTask.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticClearTask.java`
- `src/main/java/me/zly2006/lvc/git/LvcBranchMergeOps.java`
- `src/main/java/me/zly2006/lvc/git/LvcSemanticMergeEngine.java`
- `src/main/java/me/zly2006/lvc/git/LvcMergeObjectResolver.java`
- `src/integrationTest/java/me/zly2006/lvc/LvcOperationRecoveryIntegrationTest.java`

### Restore/Checkout Must Match Litematica Paste Semantics

Current concern:

- Complex Litematica builds ingested into GitMatica can restore with mismatched states after Clear Area plus Checkout.
- Observed symptoms include missing shulker boxes and pistons not extended when they should be.
- Pull restore can still trigger redstone contraptions mid-restore because it has not moved to the discard/selected-checkout/branch-checkout convergent restore path yet.
- The likely root area is world write semantics: LVC currently restores chunk-by-chunk with direct block/entity writes, which may differ from Litematica's paste ordering, block update suppression, block entity placement, neighbor update handling, or server-side execution model.

Required behavior:

- Analyze Litematica's paste/placement implementation before changing LVC restore semantics.
- Preserve exact block states and block entity NBT for storage blocks and stateful redstone components.
- Avoid unnecessary neighbor updates/redstone ticks mid-restore when the intended operation is restoring a static committed schematic state. Use the discard/selected-checkout/branch-checkout convergent scan/rewrite/verify path as the current reference for future pull restore work.
- Consider a two-pass or multi-pass restore like Litematica if needed: place safe base states first, apply block entities, then apply sensitive/redstone/stateful blocks in a stable order.
- Re-run verifier after restore and treat mismatches as a restore correctness failure, not only a user dirty-state signal.

Relevant files:

- `src/main/java/me/zly2006/lvc/semantic/LvcSemanticWorldApplier.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticRestoreEngine.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticCheckoutTask.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticDiscardTask.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticClearTask.java`
- `src/main/java/fi/dy/masa/litematica/scheduler/tasks/TaskPasteSchematicPerChunkBase.java`
- `src/main/java/fi/dy/masa/litematica/scheduler/tasks/TaskPasteSchematicPerChunkDirect.java`
- `src/main/java/fi/dy/masa/litematica/util/WorldUtils.java`

### Semantic Commit UX Polish

Current state:

- Semantic no-op commits return `null` at service level.
- `GuiLvcProjectManager` currently reports generic commit success and does not clearly distinguish real commit vs no-op.
- Semantic commits also cannot reload overlay yet.

Required behavior:

- Show `nothing to commit` for semantic no-op commits.
- Show successful commit only when a new Git commit is created.
- Keep semantic post-commit overlay reload behavior stable.
- Do not surface semantic overlay/export absence as a failed commit.

Relevant files:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`
- `src/main/java/me/zly2006/lvc/LvcProjectService.java`

### Extend Manual `Scan Changes` Into Preflight

Current state:

- The semantic project page has a `Scan changes` button.
- It hashes the active site's currently tracked semantic chunks without writing objects or changing `lvc.json`.
- It compares current hashes to manifest chunk refs.
- It reports clean, dirty, and unknown states.
- In singleplayer it uses the same integrated-server `ServerLevel` path as semantic init/commit.
- Existing verifier path remains overlay-based and is not the long-term semantic dirty model.

Required behavior:

- Reuse this scan/preflight model for future commit, pull, and reset flows. Selected-commit checkout, branch checkout, Clear Area, and branch merge already have dedicated server-authoritative preflight paths.
- Add stale-state invalidation after world edits or time passing, so old clean scans are not treated as current.
- Extend unknown handling to dedicated-server multiplayer through a server-side LVC path.
- Keep unavailable authoritative chunks as unknown, not clean.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcCaptureEngine.java`
- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Implement Semantic Export And Restore

Current state:

- Semantic repos can init and commit.
- Semantic project open/commit can reconstruct the active working-tree state from full hash refs loaded from `.lvcidx` into a file-backed Litematica schematic cache under `.git/lvc-cache/` and add a normal placement.
- Semantic commit export reconstructs selected commit content from Git tree `lvc.json`, `.lvcidx`, and `.lvcchunk` objects and writes a `.litematic` to the schematics folder.
- Semantic selected-commit checkout restores tracked block, block entity, and stored non-player entity content in singleplayer/integrated-server worlds.
- Semantic branch checkout restore from the project page branch dropdown restores tracked block, block entity, and stored non-player entity content in singleplayer/integrated-server worlds.
- Semantic pull restore is still blocked.

Required behavior:

- Reuse commit-selected Litematica/vanilla-structure views from Git tree `lvc.json`, `.lvcidx` full hash refs, and `.lvcchunk` objects for remaining restore/inspect flows.
- Preserve untracked gaps as `minecraft:structure_void` or equivalent non-overwrite behavior.
- Restore checked-out semantic state into tracked positions only.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcSemanticRepository.java`
- `src/main/java/me/zly2006/lvc/LvcChunkCodec.java`
- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Implement `Update areas`

Current state:

- The LVC project page has an `Update areas` button.
- For semantic repos, it reads the current Litematica area selection.
- It updates versioned `lvc.json` region definitions for the active site.
- It preserves `local.json` origin.
- It recaptures the active site content and commits the updated regions/chunks.
- It shows a basic confirmation with region count.

Required behavior:

- Add a richer preview of changed sub-regions, bounds, added/removed tracked chunks, and region renames.
- Update local-only `local.json` origin only if the user explicitly requests it.
- Refresh the in-game overlay/verifier after the update.

Relevant files:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`
- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/resources/assets/litematica/lang/en_us.json`
- `src/main/resources/assets/litematica/lang/zh_cn.json`

### Add Preview And Confirmation For World-Changing Operations

Current state:

- Selected-commit checkout and different-tip branch checkout update the Git working tree and restore blocks into the current world through server-authoritative preflight/confirmation paths.
- Pull restore is blocked until pull-specific dirty/conflict handling is hardened.
- Checkout confirmations warn about overwritten uncommitted changes, but they do not yet show rich affected-region previews.

Required behavior:

- Show which commit/version will be applied.
- Show affected sub-region count and bounds.
- Warn that current world blocks inside tracked sub-regions will be overwritten.
- Require explicit confirmation before writing blocks.
- Keep the rule that untracked space between sub-regions must not be modified.

Relevant files:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`
- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/semantic/LvcSemanticWorldApplier.java`

### Handle Git Dirty State, Merge Conflicts, And Failed Pulls

Current state:

- `checkoutCommitToWorkingTree()` delegates to JGit checkout.
- `pull()` delegates to JGit pull and returns `OK` or `FAILED`.
- The GUI does not distinguish dirty working tree, merge conflict, auth failure, detached HEAD surprises, or non-fast-forward cases.
- `Pull` attempts game restoration after JGit reports success, but conflict states need explicit guarding and messaging.

Required behavior:

- Check `git.status()` before checkout and pull.
- Block destructive operations when the working tree has uncommitted LVC changes unless the user explicitly chooses a recovery path.
- Detect merge conflicts and refuse to restore into the world while the repository is conflicted.
- Display actionable error messages for dirty tree, conflict, no remote, auth failure, and non-fast-forward cases.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Make In-Game Restore Server-Authoritative

Current state:

- Restore uses `Level#setBlock` on the current client world.
- This is enough for local/integrated testing paths but may not be server-authoritative on multiplayer servers.
- There is no permission check, command mode, or server-side apply path.
- Semantic init/commit capture now resolves integrated-server `ServerLevel` in singleplayer when available.
- Client-only multiplayer semantic capture still falls back to the client `Level`, which is not authoritative.
- Research note: Servux is likely the best model/path for dedicated-server support. It is a server-side Fabric mod for masa client mods, server-only on Modrinth, and 0.3.x added Litematica server-side saving/pasting with full tile entity data. See https://modrinth.com/mod/servux and https://github.com/maruohon/servux.

Required behavior:

- Decide the supported restore modes: single-player direct world write, integrated-server task, multiplayer command placement, or server-side LVC support.
- Prefer investigating a Servux-backed or Servux-compatible server protocol before inventing a separate server mod path.
- Refuse checkout/pull restore when the current world cannot be modified authoritatively.
- Report a clear message instead of silently creating client-only visual changes.
- For dedicated servers, require server-side LVC support for reliable scan/commit/restore.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Entity Restore Needs Cleanup Semantics

Current state:

- Semantic full snapshots store non-player entity NBT per `.lvcchunk` with project-relative `Pos` and attachment coordinates.
- Schematic export/overlay includes stored entities and assigns each entity to one containing sub-region.
- Checkout/discard/delete-version restore scans first; when block/block-entity rewrites are needed, it removes live non-player entities inside tracked sub-region bounds, restores blocks, then spawns the target commit's stored entities after clean verification. Clear Area removes live non-player entities inside tracked sub-region bounds only when tracked block rewrites are needed.
- Entity-only changes are hidden for v1: they do not make Scan Changes dirty and do not create no-op-breaking commits.

Risk:

- Entity identity-aware diffs and conflicts do not exist yet. If both merge sides changed entity payload in the same chunk, MVP merge keeps yours/current and logs a breadcrumb.
- Restore does not freeze entity ticking; the convergent loop clears live non-player entities only when block/block-entity rewrites are needed, repeats cleanup at later restore/failure boundaries, and respawns hidden entity snapshots after block/block-entity verification succeeds. Clean initial scans skip entity refresh so entity-only drift remains hidden MVP behavior.

Required behavior:

- Add first-class entity diffs/conflicts later, likely through the Diff Viewer Entities section.
- Add a final entity verify pass if real builds show movement during restore still matters.
- Extend pull restore to the same hardened path before enabling pull world restore.

Relevant files:

- `src/main/java/me/zly2006/lvc/util/LvcEntityNbt.java`
- `src/main/java/me/zly2006/lvc/semantic/LvcSemanticWorldApplier.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticRestoreEngine.java`
- `src/main/java/me/zly2006/lvc/git/LvcSemanticMergeEngine.java`

## P1 - User Workflows

### Upgrade `Inspect` From A Message To A Real View

Current state:

- History rows have an `Inspect` button.
- It only displays the short commit id and message in the GUI message area.

Required behavior:

- Show commit id, parent ids, author, time, message, and LVC metadata.
- Show changed semantic files, affected chunk refs, and metadata changes from `lvc.json`.
- Show sub-region metadata at that commit.
- Provide entry points for diff and checkout preview.

Design notes:

- Avoid implementing Inspect by mutating client-only world blocks to the parent commit. It is tempting because Litematica's verifier could compare against the target schematic, but it intentionally desyncs `ClientLevel` from the authoritative server and creates unsafe/read-confusing behavior.
- Client-only block mutation risks include chunk refresh/server packets snapping the view back, fake collision/raycast visuals, real server interactions while the user sees fake parent blocks, misleading verifier/client state, cleanup needs after crash/relog, and accidental edits to real server blocks while viewing fake inspect state.
- Preferred inspect visualization: hide or ghost the real world inside the tracked project bounds, then render parent/target/diff overlays at the real project position without changing client or server block state.
- Alternative option: render side-by-side or offset parent/target previews so current physical blocks cannot visually overlap either commit.
- Alternative option: render changed blocks only, with added blocks as green target overlays, removed blocks as red parent ghosts/wireframes, and changed blocks as orange target overlays with optional parent outlines.
- Alternative option: add an x-ray inspect mode that clips or masks real-world rendering inside tracked bounds via render hooks, then draws the commit preview/diff on top.
- Inspect mode should be read-only. Do not send block sync/update packets to the server, and consider blocking or exiting before attack/use/place interactions inside inspected bounds.

Relevant file:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Add Diff Workflow

Current state:

- No diff button or diff view exists.
- The PRD mentions history inspection workflows, but no schematic diff is implemented.

Required behavior:

- Compare two commits.
- Show metadata changes from `lvc.json`.
- Show schematic/block changes at least as counts per sub-region.
- Ideally reuse Litematica verifier/overlay concepts to visualize changed blocks.

Future semantic diff clustering design:

- Build a shared diff pipeline that can feed uncommitted diffs, commit inspection, and merge conflict editing: `raw semantic position diffs -> change atoms -> change clusters -> UI rows/overlays`.
- Keep this as a GitMatica semantic diff model, not a Litematica verifier-row model. Litematica verifier-style rows/overlays may consume the clusters, but the source of truth should be reusable outside the verifier screen.
- A `ChangeAtom` should represent one tracked-position change with project/world position, sub-region, change kind, before/after block state, before/after block pair, block family, optional block-entity/inventory summary, and optional merge payload.
- Uncommitted diffs should compare committed `HEAD` content against the authoritative current world. Commit inspect diffs should compare parent/selected commits or arbitrary commit pairs. Merge conflict editing should use the same clustering layer with base/yours/theirs atoms instead of forcing conflicts into a simple before/after shape.
- Use a Minecraft-grid-native clustering algorithm instead of plain connected components or generic DBSCAN/HDBSCAN as the default. Connected components alone spam clusters in checkerboard or patterned edits; generic density clustering is harder to tune and explain for block-grid UI.
- Suggested algorithm:
  - Start with small spatial islands from 6/18/26-neighbor connected components, chosen per mode.
  - Apply small-gap/radius dilation so islands separated by 1-3 block gaps can merge; this turns checkerboards and repeated pattern edits into one understandable cluster.
  - Build a graph where each island is a node and nearby-ish islands get weighted edges.
  - Edge strength should combine spatial closeness, same change kind, same before/after block pair, same block family, same sub-region, cluster density, bounding-box growth penalty, and mixed-change penalty.
  - Merge strongest compatible island edges until no edge passes the threshold.
  - Post-process tiny leftovers by merging them into the nearest compatible cluster, while splitting absurdly large/low-density clusters if they have multiple dense centers.
- Label final clusters by the dominant change type/block pair or semantic summary, for example `Added oak planks`, `Changed stone to deepslate`, `Changed chest inventories`, `Conflict: storage wall edits`, or `Mixed changes near storage room`.
- Merge conflict UI should reuse the same spatial clustering, but conflict atoms need explicit base/yours/theirs metadata so rows can show conflict choices without losing the three-way meaning.

Future backend storage profiling design:

- Add an integration-test-only `LvcStorageProfileRunner` plus Gradle `storageProfile` task.
- Generate deterministic randomized block, block-state, and inventory mutations across configurable subchunk distributions.
- Create real loadable LVC repos under `run/lvc-projects/<projectName>` using existing semantic commit APIs.
- Support JSON config plus CLI overrides for seed, commit count, output root, project name, clean mode, palettes, distributions, and reporting.
- Record commit timings, repo/object/index growth, changed semantic chunks, inventory mutation counts, and hash evolution to JSON/CSV.
- Keep it backend-only: no live world mutation, no packaged mod feature, no JMH for v1.
- Add integration coverage for repo validity, deterministic same-seed output, inventory hash tracking, and config validation.

### Branch Awareness And Remaining Branch Work

Current state:

- Commit history is branch-focused. `main` shows normal branch history. GitMatica-created branches show commits back to the stored branch-start commit inclusive; older/external branches fall back to merge-base with `main`.
- The project page shows the current branch or detached HEAD short commit in the title and top branch dropdown.
- The top branch dropdown is searchable, scrollable, right-aligned to the metadata panel, uses branch/check icons, ellipsizes long branch names, and marks the attached HEAD branch.
- Selecting a different branch switches branches. Same-tip switches preserve uncommitted changes without restore; different-tip switches run semantic checkout preflight and confirmation before overwriting dirty state.
- Right-clicking the branch dropdown opens branch actions. Create Branch, Delete Branch, Rename Branch, and Merge Branch are implemented.
- Selected-commit checkout still intentionally detaches HEAD unless the selected commit is the remembered branch tip, where it reattaches silently.

Required behavior:

- Highlight the active commit.
- Add richer visual branch merge previews and per-conflict editor UI. Current merge has an MVP all-conflicts Base/Incoming/Yours/Cancel choice popup.
- Add richer branch switch previews/counts before destructive restores.
- Surface remote branch/upstream state when remote UX is expanded.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

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

- `src/main/java/me/zly2006/lvc/LvcProjectService.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Replace Current SSH Key Handling And Remove EdDSA Crypto Dependency

Current state:

- SSH push/pull uses JGit with Apache SSHD.
- Remote Git commands are isolated in `LvcGitRemoteOps`; SSH transport setup and default key discovery are isolated in `LvcSshTransportFactory`.
- The direct `net.i2p.crypto:eddsa:0.3.0` shadow dependency has been removed from `build.gradle`; JGit's SSH Apache artifact now owns any transitive crypto needs.

Required behavior:

- Prefer and document a supported MVP SSH path based on key formats supported by the shaded JGit SSH Apache stack.
- Document the MVP GitHub setup around RSA/OpenSSH-compatible keys.
- Replace the SSH setup UX before broader release: local-only SSH key path config, clear connection test, and passphrase handling if needed.

Relevant files:

- `build.gradle`
- `src/main/java/me/zly2006/lvc/git/LvcGitRemoteOps.java`
- `src/main/java/me/zly2006/lvc/git/LvcSshTransportFactory.java`

### Open Newly Created Project Directly With Tracking State

Current state:

- Creating an LVC project from the Save Schematic page creates the repo and then opens the project manager.
- The world already contains the selected build, but the LVC project page and tracking overlay are not opened immediately.

Required behavior:

- Decide whether create should open the project manager or the project page.
- If opening the project manager remains required, consider auto-selecting/highlighting the new project.
- If opening the project page, load the tracking overlay/verifier for the initial commit.

Relevant file:

- `src/main/java/fi/dy/masa/litematica/gui/GuiSchematicSaveBase.java`

## P1 - Data Model

### Maintain Semantic Chunk Storage As The Canonical Large-Project Path

Current state:

- New projects use compressed semantic `.lvcchunk` objects and `lvc.json`.
- There is no released legacy repo format; do not preserve or reintroduce `index.nbt` compatibility paths.

Risk:

- Accidentally re-centering new work on a monolithic binary structure file would lose the scalability benefits of semantic chunks.

Required behavior:

- Treat `lvc.json`, `.lvcidx`, and compressed content-addressed chunks as canonical for new MVP work.
- Do not add `history.json`.
- Do not adopt PRD directory suggestions unless they fit the Git-backed semantic model.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcSemanticRepository.java`
- `src/main/java/me/zly2006/lvc/LvcChunkStore.java`
- `docs/tech/lvc-semantic-storage.md`

### Add Real-Chunk World I/O Planning

Current state:

- Semantic storage chunks are project-relative `16x16x16` units keyed by `LvcChunkCoordinate`.
- Capture, scan, clear, and restore currently iterate semantic chunks and translate each tracked position to world coordinates.
- If the local world origin is not aligned to 16 on X/Z, one semantic chunk can span multiple Minecraft chunk columns.

Required behavior:

- Keep project-relative semantic chunks as the repository/storage format.
- Add a reusable world-operation execution planner that maps project-relative LVC chunks, tracked positions, and semantic subchunks to real Minecraft `ChunkPos` columns and chunk sections before reading or mutating the world.
- Use that planner from capture, scan/preflight, restore, clear, client sync, and future server/Servux restore paths instead of adding operation-local mapping/grouping logic.
- Reference Litematica's `TaskProcessChunkBase`, `TaskPasteSchematicPerChunkDirect`, `SchematicPlacingUtils.placeToWorldWithinChunk`, and `PositionUtils.getTouchedChunksForBoxes` style of real-chunk planning/paste batching before implementing.
- Wait on unavailable real chunks instead of treating a partly unavailable semantic chunk as an immediate hard failure where practical.
- Use Litematica-style neighbor chunk gating for restore/paste-like operations when block updates, block entities, lighting, or redstone can depend on adjacent chunks.
- Preserve the invariant that false mask bits are untracked and must not be read, restored, cleared, or overwritten.

Priority note:

- This is a later MVP optimization/robustness item, not a storage redesign.

Relevant files:

- `src/main/java/me/zly2006/lvc/capture/LvcCapturePlanner.java`
- `src/main/java/me/zly2006/lvc/capture/LvcCaptureSession.java`
- `src/main/java/me/zly2006/lvc/semantic/LvcSemanticWorldApplier.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticCaptureTask.java`
- `src/main/java/me/zly2006/lvc/task/LvcSemanticRestoreEngine.java`

## P2 - UI Polish

### Keep LVC UI Organized Like Litematica UI

Current state:

- `GuiLvcProjectBrowser` follows Litematica's `GuiListBase` + browser widget pattern.
- `WidgetLvcProjectBrowser` follows the Litematica browser pattern with LVC-specific repository filtering, selected-project summary, deletion refresh behavior, full-width rows, and conditional scrollbar rendering.
- `GuiLvcProjectManager` is still monolithic and owns history drawing, action buttons, remote flows, and some confirmation/listener wiring. Project task actions are split into workflow classes, but the main screen still needs widget decomposition.

Required behavior:

- Keep screen-level workflow in `GuiLvc*` classes.
- Move reusable list/browser rendering into `WidgetLvc*` classes.
- Extract commit history into `WidgetLvcCommitList` and `WidgetLvcCommitEntry` when real history/diff/inspect work starts.
- Avoid broad UI refactors until they directly support MVP workflows.
- Consider package split later, for example `me.zly2006.lvc.gui` and `me.zly2006.lvc.gui.widget`, once the UI surface grows past a few screens.

Priority note:

- This is not an MVP blocker.
- First good time to do it is while replacing the Inspect stub or adding real diff/history views.

Relevant files:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectBrowser.java`
- `src/main/java/me/zly2006/lvc/gui/widgets/WidgetLvcProjectBrowser.java`

### Add Mod-Level GitMatica Config Page

Current state:

- LVC diagnostics are controlled by JVM/env flags, not by an in-game GitMatica settings page.
- LVC UI colors and screen-opening hotkeys are scattered across existing Litematica/malilib config surfaces or hardcoded defaults.

Required behavior:

- Add a GitMatica/LVC config page reachable from the mod/Litematica config flow.
- Include user-facing logging controls for normal/debug LVC diagnostics without requiring JVM flags.
- Add configurable colors for added, removed, changed, and wrong-state block overlays/diffs.
- Add hotkeys for opening core LVC screens such as Project Browser/Project Manager and future common workflows.
- Keep the page mod-level, not project-level; project-specific metadata must stay in `lvc.json`/`local.json`.

Relevant files:

- `src/main/java/me/zly2006/lvc/LvcDiagnostics.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectBrowser.java`
- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`
- `src/main/java/fi/dy/masa/litematica/config/Configs.java`

### Replace Raw Text History With A Proper List Widget

Current state:

- `GuiLvcProjectManager` manually draws compact history rows with search, selection, mouse-wheel scrolling, and a stable scrollbar gutter.
- History actions are currently handled by selected-row buttons outside the row, not row-level widgets.

Required behavior:

- Eventually extract history into a proper list widget when implementing real inspect/diff/history views.
- Preserve current behavior: stable row layout, selected-row metadata, scroll support, and no row width jump when the scrollbar appears.
- Add hover text for commit ids and actions.

Relevant file:

- `src/main/java/me/zly2006/lvc/gui/GuiLvcProjectManager.java`

### Add LVC Translations For All Supported Languages

Current state:

- LVC strings are added in `en_us.json` and `zh_cn.json`.
- Other language files do not have LVC-specific translations.

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
- Test branch checkout restores tracked blocks and preserves dirty state on same-tip branch switches.
- Test pull restores the world after a successful pull once pull restore is implemented.
- Expand real-world tile entity/entity restore tests beyond current storage/capture coverage.

Relevant tests:

- `src/integrationTest/java/me/zly2006/lvc/LvcRepositoryIntegrationTest.java`

### Add GUI Interaction Tests

Current state:

- LVC GUI compiles and can be manually tested in-game.
- There are no automated GUI interaction tests.

Required behavior:

- Test create project button flow from Save Schematic page.
- Test Project Manager project list and Open button.
- Test Project page Commit, Pull, Push, Inspect, and Checkout flows.
- Test confirm dialogs once added.

## Non-LVC TODOs Observed During Review

These are not part of the current LVC task but appeared in the searched files:

- `GuiSchematicSave.java` has an existing `// TODO` around `SchematicSaveInfo`.
- `WidgetSchematicVerificationResult.java` has an existing `// FIXME`.

They should be tracked separately unless they block LVC workflows.
