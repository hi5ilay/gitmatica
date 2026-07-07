# RVC Semantic Chunk Storage

This document defines the long-term storage format for the new RVC MVP. Git remains the VCS layer, but RVC stores schematic content as deterministic, content-addressed project chunks instead of committing raw `.litematic` or large compressed structure files as canonical state.

## Goals

- Commit only changed project chunks when a large build changes.
- Keep Git history, branches, remotes, and merge-base discovery.
- Keep schematic semantics in RVC, not in Git.
- Make export to `.litematic` or vanilla structure possible later.
- Preserve user-defined tracked sub-regions without modifying untracked gaps.
- Support one project with multiple sites, including multiple sites in the same dimension.

## Current Implementation Status

Implemented:

- `rvc.json` manifest model.
- local-only `local.json` model.
- fixed-size `16x16x16` RVC chunks.
- tracked mask semantics where mask false means untracked, not air.
- deterministic `.rvcchunk` codec.
- content-addressed object store under `objects/sha256/`.
- SHA-256 hashing through Java `MessageDigest`.
- fake-world capture for integration tests.
- Minecraft `Level` capture for block states and block entities.
- singleplayer semantic init/commit routing to integrated-server `ServerLevel` when available.
- manual active-site scan changes through the same hash path without writing objects or changing `rvc.json`.
- active-site update areas from current Litematica selection, preserving local-only origin and recapturing content.
- canonical block state strings with sorted properties.
- canonical block entity NBT with sorted compound keys and absolute `x/y/z` removed.
- semantic repo init and active-site commit.
- no-op semantic commit detection at repository/service level.

Not implemented yet:

- semantic export to `.litematic` or vanilla structure.
- semantic overlay/verifier loading.
- semantic checkout/pull restore.
- rich update-area preview and explicit local-origin moves.
- pending block/fluid tick capture.
- entity capture.
- dedicated-server authoritative capture.

## Repository Layout

```text
project/
  rvc.json
  objects/
    sha256/
      ab/
        abcdef....rvcchunk
  README.md
  .gitignore
  local.json
  .git/
```

Versioned files:

- `rvc.json`: project manifest, sites, regions, chunk object references.
- `objects/sha256/**.rvcchunk`: immutable content-addressed storage chunks.
- `README.md`: generated project description.
- `.gitignore`: must ignore `/local.json`.

Local-only files:

- `local.json`: clone-local world/server binding and site origins. This file must never be committed.

## Terminology

`project`
: One logical build or system, for example a farm.

`site`
: One independently placed coordinate space inside a project. A project may have multiple sites in the same dimension or different dimensions.

`region`
: A user-defined tracked box inside a site. Regions are user-facing and semantic.

`RVC chunk`
: A project-relative fixed-size storage unit. Default size is `16x16x16`. It is not a Minecraft chunk section, though the size intentionally matches the common section size.

`tracked mask`
: Per-RVC-chunk bitset saying which local block positions are tracked. Mask false means untracked, not air.

## Coordinate Model

RVC chunks are project-relative storage chunks.

```text
world_pos = local_site_origin + project_relative_pos
project_relative_pos = chunk_coord * chunk_size + local_chunk_pos
```

Consequences:

- Moving a site in `local.json` does not rewrite storage chunks.
- One RVC chunk may overlap multiple real Minecraft chunks or sections.
- Scan/capture code must map RVC chunks to all touched authoritative world chunks.
- The repo stores the build's coordinate system, not the Minecraft world chunk grid.
- Dedicated-server authoritative capture/restore should investigate Servux first, since it already provides server-side support for masa client mods and Litematica server-side save/paste workflows.

## Manifest: `rvc.json`

`rvc.json` is the canonical project manifest.

Example:

```json
{
  "format": "rvc-manifest-v1",
  "project_id": "2f3b5d3a-64c1-46c1-9658-193d24283e68",
  "name": "gold_farm",
  "content": {
    "chunk_format": "rvcchunk-v1",
    "hash": "sha256",
    "chunk_size": [16, 16, 16]
  },
  "sites": [
    {
      "id": "overworld_main",
      "name": "Overworld Main",
      "dimension": "minecraft:overworld",
      "regions": [
        {
          "id": "storage",
          "name": "Storage",
          "min": [0, 0, 0],
          "size": [32, 16, 32]
        }
      ],
      "chunks": {
        "0,0,0": "sha256:abcdef...",
        "1,0,0": "sha256:123456..."
      }
    },
    {
      "id": "nether_roof",
      "name": "Nether Roof",
      "dimension": "minecraft:the_nether",
      "regions": [
        {
          "id": "spawn_platforms",
          "name": "Spawn Platforms",
          "min": [0, 0, 0],
          "size": [128, 8, 128]
        }
      ],
      "chunks": {
        "0,0,0": "sha256:789abc..."
      }
    }
  ]
}
```

