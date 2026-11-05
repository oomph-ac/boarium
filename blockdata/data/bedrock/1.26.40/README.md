# Bedrock 1.26.40 block data

Source: AllayMC/BedrockData, folder `1.26.40.8`, commit `915f9fc8185af45955c1f8aa9d30abfe52d99384` (https://github.com/AllayMC/BedrockData/tree/915f9fc8185af45955c1f8aa9d30abfe52d99384/1.26.40.8).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.
For protocol 2168 (26.40–26.44) and 2169 (26.45). Its block palette is the same, entry for entry, as Geyser's `bedrock/block_palette.26_40.nbt`.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.40.8/block_palette.nbt` | none | `7f33e1cd920f5207d40382e30fc072d709cd1247` | `3e70f3099c5567294c284f65f348189d0771e2840f36986d7704c6af72ce588b` |
| `block_states.json.gz` | `1.26.40.8/block_states.json` | `gzip -9 -n` | `2941931bd058f4e276180149a9948d71ed33d284` | `3a2a57d70daf4a8275707841c5611f74ab67e9f9be5fe304be49e55074e88674` (after gunzip) |
| `block_types.json.gz` | `1.26.40.8/block_types.json` | `gzip -9 -n` | `283215309b51cd2b56a522af1cb3216f7cdb3824` | `614eb67d4d4a348df1017dcf735c20d4c3f4da2b39b2a528c7491e1946204f32` (after gunzip) |

The files are in the same formats as those of `1.26.30` (see its README). `./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_40.bin.gz` from them.
