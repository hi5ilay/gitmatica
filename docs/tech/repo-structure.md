# LVC git repo structure

Each LVC project is a git repo.

## Directory structure

- local.json
- index.json
- index.nbt
- READMD.md
- .gitignore
- `<regionname>.nbt`

## Files

local.json:

Master Origin, this is the origin that all subregion origins are relative to.
This is never changed even the subregion size changes to keep the subregion origins stable.

e.g.
```json
{
  "master_origin": [-18, -61, -12]
}
```

index.json:

Records the subregion definitions and other metadata.

**Subregion bounding box changes**:
Use this file to detect any bbox changing.
If bbox changed, when merging, fir prompt the user to choose which bbox to use, and then update
the index.nbt block coordinates accordingly.

e.g.
```json
{
  "lvc_version": 1,
  "name": "Unnamed",
  "sub_regions": [
    {
      "name": "Unnamed",
      "pos1": [0, 0, 0],
      "pos2": [6, 4, 5],
      "size": [7, 5, 6]
    }
  ]
}
```

index.nbt, *.nbt:

subregions. stored im vanilla structure block format.

