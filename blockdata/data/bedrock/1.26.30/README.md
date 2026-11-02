# Bedrock 1.26.30 block data

Source: AllayMC/BedrockData, folder `1.26.33.1`, commit `6d708fea20c971be89420bce2d756b3329817b39` (https://github.com/AllayMC/BedrockData/tree/6d708fea20c971be89420bce2d756b3329817b39/1.26.33.1).
Licence: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/), as the upstream repository states.

| File here | Upstream file | Change | Upstream git blob | SHA-256 of the upstream file |
| --- | --- | --- | --- | --- |
| `block_palette.nbt` | `1.26.33.1/block_palette.nbt` | none | `ce3fb4afb13ea2ceed6b7c417a800c81fad8469a` | `5751c01f7a1fe836de146f9e4e1bc5c802c66dd99ecf055e8cd94f85b651f1ae` |
| `block_states.json.gz` | `1.26.33.1/block_states.json` | `gzip -9 -n` | `ea08024d64f0770c4e8fb7d7f163e0cf92d0eb17` | `4c52232c3b535537cb49a1325fe96d03d53002e4769890263364238969d21758` (after gunzip) |

`block_palette.nbt` is the block palette in runtime ID order (gzip, big-endian NBT). `block_states.json` is a dump of every block state from Bedrock Dedicated Server, with its collision shape and other properties.

The dump cuts floats to 6 decimals, so 0.8175 prints as 0.817499. The generator reads a 6-decimal number as the float of the shorter decimal one millionth away from zero (0.817499 as 0.8175) when that float cuts to the same text, and as printed otherwise (0.754166 on pointed dripstone, 0.199999 on turtle eggs).

`./gradlew :blockdata:generateBedrockBlocks` builds boar's `bedrock_blocks.26_30.bin.gz` from these two files.
