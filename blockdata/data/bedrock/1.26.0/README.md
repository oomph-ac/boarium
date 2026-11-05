# Bedrock 1.26.0 block data

Source: AllayMC/BedrockData, folder `1.26.0.2`, commit `915f9fc8185af45955c1f8aa9d30abfe52d99384` (https://github.com/AllayMC/BedrockData/tree/915f9fc8185af45955c1f8aa9d30abfe52d99384/1.26.0.2).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.
For protocol 924 (26.0–26.3). Its block palette is the same, entry for entry, as Geyser's `bedrock/block_palette.26_0.nbt`.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.0.2/block_palette.nbt` | none | `7c7d38f119d9a3744b64cae1af90ca0382a671ea` | `cd15fe193c32855477cc635a7c33964d24d3e78f12f943fb52f90eb73ff51b16` |
| `block_states.json.gz` | `1.26.0.2/block_states.json` | `gzip -9 -n` | `22159a4bdc5a457e6114218000472b0377165263` | `9198d75e366fe759614dfd14424e2577858ed9843e506a9dad2c5a8ec1befe4d` (after gunzip) |
| `block_types.json.gz` | `1.26.0.2/block_types.json` | `gzip -9 -n` | `27ee2bc431f810303cbc7db1da6c52ce1b869c1d` | `aaad9d919677c84a370659c547c20c39b6dec32d8fb5fa4b5bbd71517d0377c3` (after gunzip) |

The files are in the same formats as those of `1.26.30` (see its README). `./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_0.bin.gz` from them.