Required rules:

- `project_id` is stable and generated at project init.
- `site.id` and `region.id` are stable opaque IDs, not display names.
- `site.name` and `region.name` are user-facing and may be renamed.
- `region.min` is relative to the site's project coordinate space.
- `region.size` is positive on every axis.
- `site.chunks` maps RVC chunk coordinates to content object hashes.
- A chunk key is `x,y,z` using signed decimal integers.
- A chunk object must only contain positions tracked by the union of regions intersecting that chunk.
- If a region is resized and a chunk has no tracked positions left, remove that chunk entry from the manifest.

Overlap policy:

- Overlapping same-site regions are valid tracking masks.
- Storage uses the union of all region volumes, so an overlapped world position is stored once.
- If a future Litematic import finds conflicting contents for the same project coordinate across overlapping sub-regions, reject the import instead of guessing.

## Local State: `local.json`

`local.json` binds the versioned project to this clone's world/server placement.

Example:

```json
{
  "format": "rvc-local-v1",
  "project_id": "2f3b5d3a-64c1-46c1-9658-193d24283e68",
  "active_site": "overworld_main",
  "sites": {
    "overworld_main": {
      "dimension": "minecraft:overworld",
      "origin": [1000, 64, 1000],
      "world_hint": "Survival Server"
    },
    "nether_roof": {
      "dimension": "minecraft:the_nether",
      "origin": [125, 128, 125],
      "world_hint": "Survival Server"
    }
  }
}
```

Required rules:

- `local.json` must be ignored by Git.
- Every local site entry is keyed by versioned `site.id`.
- `origin` is local clone state and must not be written to `rvc.json`.
- Missing local placement for a site makes that site `UNKNOWN` for scan/commit/restore in this clone.

## Object Path

Object path:

```text
objects/sha256/<first-two-hex>/<full-hex>.rvcchunk
```

Example:

```text
objects/sha256/ab/abcdef0123....rvcchunk
```

The hash is over the exact canonical `.rvcchunk` bytes. If the file already exists, do not rewrite it.

## `.rvcchunk` Format

`.rvcchunk` is a deterministic binary object. It stores content only, not the site ID or chunk coordinate. The manifest owns placement.

Encoding primitives:

- Fixed-width integers are big-endian.
- `varuint` is unsigned LEB128.
- `varint` is signed LEB128.
- `utf8 string` is `varuint byte_length` followed by UTF-8 bytes.
- `canonical_nbt` is uncompressed canonical NBT bytes.

Header:

```text
magic             8 bytes   ASCII "RVCCHN1\0"
flags             u16       reserved, MVP writes 0
size_x            u16       MVP 16
size_y            u16       MVP 16
size_z            u16       MVP 16
tracked_mask_len  u16       MVP 512
tracked_mask      bytes     4096 bits for 16x16x16
```

Bit index:

```text
index = x + size_x * (y + size_y * z)
```

Payload:

```text
palette_count                 varuint
palette_entry[]               utf8 string, sorted by first use in index order
block_state_indices[]         varuint, one entry per tracked mask bit in ascending index order
block_entity_count            varuint
block_entity[]                block entity records
pending_block_tick_count      varuint
pending_block_tick[]          scheduled block tick records
pending_fluid_tick_count      varuint
pending_fluid_tick[]          scheduled fluid tick records
entity_count                  varuint, MVP writes 0 unless entity tracking is enabled
entity[]                      future optional records
```

`block_state_indices` count must equal the number of true bits in `tracked_mask`. Entries are written in ascending bit index order. Tracked air is stored normally as a tracked mask bit with block state `minecraft:air`. Untracked positions have mask false and no block state entry.

Block entity record:

```text
index             u16
nbt_len           varuint
canonical_nbt     bytes
```

Scheduled tick record:

```text
index             u16
target_id         utf8 string, for example "minecraft:water"
delay             varint, trigger tick relative to capture game time
priority          i8
sub_tick_order    i64
```

Entity record is reserved for a later entity-tracking pass. MVP may keep `entity_count = 0`.

### Block State Canonical String

Block states must be written as canonical strings:

```text
namespace:block[property_a=value_a,property_b=value_b]
```

Rules:

