# Bedrock 1.26.20 block data

Source: AllayMC/BedrockData, folder `1.26.20.4`, commit `915f9fc8185af45955c1f8aa9d30abfe52d99384` (https://github.com/AllayMC/BedrockData/tree/915f9fc8185af45955c1f8aa9d30abfe52d99384/1.26.20.4).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.
For protocol 975 (26.20–26.23). Its block palette is the same, entry for entry, as Geyser's `bedrock/block_palette.26_20.nbt`.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.20.4/block_palette.nbt` | none | `68dc1bfa13163142854350cc6ea36b27b878af5e` | `a11653169263dc20c08f49116d8c3b90d2f38f31849f546dc1319bce40f6cd46` |
| `block_states.json.gz` | `1.26.20.4/block_states.json` | `gzip -9 -n` | `1415963e8b417aebfe12ed93323f3da5a484749a` | `2a8302bb17b0befcd2aed83368cf20ee5a093d5c0cbbfe18ef9e5311860f8cd3` (after gunzip) |
| `block_types.json.gz` | `1.26.20.4/block_types.json` | `gzip -9 -n` | `6ffca79e32ce62bedd8e1d1fe7107b7bb326b8bb` | `d07ace37b43e0c6b26c62e834652a07ded33b788d77787522145b2201634a1f7` (after gunzip) |

The files are in the same formats as those of `1.26.30` (see its README). `./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_20.bin.gz` from them.
