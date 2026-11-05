# Bedrock 1.26.50 block data

Source: AllayMC/BedrockData, folder `1.26.50.5`, commit `915f9fc8185af45955c1f8aa9d30abfe52d99384` (https://github.com/AllayMC/BedrockData/tree/915f9fc8185af45955c1f8aa9d30abfe52d99384/1.26.50.5).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.
For protocol 2193 (26.50–26.52). Its block palette is the same, entry for entry, as Geyser's `bedrock/block_palette.26_50.nbt`.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.50.5/block_palette.nbt` | none | `a80bd11f00132962047639fd3848f774366ad591` | `3097d3bd82e0aa38ca29e5be14692749edc81ff3e9250128833690ccc4a4d64b` |
| `block_states.json.gz` | `1.26.50.5/block_states.json` | `gzip -9 -n` | `a76196262c544899230c377b6e7cf487f2a1b0ff` | `a38f67ed01a7d3f1f69b7daafb76774774fbef0e6eaba22f1be0208a6b0792e7` (after gunzip) |
| `block_types.json.gz` | `1.26.50.5/block_types.json` | `gzip -9 -n` | `c9f9752bb88d88c1b21baed494a08806446abfbc` | `5ee982c84f153e11cccfcba8f406befde543a090a0d8f9adb23b7a0844ba734c` (after gunzip) |

The files are in the same formats as those of `1.26.30` (see its README). `./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_50.bin.gz` from them.
