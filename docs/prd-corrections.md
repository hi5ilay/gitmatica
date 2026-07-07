# LVC PRD Corrections

This document records corrections to the external "Schematic Version Control" PRD and clarifies which parts should guide the current LVC implementation.

The implementation authority is:

1. The instructions in this document.
2. `docs/tech/lvc.md`.
3. Existing implementation constraints in the Litematica codebase.

Do not treat the external PRD as authoritative when it conflicts with these corrections.

2026-06-05 implementation update: the mod has not been released, so there is no legacy repository compatibility contract. The supported repository format is semantic `lvc.json`, compressed binary `indexes/*.lvcidx`, compressed content-addressed `.lvcchunk` objects, and ignored clone-local `local.json`. Older notes in this file that mention `index.json`, `index.nbt`, `local_selection`, or `master_origin` are historical context only and must not be used to justify keeping or reintroducing legacy repo code.

## Gameplay State Rule

LVC operations that change the current project state must not stop at Git or filesystem updates.

When an operation changes which committed schematic state is active, the implementation must also update the in-game state so the player can see the result immediately. This applies to checkout and pull, and to any future operation that changes the active project version.

Required behavior:

- Update the Git repository or working tree as needed.
- Restore the checked-out schematic state into the current Minecraft world for the tracked sub-regions.
- Refresh the Litematica ghost overlay to the same state.
- Run or refresh verifier state when the client and schematic worlds are available.
- Use the active site's local world origin from ignored `local.json`, not an arbitrary repository path or guessed origin.
- Never modify untracked space between independent sub-regions. Semantic tracked masks define exactly which project-relative positions may be captured/restored.

This rule is intentionally stronger than ordinary Git semantics because LVC is an in-game version control workflow. If the repository changes but the player cannot see the corresponding world/overlay state, the feature is incomplete.

## Corrections

### 1. `history.json` Is a Wrong Design

The PRD's proposed `history.json` metadata log is incorrect for LVC.

LVC is intentionally Git-backed. Commit history, commit IDs, parent relationships, author, timestamp, branch pointers, and messages should come from Git itself. Duplicating this into `history.json` would create two competing sources of truth and introduce consistency bugs.

Required direction:

- Use Git commits as the permanent history log.
- Use JGit to read history for UI display.
- Store LVC-specific commit metadata in Git commit objects where needed.
- Do not add a separate `history.json` history ledger.

### 2. Ignore PRD Directory Structure Advice

Do not follow any PRD recommendation about repository directory layout unless it is explicitly re-approved in LVC docs or implementation notes.

The external PRD proposes files such as `history.json` and a `/data/` directory. That structure is not authoritative for this project.

Current LVC repository structure is intentionally simple and Git-native:

- `index.json`: versioned LVC project metadata.
- `index.nbt`: versioned vanilla structure content saved with Minecraft `StructureTemplate`.
- `.gitignore`: Git ignore rules for local-only files.
- `local.json`: local-only state, ignored by Git.

Future structure changes should be designed from the Git-backed model, not copied from the PRD.

### 3. `local.json`, `index.json`, Sub-Regions, and Master Origin

`local.json` must never be synchronized. It is local clone state only and must remain ignored by Git.

Reason: different clones of the same LVC repository may be used in different Minecraft worlds or at different physical positions. The same schematic project may therefore need a different Master Origin per clone.

Responsibilities:

- `index.json` records versioned project metadata that must be shared between clones.
- `index.json` must record all sub-region definitions using coordinates relative to the project coordinate system.
- `local.json` records local-only workspace information.
- `local.json` stores the Master Origin for this clone.
- `local.json` may store local UI/workspace state that should not affect collaborators.
- `local.json` must be listed in `.gitignore`.

Important consequence:

Sub-region layout is shared project data and belongs in `index.json`, not `local.json`. Master Origin is per-clone local data and belongs in `local.json`, not `index.json`.

The current implementation stores `local_selection` in `local.json`. This was added to prevent commits from depending on the currently selected in-game area. That is useful as a short-term safety measure, but the final design should move shared sub-region layout into `index.json` while keeping clone-specific Master Origin in `local.json`.

### 4. Current Sub-Region Serialization Needs Fixing

Status: fixed in the current implementation.

The PRD correctly identifies that tracked areas must be explicit sub-regions, but the current implementation does not yet serialize them in the final desired model.

Current behavior:

- The project captures the current Litematica `AreaSelection`.
- Commit code reads a saved local selection first.
- It falls back to the current in-game selection only when local selection data is unavailable.
- All valid boxes are projected into one enclosing vanilla structure before saving `index.nbt`.

