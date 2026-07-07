# LVC Git Operations

## Terminology

- **LVC project**: A Minecraft structure project managed by LVC, stored in a Git repository.
- **Commit**
- **Branch**
- **Merge**
- **Diff**
- **Differ**: Our custom diffing algorithm that compares two structures and detects changes.
- **Client World**: The Minecraft world currently open in the client, which may or may not be the same as the world where the structure is located.
- **Server World**: The Minecraft world where the structure is located, which may or may not be the same as the client world.
- **Schematic World**: A temporary world created by litematica / LVC to perform verifying and diffing operations.


### Checkout

When checking out a commit or branch, LVC will:
1. Load the structure from the target commit/branch into the schematic world.
2. the schematic world will show verifier result (see logic in fi.dy.masa.litematica)

**Do not modify client or server world during checkout.**


### Commit

1. Find the bbox. try to load from local.json, if not exist, fallback to current area selection.
2. Export the bbox area into a schematic world, and then save it as index.nbt in vanilla structure format.
3. if bbox changed, also update the index.json with the new bbox and subregion definition.
4. Use jgit to create a commit with the new index.nbt, and custom metadata.

### Diff

TODO

### Push & Pull

### Merge

#### Merge Start

#### Merge Abort

#### Merge Commit
