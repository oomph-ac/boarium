# boarium

boarium is a Minecraft: Bedrock Edition proxy built on the design of [Spectrum](https://github.com/cooldogedev/spectrum), with [Boar](https://github.com/opencollab-incubator/Boar) as its anti-cheat.

Clients join boarium over RakNet. boarium talks to downstream servers with the Spectrum protocol as [oomph-ac/spectrum-internal](https://github.com/oomph-ac/spectrum-internal) speaks it, so servers that run [spectrum-pm-internal](https://github.com/oomph-ac/spectrum-pm-internal) or [spectrum-df-internal](https://github.com/oomph-ac/spectrum-df-internal) can sit behind it. The public spectrum-pm and spectrum-df use an older frame format and do not work with boarium.

boarium is in early development and does not run yet.

## Building

Requires JDK 21.

```
./gradlew build
```
