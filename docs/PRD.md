## 3. Core Storage And Change Tracking Decisions

LVC should use Git, but Git must not be expected to understand raw `.litematic` files or large compressed NBT blobs. Git is the VCS control plane: commits, branches, tags, remotes, authorship, history graph, merge base discovery, push, and pull. LVC is the schematic semantics layer: tracked volumes, block/entity/tick content, diffs, merges, world restore, overlays, and conflict UI.

Detailed storage schema: `docs/tech/lvc-semantic-storage.md`.

### 3.1 Canonical Storage Direction

The long-term canonical repo format should be a semantic, chunked, content-addressed object store committed to Git.

Example shape:

```text
repo/
  lvc.json
  objects/
    sha256/
      ab/
        abc123.lvcchunk
        def456.lvcchunk
  local.json
  .git/
```

`lvc.json` or equivalent manifest records project metadata, tracked sub-regions, and a mapping from project storage chunk coordinates to content hashes. The chunks are immutable content-addressed objects. If only one part of a large build changes, a commit should add or reference only the changed chunks while reusing the existing hashes for unchanged chunks.

`.litematic`, vanilla structure `.nbt`, and other schematic files should be treated as import/export or generated cache formats, not the long-term canonical VCS storage format. They may be produced for compatibility with Litematica or Minecraft, but they should not be the primary unit Git is asked to diff, merge, or store repeatedly.

Recommended chunk properties:

- Small enough that localized edits do not rewrite huge objects, for example `16x16x16` or another measured storage-chunk size.
- Deterministic serialization with stable ordering.
- Content-addressed by a strong hash such as SHA-256.
- Explicit distinction between tracked air and untracked space.
- Block entities and optional entity data serialized in a normalized form.

### 3.2 Versioned World State Scope

LVC should version reproducible structure state, not every transient Minecraft server detail.

Canonical tracked content:

- Blocks and block states.
- Block entity NBT, normalized.
- Entities, if the project enables entity tracking.
- Pending block ticks.
- Pending fluid ticks.

Out of scope by default:

- Random tick future state.
- Entity scheduler internals.
- Block entity scheduler internals.
- Neighbor update queues.
- Mod-specific task queues.
- Server event queues.
- Any other transient simulation queues not exposed as stable block/fluid scheduled ticks.

This keeps LVC aligned with what Litematica can practically preserve while avoiding a fragile attempt to snapshot the entire simulation engine.

### 3.3 Scheduled Tick Policy

LVC should store pending block ticks and pending fluid ticks. These are useful for exact-ish restore of water, fluids, redstone, and other delayed block/fluid updates. They should be treated as simulation metadata, not as normal user-authored build content.

Default diff behavior:

- Do not show every scheduled tick change in normal diff UI.
- Summarize them as simulation state changes when useful.
- Allow advanced inspection for exact tick details.

Default merge behavior:

- Scheduled ticks should not create normal user-facing merge conflicts.
- A tick is kept only when it is valid for the final merged block/fluid state at that position.
- If only one side changed a valid tick, keep it.
- If both sides changed a tick identically and it remains valid, keep it.
- If both sides changed a tick differently, or the final merged state does not clearly support the tick, drop the tick.
- If a block/fluid conflict exists at that position, the resolved block/fluid choice owns the tick choice; invalid ticks are still dropped.
- Show at most a summary such as `14 pending simulation updates reset`.

Optional expert mode may provide strict scheduled-tick conflict handling for exact snapshot workflows, but the default product should preserve the build over half-tick simulation details.

### 3.4 Dirty State And Change Detection

LVC should detect changes by comparing final world state to committed chunk hashes. It should not rely on knowing why the world changed.

This catches:

- Manual block breaking and placing.
- Water and fluid flow.
- Redstone updates.
- Commands and command blocks.
- WorldEdit, Axiom, and similar tools.
- Other mods, as long as the authoritative world state can be scanned.