Problems:

- Sub-region metadata is not stored in versioned `index.json`.
- Per-sub-region identity and relative positions are not preserved as first-class project metadata.
- Collapsing all boxes into one enclosing cuboid can include blocks between independent sub-regions if the untracked space is not explicitly represented.
- The current model cannot accurately support future branch, diff, merge, or update-area workflows.

Required direction:

- Store sub-region names, sizes, and relative positions in `index.json`.
- Keep sub-region definitions versioned with the project.
- Keep independent sub-regions explicit in `index.json`; when `index.nbt` needs one enclosing structure volume, untracked space must be encoded as `minecraft:structure_void`.
- Keep fallback code explicit and visibly named when fallback to current selection is necessary.

Implementation notes:

- `index.json` now stores versioned sub-region entries with names, sizes, and positions relative to the local Master Origin.
- `local.json` stores the clone-local Master Origin and remains ignored by Git.
- Commit code restores the tracked selection from `index.json` plus `local.json`.
- Fallback to the current in-game selection remains explicit in method names.
- `index.nbt` is a single vanilla structure file. During export, LVC copies tracked blocks into a temporary schematic world, fills untracked gaps with `minecraft:structure_void`, and then delegates serialization to `StructureTemplate#fillFromWorld` / `StructureTemplate#save`. This prevents independent sub-regions from committing real world blocks from the space between them.

### 5. Repeated Directory Structure Warning

Do not follow PRD directory structure advice.

This is intentionally repeated because multiple PRD sections imply non-Git-native storage, especially `history.json` and `/data/`. LVC should not adopt those suggestions by default.

Any future storage change must answer:

- Why Git's native object/history model is not enough.
- Whether the data is shared project state or clone-local workspace state.
- Whether the data belongs in Git, in `index.json`, in `local.json`, or in generated/transient files.

### 6. Post-Commit Tracking and Verification Needs Fixing

Status: fixed in the current implementation.

The PRD describes a useful post-commit workflow: after a commit, the committed state should be visible as a persistent ghost overlay and the world should be compared against that committed state.

Current behavior:

- A commit writes `index.nbt` and creates a Git commit.
- The LVC project GUI refreshes the commit history.
- No ghost overlay is loaded.
- No verifier state is enabled.
- No clean/dirty indicator exists.

Required direction:

- After commit, load or update a ghost overlay representing the committed schematic state.
- Reuse Litematica verifier/rendering behavior where practical.
- Provide a clean/dirty signal comparing the current world against the last committed state.
- Make overlay visibility follow existing Litematica rendering controls where possible.

Implementation notes:

- After an LVC commit from the project GUI, the committed `index.nbt` is loaded through Litematica's vanilla structure loader and schematic holder.
- A normal Litematica schematic placement is created at the schematic's real world origin, computed from versioned sub-regions plus the clone-local Master Origin.
- The placement uses existing Litematica rendering controls, so the ghost overlay follows the same rendering pipeline as other placements.
- The schematic verifier is started when a client world and schematic world are available.
- The project page reports a clean/dirty tracking status after verifier completion.

### 7. History UI Actions Need Fixing

Status: fixed for the safe MVP surface.

The PRD expects history entries to support workflows such as checkout and inspection. The current LVC project page only displays a flat commit list.

Current behavior:

- The project page lists commit ID, message, author, and timestamp.
- Top-level buttons include `Update areas`, `Commit`, `Push`, and `Pull`.
- Individual history rows do not expose actions.

Required direction:

- Add row-level actions for history entries when the underlying operations are implemented.
- At minimum, design space for future `Checkout`, `Inspect`, and possibly `Diff` actions.
- Do not implement destructive world-changing actions without preview and safety checks.
- Do not confuse Git push/pull with PRD history inspection features.

Implementation notes:

- Commit history rows now expose `Inspect` and `Checkout` action buttons.
- `Inspect` displays the selected commit summary in the GUI message area.
- `Checkout` restores the selected commit into the repository working tree and applies the checked-out schematic state to the tracked sub-regions in the current Minecraft world.
- `Checkout` also reloads the Litematica overlay and verifier so the visible in-game state matches the checked-out commit.
- The project page has a branch-focused history view and a searchable branch dropdown. Branch creation, deletion, renaming, switching, and merge are implemented for the MVP safe surface. Merge uses Git for graph/ref/history mechanics and LVC semantic chunk/index merging for schematic content; when conflicts are detected, the MVP UI can accept all conflicts as base, incoming, or yours, or cancel. Per-conflict visual resolution remains future work.
- Push and pull remain top-level project operations and are not mixed with per-commit history actions.

