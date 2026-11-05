# Bedrock 1.26.60 block data

Source: AllayMC/BedrockData, folder `1.26.60.6`, commit `915f9fc8185af45955c1f8aa9d30abfe52d99384` (https://github.com/AllayMC/BedrockData/tree/915f9fc8185af45955c1f8aa9d30abfe52d99384/1.26.60.6).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.
For protocol 2225 (26.60). Its block palette is the same, entry for entry, as Geyser's `bedrock/block_palette.26_60.nbt`.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.60.6/block_palette.nbt` | none | `1f1f27e0567064e3583729aee4a2d7f4dd0d689c` | `67b3e702f333f058fd321a3252c22ed0b8746ebafe168bddc6dcb51b0aab3ae9` |
| `block_states.json.gz` | `1.26.60.6/block_states.json` | `gzip -9 -n` | `47d7bc92ffb0d625b8bc926049b40033aa62cf41` | `b221be347b1e22176e8f4727723ff71996d6f08ad9614cb51aee683a55d27dfd` (after gunzip) |
| `block_types.json.gz` | `1.26.60.6/block_types.json` | `gzip -9 -n` | `ff647194ae132bad64725c8c8d60de713d5a3c4a` | `ccbe8928839803d453f666c21c8ebe8f1136fd5c5d7af6a974b7a8aa661a5beb` (after gunzip) |

The files are in the same formats as those of `1.26.30` (see its README). `./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_60.bin.gz` from them.