Event hooks may be used later as hints, but they are not the source of truth. A missed event must not cause LVC to claim the world is clean. Hooks can mark a project `STALE` or a chunk `MAYBE_DIRTY`; authoritative hash scans decide whether content is actually clean or dirty.

LVC should support these status states:

- `UNSCANNED`: no authoritative comparison has been run.
- `SCANNING`: a scan is in progress.
- `CLEAN`: the tracked content was verified equal at a specific commit and scan time.
- `DIRTY`: one or more tracked chunks were verified different.
- `STALE`: the world may have changed since the last verified scan.
- `UNKNOWN`: one or more tracked chunks could not be scanned, usually because they are unloaded or the client/server lacks authority.

Do not show `CLEAN` unless an authoritative scan has verified it.

### 3.5 Scan UX And Server Authority

LVC should not run continuous background full scans by default, even as a permanent design. Large Minecraft builds and multiplayer servers need predictable performance.

Permanent workflow:

- A `Scan Changes` button and hotkey lets the player manually request a full tracked-area scan.
- Commit runs a mandatory preflight scan/export so the committed snapshot is exact.
- Checkout, pull, reset, discard, and world-affecting merge operations run a mandatory preflight scan before overwriting tracked blocks.
- If dirty content is found before a destructive operation, require an explicit user choice.
- If scan result is `UNKNOWN`, block the operation or require an explicit override.

Full scans should still be implemented incrementally:

- Scan tracked storage chunks only.
- Batch work across ticks.
- Show progress.
- Allow cancellation where safe.
- Avoid freezing the client or server.

Singleplayer uses the integrated server as the authoritative scan source. Multiplayer requires server-side LVC support for complete dirty detection and safe restore. A client-only multiplayer install can provide only approximate checks over loaded/synced chunks and must report unavailable chunks as `UNKNOWN`, not clean.

## 4.1 Project Initialization and Origin Management

The initialization process converts a standard Litematica selection into a managed VCS project. This flow handles naming, area definition, and the first version save in one seamless sequence.

### 4.1.1 Project Creation and Access

Users can initiate or access projects through two distinct workflows depending on whether they are converting an existing selection or starting a fresh repository.

#### A. The "Convert Selection" Flow (Fast-Track):

1. **Define Areas**: Use Litematica in "Normal" selection mode to define your build. This can include one or **multiple sub-regions**.
2. **The Entry Point**: Open the Litematica menu and navigate to:
   **Area Selection** -> **Save Schematic**
3. **Initiate Project**: Click the **[Create Project]** button located at the bottom of the page.
4. **Naming and Finalizing**: A prompt appears for you to name your project. Upon clicking confirm:
   - The project is created.
   - All active sub-regions are imported into the project.
   - An **initial commit** is automatically captured
5. **Automatic Manager Entry**: The system immediately transitions you into the **Project Manager** dashboard for that project.

#### B. The "Project Browser" Flow (Manual Setup):

The **Project Browser** serves as the central hub for all version-controlled builds.

1. **Entry Point**: Located at the same level as the Area Editor in the **Litematica Main Menu**.
2. **Project List**: Displays all existing projects. Loading a project from this list opens its respective **Project Manager**.
3. **Empty Project Creation**: Clicking the **[Create New Project]** button at the bottom of the browser allows for a "Manual" setup:
   - **Naming**: The user provides a project name.
   - **Empty State**: The user is sent to an empty **Project Manager**. No sub-regions are imported, and **no initial commit** is made.
   - **Manual Definition**: This allows the user to open the **Project Editor** to manually set the **Project Origin** and define sub-regions _before_ capturing the first version.

### 4.1.2 Setting and Adjusting the Project Origin

The **Project Origin** is the universal zero-point anchor for the project. Every sub-region saved in the system is stored relative to this reference point.

1. **Placement Logic**:
   - In the **Fast-Track** flow, the origin defaults to **Position 1 (Pos1)** of the selection.
   - In the **Manual** flow, the origin is unset until defined by the user.