### 8. Remote Sync Guidance from the PRD Should Be Ignored

Do not follow the PRD's instruction to hold off on remotes.

LVC is Git-backed, and remote synchronization is an important part of the MVP workflow. The current implementation may include push and pull UI, including first-time remote URL configuration.

Required direction:

- Keep push/pull as valid LVC project operations.
- Use JGit for remote operations.
- Keep remote URL configuration local to the Git repository config.
- Do not let the PRD's "hold off on Remotes & Cloning" note block remote sync work.

### 9. Long-Term Storage Direction Is Git Plus Semantic Chunks

The long-term LVC architecture should keep Git, but only as the version-control and synchronization control plane. Git should own commits, branches, tags, remotes, authorship, merge-base discovery, push, and pull. LVC should own schematic semantics.

Detailed storage schema: `docs/tech/lvc-semantic-storage.md`.

Do not design the long-term system around repeatedly committing raw `.litematic` files or one large compressed `index.nbt` as the canonical content object. Compressed binary schematic files are poor units for diff, merge, and scalable repository growth.

Required long-term direction:

- Use a semantic, deterministic, content-addressed chunk store as the canonical schematic content format.
- Store project manifests in Git. The manifest maps tracked sub-regions and storage-chunk coordinates to immutable content hashes.
- Reuse unchanged chunks across commits so repository growth is proportional to changed chunks, not full build size times commit count.
- Treat `.litematic` and vanilla structure `.nbt` files as import/export or generated cache formats unless a later design explicitly accepts their scalability tradeoffs.
- Keep explicit tracked air distinct from untracked space.

LVC stores full canonical snapshots for restore/export, including normalized block entity NBT when available. Tracked content includes canonical block entity NBT for tracked positions so inventories and other stored block-entity payload changes affect dirty state, no-op commit detection, diffs, and merge conflicts. Container inventories are merge-atomic: LVC must not automatically merge containers at slot level, even when edits touch different slots. A future conflict UI may let the player choose or compose slot-level results manually. Entity payload and scheduled tick payload remain stored but hidden from normal tracked dirty state for now. LVC should not attempt to preserve random tick future state, entity scheduler internals, block entity scheduler internals, neighbor update queues, mod task queues, server event queues, or other transient simulation internals.

Pending block/fluid ticks are simulation metadata. Store them, but do not make them normal user-facing merge conflicts. During merge, keep valid non-conflicting ticks, carry ticks from the chosen block/fluid side when applicable, and drop invalid or ambiguous conflicting ticks with at most a summary warning. Exact scheduled-tick conflict resolution may exist as an expert mode, but it should not be the default workflow.

Dirty state must be based on authoritative hash scans of tracked storage chunks, not on event history. Event hooks may be used only as hints to mark state stale or maybe dirty. LVC should not run continuous background full scans by default. Instead, provide manual `Scan Changes` and mandatory preflight scans before commit, checkout, pull, reset, discard, and world-affecting merge operations. A clean state may only be shown after an authoritative scan verifies it.

Singleplayer should use the integrated server as the authoritative scan/restore source. Multiplayer requires server-side LVC support for complete dirty detection and safe restore. Client-only multiplayer checks are approximate and must report unreadable or unloaded tracked chunks as unknown, not clean.

## Summary of Current Fix Priorities

The following implemented areas are intentionally accepted:

- Git is the source of truth for history.
- No `history.json`.
- Git-native commit history UI.
- Push and pull may exist in the MVP.
- `local.json` must stay ignored and local-only.

The following areas have been fixed or deliberately bounded:

- Shared sub-region definitions are versioned in `index.json`.
- Master Origin is kept in local-only `local.json`.
- `index.nbt` currently stores vanilla structure data for the v1 compatibility path. This is accepted for current implementation work, but the long-term canonical storage direction is semantic content-addressed chunks. Untracked gaps inside the enclosing structure volume are written as `minecraft:structure_void`, not as real world blocks and not as air to be pasted over unrelated world space.
- Post-commit, selected-commit checkout, branch checkout, discard, clear, and update-area operations refresh the visible in-game state through world restoration and/or ghost overlay plus verifier where applicable. Pull restore remains blocked until its dirty/conflict handling is hardened.
- History rows include `Inspect` and real `Checkout` actions.
- Branch create/delete/switch exists on the project page. Switching to a branch at the current HEAD preserves dirty state without restore; switching to a different branch tip uses checkout preflight and confirmation before overwriting dirty state.
