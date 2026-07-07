# Conflict Resolution

## Terminology

- **conflict**
- **yours**: The version of the structure in the current branch.
- **theirs**: The version of the structure in the branch being merged.
- **connecting changes**

1. Prompt the user for subregion removed/added/renamed. If there is a conflict, prompt the user to choose which version to keep.
2. Prompt the user for subregion bbox changes. If there is a conflict, prompt the user to choose which version to keep, and update the index.nbt block coordinates accordingly.
3. Shift all subregion contents, if new bbox being applied.
4. For each changed subregions, detect the connecting changes. for each non-connecting change, prompt the user to choose which version to keep.
5. After all conflicts resolved, create a new commit with the merged structure.

**Case of bbox change**

- If the bbox change is non-conflicting, apply the new bbox and shift all subregion contents accordingly.
- If the structure is not changes in client and server world, only the bbox shifted by 1 block, the differ result should always be none, no matter current or incoming subregion origin is chosen.