2. **Visual Representation**: It renders in the world as a **transparent cyan box**. This distinct color differentiates the project's master anchor from standard Litematica manual origins.
3. **Relocation Logic**: The Project Origin can be moved via the **Project Editor** (see 4.1.3). If the origin is relocated, the system mathematically offsets all sub-regions to ensure the physical build remains at the same world coordinates.

### 4.1.3 The Project Editor (Structural Command)

The **Project Editor** is the dedicated interface for managing a project's physical presence. While it is based on the familiar **Litematica Area Editor** workflow, it is a separate tool focused entirely on VCS-tracked volumes.

1. **Access**: The **[Project Editor]** button is located in the **Project Manager** dashboard and the **Litematica Main Menu**.
2. **Expanded Functionality**: The Project Editor acts as a "Superset" of the standard area editor:
   - **Origin Tools**: Includes coordinate fields for manual X, Y, Z entry, nudge buttons, and a **[Move to Player]** button to anchor the project stance.
   - **Volume Management**: Allows users to **Add, Remove, Resize, Rename**, and **Relocate** the sub-regions specifically assigned to the active project.

3. **VCS Integration**: Unlike the standard Area Editor, any changes made here (e.g., resizing a box) are flagged as "Structural Changes" in the project history, requiring a commit to be finalized.
4. **Interaction**: It functions identically to Litematica’s standard editor, providing real-time visual feedback and allowing for manual box adjustments in the world while the menu is active.

---

### UI Component Summary

| Component           | Location             | Function                                                                            | In-World Visual               |
| ------------------- | -------------------- | ----------------------------------------------------------------------------------- | ----------------------------- |
| **Project Browser** | Litematica Main Menu | List of all projects; entry point for manual project creation.                      | N/A                           |
| **Create Project**  | Save Schem / Browser | Initializes project. Automates **Initial Commit** if used from Save Schematic page. | N/A                           |
| **Project Manager** | Browser              | Central dashboard for branches, commits, and project metadata.                      | N/A                           |
| **Project Editor**  | Manager / Main Menu  | Dedicated hub for Origin and Project Volume management.                             | **Cyan Box / White Outlines** |
| **Origin Tools**    | Project Editor       | Manual entry, nudging, and snapping anchor.                                         | **Cyan Transparent Box**      |
| **Area Editor**     | Litematica Main Menu | Standard Litematica volume management for non-VCS selections.                       | Standard Selection Boxes      |

---

## 4.2 Commit (The State Saving Flow)

This flow captures the current state of the blocks, entities, and NBT data within the defined sub-regions and records them as a permanent version in the project history.

### 4.2.1 The Save Version Trigger

1. **Initiate Save**: Inside the **Project Manager**, click the **[Save Version]** button.
2. **Version Description**: A prompt appears for you to enter a description (e.g., "Optimized piston timings").
3. **Project Update**: Once confirmed, the dashboard updates the version count (e.g., "Version 2 of 2"). The project now recognizes this specific world state as a historical milestone.

### 4.2.2 Sub-Region and Origin Hierarchy

The system uses a layered coordinate system to ensure your build remains consistent even if parts of it are moved.

1. **Local Anchoring**: Every block and entity is recorded relative to the **origin of the specific sub-region** it belongs to.
2. **Global Anchoring**: Each sub-region's position is, in turn, tracked relative to the **Project Origin** (the cyan box).
3. **Structural Integrity**: This hierarchical approach ensures that the internal layout of your build is preserved within its boundaries, while the entire project remains locked to your master zero-point.

### 4.2.3 Post-Save Feedback: Persistent Ghost Overlay And Verified Status

Immediately after saving, the system engages "Tracking Mode" to help you visualize future changes.

