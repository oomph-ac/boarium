# Bedrock 1.26.10 block data

Source: AllayMC/BedrockData, folder `1.26.12.2`, commit `915f9fc8185af45955c1f8aa9d30abfe52d99384` (https://github.com/AllayMC/BedrockData/tree/915f9fc8185af45955c1f8aa9d30abfe52d99384/1.26.12.2).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.
For protocol 944 (26.10). Its block palette is the same, entry for entry, as Geyser's `bedrock/block_palette.26_10.nbt`.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.12.2/block_palette.nbt` | none | `4fd6d8af61274aa8f3749e67d92b433241c3e719` | `9f0efba37cae0e49477626c9809c4c2274004d5736dc31f235b236d62093677a` |
| `block_states.json.gz` | `1.26.12.2/block_states.json` | `gzip -9 -n` | `31e5503d7cfa06a17eb70731c71c05f7a8a232f9` | `37954f1c786acba0d43baf73077e1e43e299fbf52fd7e83b3dc041914ac1218d` (after gunzip) |
| `block_types.json.gz` | `1.26.12.2/block_types.json` | `gzip -9 -n` | `ff278f49918407cf203740eba4e744a8e2baa4a6` | `6d649a0e7119ecf4c8a8c52c5dfa18c0173cb46f0b2dd9555305833e992a4559` (after gunzip) |

The files are in the same formats as those of `1.26.30` (see its README). `./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_10.bin.gz` from them.
