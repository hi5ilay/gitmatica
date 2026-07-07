# Future Storage Notes

## Revisit `.lvcidx` Encoding

For future storage optimization, benchmark whether versioned hash indexes should switch from compressed monolithic `.lvcidx` files to an uncompressed deterministic format.

Hypothesis: uncompressed, sorted, deterministic index records may be larger in the working tree but pack better across history because Git can delta-compress similar blobs. This may be a better first step than chunking index files, which adds more file and merge complexity.

Benchmark before changing format:

- Current compressed monolithic `.lvcidx`.
- Uncompressed deterministic `.lvcidx`.
- Optional chunked/page index format.

Use a realistic project with many commits changing a small number of semantic chunks each, run `git gc`, then compare packed repo size, read/write cost, and implementation complexity.