1. **Automatic Overlay**: The version you just saved is projected back into the world as a **Ghost Overlay**.
2. **Verified Comparison**: LVC compares the physical world to the saved state when the user runs **Scan Changes** and during required preflight scans before operations such as save, checkout, pull, reset, and discard.
3. **Change Highlighting**: Deviations found by a scan can be highlighted with color tints:
   - **Red**: The wrong block is in this position.
   - **Orange**: The block is correct, but its state is wrong (e.g., a repeater is on the wrong delay).
   - **Magenta**: A block from the save is missing in the world.
   - **Light Blue/Cyan**: An extra block exists in the world that was not in the save.

4. **The Clean State**: The project is shown as clean only after an authoritative scan verifies that the physical build matches the saved state. If the world may have changed after the last scan, the status becomes stale instead of clean.
5. **Visibility Control**: You can toggle the ghost overlay and the highlights on or off at any time using standard Litematica rendering hotkeys.

### 4.2.4 The Discard Mechanism (Instant Reversion)

The **Discard Changes** function provides a rapid way to reset the workspace to a known-good state. It is designed for iterative testing, allowing users to experiment with blocks or redstone logic and then instantly roll back to the last commit without navigating the full Checkout menu.

1. **Initiate Discard**: Click the **[Discard Changes]** button in the **Project Manager** (located next to the [Save Version] button).
2. **Safety Confirmation**: By default, a mandatory confirmation prompt appears to prevent accidental data loss.

   > **"Warning: This will physically overwrite all blocks within project sub-regions to match the last commit. This action is irreversible. Proceed?"**
   - **Configurable Prompt**: For users who prefer an uninterrupted workflow, this warning can be disabled in the **Project Settings** (located in the Project Manager at the same level as the Project Editor). When disabled, clicking Discard triggers the restoration immediately.

3. **Physical Restoration (The "Swap")**: Upon confirmation, the system performs a high-priority version restoration:
   - **Clear**: Wipes blocks, entities, and NBT data within the current sub-region volumes.
   - **Restore**: Instantly populates the volumes with the exact states recorded in the **active commit**.

4. **Clean State Transition**: The world is now physically identical to the last save. All tracking highlights (Red/Magenta/etc.) are automatically cleared.

---

### Commit Lifecycle Summary

| Stage            | Action                      | Result                                                                 |
| ---------------- | --------------------------- | ---------------------------------------------------------------------- |
| **Trigger**      | Click **[Save Version]**    | Opens description prompt; records a new historical milestone.          |
| **Tracking**     | Active Ghost Overlay        | Saved state remains visible; scan results provide verified comparison. |
| **Recovery**     | Click **[Discard Changes]** | **Triggers Warning Prompt**; physically reverts blocks to last commit. |
| **Verification** | Color Highlighting          | Visual cues appear if the build deviates from the save.                |

---

## 4.3 Checkout (Version Restoration)

This flow allows users to physically revert the world to a specific point in history. It includes a mandatory preview phase to ensure the restoration is accurate before the world is modified.

### 4.3.1 The Checkout Trigger and Safety Check

1. **Select Version**: In the **Project Manager**, click on a specific commit entry to expand the **Context Menu**.
2. **Initiate Checkout**: Click the **[Checkout]** button within the context menu.
3. **Unsaved Changes Prompt**: The system runs a preflight scan against the current world state. If discrepancies exist, the user is prompted to commit or discard changes before proceeding. If any tracked chunks cannot be scanned authoritatively, the operation is blocked or requires an explicit override.

### 4.3.2 Target Version Preview (Ghost Overlay)

Before the physical swap occurs, the system enters a Preview Mode:

1. **Ghost Placement**: The selected target version is loaded as a Ghost Overlay in the world, mapped to the current Project Origin.
2. **Visual Verification**: The user can walk around the build to see exactly where the blocks will land.
3. **Preview Comparison**: The system highlights mismatches between the current physical world and the target ghost overlay using the standard color palette (Red/Orange/Magenta/Cyan). This shows the user exactly what will be added, removed, or changed if they proceed.