- Use the registry ID for the block.
- Omit brackets when the block has no properties.
- Sort properties by property name.
- Write property values using Minecraft's normal property value names.

Examples:

```text
minecraft:stone
minecraft:oak_log[axis=y]
minecraft:repeater[delay=2,facing=north,locked=false,powered=false]
```

### Canonical NBT

NBT used inside `.rvcchunk` must be deterministic.

Rules:

- Compound keys sorted lexicographically.
- Lists keep original order.
- Numeric types keep their original NBT type.
- Block entity absolute `x`, `y`, and `z` fields are removed before hashing/storage.
- Restore/export code reconstructs block entity position from the chunk coordinate plus local index.

If canonical NBT writing is not available from Minecraft helpers, implement a small RVC canonical NBT writer for this format.

## Capture Algorithm

Input:

- `rvc.json`
- `local.json`
- selected site IDs, or all sites for full commit
- authoritative server/integrated-server world access

Algorithm:

1. Validate manifest and local placement.
2. For each selected site, collect regions.
3. Treat same-site regions as non-owning tracking masks. Overlapping regions are allowed.
4. Enumerate all RVC chunks intersecting the site regions.
5. For each RVC chunk, build `tracked_mask` from the union of all intersecting region areas, so an overlapped position is stored once.
6. For every true mask bit, map project-relative position to world position.
7. Read block state from authoritative world state.
8. If block has a block entity, read and normalize block entity NBT.
9. Capture pending block/fluid ticks whose positions are tracked, storing relative delay.
10. Encode canonical `.rvcchunk` bytes.
11. Hash bytes with SHA-256 and write object if missing.
12. Update `site.chunks[chunk_key]`.
13. Remove old chunk entries no longer intersecting any tracked region.

Important invariant:

```text
mask false = untracked, not air
```

Untracked positions must never be committed, restored, diffed, or overwritten.

## Commit Flow

1. Run capture for the selected commit scope.
2. Update `rvc.json` with new chunk hashes.
3. Stage `rvc.json`, new object files, `README.md`, and `.gitignore`.
4. Do not stage `local.json`.
5. If no staged changes exist, report `nothing to commit`.
6. Create a Git commit with player identity and message.

MVP commit scope may be the active site only, but full-project commit should be the target behavior. If a site cannot be scanned authoritatively, the commit must report `UNKNOWN` unless the UI explicitly supports partial-site commits.

## Scan Changes Flow

Manual scan uses the same capture path without writing objects or changing `rvc.json`. The current MVP exposes this for the active site through the project GUI; future preflight should reuse the same result model for commit, checkout, pull, reset, and merge.

1. Encode each current chunk in memory.
2. Compute hash.
3. Compare against `site.chunks`.
4. Report:
   - added chunk references
   - removed chunk references
   - changed chunk references
   - unchanged chunk references
   - unknown chunks/sites

Status states:

- `UNSCANNED`: no scan has run.
- `SCANNING`: scan in progress.
- `CLEAN`: all scanned tracked chunks match current manifest.
- `DIRTY`: at least one tracked chunk differs.
- `STALE`: world may have changed after the last scan.
- `UNKNOWN`: at least one required chunk/site could not be scanned authoritatively.

Do not show `CLEAN` unless an authoritative scan has verified the current content.

## Restore And Export Notes

Restore/export is not required for the first MVP, but storage must support it.

Restore:

- Load chunk objects referenced by the manifest.
- For mask true positions, write blocks and block entities.
- For mask false positions, do nothing.
- Apply valid pending block/fluid ticks after block placement.

Export:

- Build an in-memory Litematica schematic or vanilla structure from the manifest and chunk objects.
- Untracked gaps between independent regions become absent/void, not air.

## Scheduled Tick Merge Policy

Pending block/fluid ticks are simulation metadata.

Default merge:

- Keep valid non-conflicting ticks.
- Carry ticks from the chosen block/fluid side when a related block conflict is resolved.
- Drop invalid or ambiguous conflicting ticks.
- Show only a summary warning by default.

Validation:

- A block tick is valid only when the final block at that position matches the tick target block.
- A fluid tick is valid only when the final fluid at that position matches the tick target fluid.

## MVP Validation Checklist

- `rvc.json` validates.
- `local.json` is ignored by Git.
- Same chunk content produces the same SHA-256 hash.
- Changing one block changes only the intersecting RVC chunk object hash.
- Independent region gaps remain untracked.
- Overlapping regions are preserved and captured as a union mask.
- Missing local site placement reports `UNKNOWN`.
- Client-only multiplayer cannot claim complete clean state.