### 4.3.3 The Final Confirmation and Restoration

1. **Confirmation Prompt**: The user is presented with a final choice: [Confirm] or [Cancel].
2. **Union Volume Clear**: Upon confirmation, the system calculates the Union Volume (the combined bounding boxes of the Current State and the Target State) and clears everything within this volume, including:
   - All blocks and their states.
   - All entities (Armor stands, Minecarts, Item frames, etc.).
   - All Tile Entity data (inventories, signs, etc.).

3. **Physical World Swap**: The system populates the cleared volume with the exact block states, entities, and NBT data from the target commit.
4. **Overlay Transition**: The ghost overlay from the preview is dismissed, and a new persistent ghost overlay of the now-active version is applied.

---

### Checkout Workflow Summary

| Stage            | Action                 | Result                                          |
| ---------------- | ---------------------- | ----------------------------------------------- |
| **Selection**    | Pick version from list | Initiates the restoration sequence.             |
| **Preview**      | Inspect Ghost Overlay  | Visualizes changes before world modification.   |
| **Confirmation** | Click [Confirm]        | Triggers the physical block swap.               |
| **Result**       | World Update           | Physical blocks now match the selected version. |

---

### Context Menu

| Option                 | Function                                                  |
| ---------------------- | --------------------------------------------------------- |
| **Checkout**           | Physically swaps the world to this commit's state.        |
| **Open Changes**       | Enters the dual-ghost visualizer mode (See 4.4).          |
| **Create Branch From** | Spawns a new independent timeline from this anchor point. |

---

## 4.4 History Diff Inspection (Visual Comparison)

This feature is an advanced diagnostic mode modeled after Litematica and TechUtils verifiers. It allows users to see exactly what changed within a specific commit by comparing it against its predecessor in a non-interactable, "view-only" environment.

### 4.4.1 The Inspection Trigger (Action Hub)

1. **Select Commit**: In the **Project Manager**, click on the commit entry you wish to audit to expand the **Context Menu**.
2. **Initiate Inspection**: Click the **[Open Changes]** button.
3. **Safety Check**: The system prompts the user to ensure the current physical work is saved.
4. **The "Visualizer" Clear**: Upon confirmation, the system calculates the combined volume of the **Current State**, the **Parent State**, and the **Target State**. It clears all physical blocks and entities from this volume to provide a clean slate for the visualizer.
5. **Full-Build Ghost Loading**: Unlike a traditional text-based Git diff that only shows lines changed, this mode loads **two complete versions of the entire build**. Both layers are aligned to the **Project Origin**:

- **Layer A (The Parent State)**: A ghost representation of the entire build as it existed in the previous version. This layer uses **Solid-Style Transparency**, making it look like real blocks while remaining non-interactable.
- **Layer B (The Target State)**: A translucent ghost representation of the entire build as it exists in the selected version, layered directly over the Parent.

### 4.4.2 Standardized Color Palette

The system compares the two complete build states and applies tints based on the differences found between the two full-volume layers:

| Color              | Status Type      | Description                                                                      |
| ------------------ | ---------------- | -------------------------------------------------------------------------------- |
| **Light Blue**     | Added            | Block exists in the Target version but was absent in the Parent.                 |
| **Pink / Magenta** | Removed          | Block was in the Parent but is missing from the Target.                          |
| **Orange**         | Mismatched State | Block type is the same, but properties (delay, rotation) or inventories changed. |
| **Red**            | Wrong Block      | The block type in the Target is different from the Parent at that coordinate.    |

### 4.4.3 The Verifier GUI and Toggle View

The user can open the Verifier GUI to control the visualization of these two full-build layers:

- **Filter by Comparison State**: Instantly toggle the visibility of blocks based on their relationship between the two versions. This includes:
- **Wrong Blocks (Red)**, **Wrong States (Orange)**, **Extra Blocks (Pink)**, **Missing Blocks (Blue)**, and **Correct State (Unchanged)**.
- **Verification Range**: Use the **Range:** setting to define the scope of the comparison. This allows users to restrict the verification check to specific layers or sub-regions rather than the entire volume.
- **Layer Visibility**: Toggle the Parent (Solid Ghost) or Target (Translucent Ghost) layer on/off to see the "Before" and "After" versions of the entire project.

### 4.4.4 The Information HUD

A real-time HUD provides data on the ghost blocks currently under the crosshair:

- **Property Mismatches**: Displays the exact state change (e.g., Parent: delay=1 | Target: delay=3).
- **Inventory Mismatches**: Lists specific item changes in containers (e.g., Target added 1x Diamond).

### 4.4.5 Component Clustering

- **Glow Boundaries**: Groups of touching mismatched blocks are wrapped in a glowing edge to highlight the "zone" of change within the full build.
- **Unit Logic**: Individual block changes are grouped into logical "component updates," allowing the user to see a complex circuit change as a single unit of history.

---

## 4.5 Branching (Parallel Timelines)

This feature enables non-linear development, allowing both the project structure (Sub-Regions) and the block data to diverge into separate, independent paths.

### 4.5.1 Contextual Branch Creation

In the **Project Manager**, branching is handled as a contextual action tied to specific commits in your history.

1. **Select a Base**: Click on any commit entry within the version history list. This expands a **Context Menu** for that specific point in time.
2. **Branch Setup**: Upon clicking **[Create Branch From]**, you are prompted to **name** the new timeline (e.g., `feature`).
3. **Structural Inheritance**: The new branch initially inherits the exact **Sub-Region definitions** (box sizes and positions) of the commit it was spawned from.
4. **Divergent Layouts**: From this point forward, any changes made to Sub-Regions (adding, resizing, or moving boxes) are recorded **only** within the active branch. This allows one branch to have a compact footprint while another expands to include new modules.

---

## 4.6 Feature: Branch Merging & Conflict Resolution

Merging allows parallel timelines to converge. This process is divided into two distinct phases: first defining the tracking boundaries (Structural), then reconciling the blocks within those boundaries (Content).

### 4.6.1 Structural Merge (The Volume Phase)

Before block data is compared, the system must resolve the "where" and "how big" of the tracking boxes.

1. **Coordinate Mapping**: The system mathematically resolves the world-position of all sub-regions. By calculating positions relative to the shared **Project Origin**, it determines if a build has physically moved in the world or if only the tracking box itself has shifted.
2. **Geometric Comparison**: If a Sub-Region's Relative Position or Size differs between the two branches, the user must choose a boundary definition.

- **The Choice**: "Which tracking volume (Box) should we keep for the merged version?"

3. **The Box vs. Build Logic**: If the bounding box moved but the machine stayed at the same world coordinates, the system recognizes that the content is identical. No block-level conflict is triggered.

### 4.6.2 Content Merge (Blocks, Inventories, and Entities)

Once the structural volume is established, the system reconciles the actual content based on physical world-space coordinates. This phase covers everything from solid blocks to the data stored within containers and entities.

### A. Blocks and Inventories (Manual Resolution)

Block-level data and container contents are treated as critical project information. Any discrepancy requires a manual choice to ensure the technical integrity of the build.

- **Block Comparison**: The system checks for differences in **Block Type** and **Block State**.
- **Inventory Contents**: For all container blocks, the system performs a comparison of the stored items and their properties.
- **Resolution Logic**: If the contents or states differ between branches, the block is flagged as a conflict. The user must select **[Accept Incoming]** or **[Keep Current]** to determine which version to preserve.

### B. Entity Reconciliation (Tiered Tracking)

To prevent minor metadata fluctuations from cluttering the merge, entities use a tiered resolution system based on their unique identity:

1. **Tracked**: User manually resolves the conflict through the verifier.
2. **Tracked but Hidden**: System silently keeps the **Current** branch value to maintain consistency.
3. **Untracked**: System discards both versions and applies **Default Values** to ensure a clean state.

### C. Scheduled Block And Fluid Ticks

Pending block ticks and pending fluid ticks are preserved as simulation metadata where possible, but they should not create normal merge conflicts.

The merge keeps valid non-conflicting ticks, carries ticks from the chosen block/fluid side when a related block conflict is resolved, and drops invalid or ambiguous conflicting ticks. The UI may show a summary warning such as `Pending simulation updates reset`, but the user should not be forced to resolve individual tick entries in the default workflow.

### D. Visual Conflict Audit (The "Merge Verifier")

To facilitate rapid resolution, the system utilizes the same visual diagnostic tools as the History Diff mode:

- **Standardized Color Palette**: All mismatches between branches are highlighted using the project's universal color code (e.g., **Red** for wrong blocks, **Orange** for wrong states, etc.).
- **Component Clustering**: Just as in **4.4.5**, the system groups adjacent mismatched blocks or entities into logical clusters. These are wrapped in a glowing boundary, allowing the user to accept or reject an entire circuit update or machine module as a single unit rather than block-by-block.

### E. The Trimming Protocol

If any resolved data (Accepted blocks, resolved entities, or defaulted stats) sits outside the final boundaries of the box chosen in **4.6.1**, the system will **crop** that data. Only content physically contained within the final merged volume is preserved in the resulting commit.

---

### Merge Conflict Summary

| Feature                  | Conflict Type          | Resolution Method |
| ------------------------ | ---------------------- | ----------------- |
| **Blocks**               | Type/State Mismatch    | Manual Choice     |
| **Inventories**          | Content Mismatch       | Manual Choice     |
| **Entities (Tracked)**   | Presence/Pose Mismatch | Manual Choice     |
| **Entities (Hidden)**    | Physics/Rotation Diff  | Auto-Current      |
| **Entities (Untracked)** | Status/Life Stats Diff | Auto-Default      |
| **Block/Fluid Ticks**    | Simulation Diff        | Auto-Drop Invalid/Ambiguous |

Stage,Logic,User Interaction

1. Volume Selection,Compare Current vs. Incoming Box dimensions/offsets.,Select [Current Box] or [Incoming Box].
2. Content Diff,Compare blocks based on resolved world-coordinates.,Use colors to [Accept] or [Reject] changes.
3. Trimming Check,"Check if ""Accepted"" blocks fall outside the selected volume.",[Confirm Trim] or adjust volume.
4. Overlap Check,Detect if boxes now share the same world-space.,[Acknowledge Overlap] and finalize merge.

### 4.6.3 Tracking Masks & Overlaps

When merging causes two or more Sub-Regions to occupy the same physical space:

1. **Tracking Masks**: The system allows sub-regions to overlap. Sub-regions do not own blocks; they define tracking masks.
2. **Union Storage**: A physical block inside multiple sub-regions is captured once in the LVC chunk object.
3. **Export Preservation**: Litematic export should preserve the user's overlapping sub-region names and bounds.

### 4.6.4 Final Merge Validation Workflow

| Stage                   | Logic                                                        | User Interaction                                    |
| ----------------------- | ------------------------------------------------------------ | --------------------------------------------------- |
| **1. Volume Selection** | Compare Current vs. Incoming Box dimensions/offsets.         | Select **[Current Box]** or **[Incoming Box]**.     |
| **2. Content Diff**     | Compare blocks based on resolved world-coordinates.          | Use colors to **[Accept]** or **[Reject]** changes. |
| **3. Trimming Check**   | Check if "Accepted" blocks fall outside the selected volume. | **[Confirm Trim]** or adjust volume.                |
| **4. Overlap Check**    | Detect if boxes now share the same world-space.              | **[Acknowledge Overlap]** and finalize merge.       |

## TODO

Remote: host project on server
