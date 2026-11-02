# boarium

boarium is a Minecraft: Bedrock Edition proxy built on the design of [Spectrum](https://github.com/cooldogedev/spectrum), with [Boar](https://github.com/opencollab-incubator/Boar) as its anti-cheat.

Clients join boarium over RakNet. boarium talks to downstream servers with the Spectrum protocol as [oomph-ac/spectrum-internal](https://github.com/oomph-ac/spectrum-internal) speaks it, so servers that run [spectrum-pm-internal](https://github.com/oomph-ac/spectrum-pm-internal) or [spectrum-df-internal](https://github.com/oomph-ac/spectrum-df-internal) can sit behind it. The public spectrum-pm and spectrum-df use an older frame format and do not work with boarium.

boarium is in early development. It logs players in, passes packets between them and their server, and moves them between servers, as Spectrum does. Boar checks players, and changes what they do only when `boar_mitigations` is on.

## Building

Requires JDK 21.

```
./gradlew build
```

## Running

```
./gradlew :app:installDist
mkdir -p run && cd run
../app/build/install/boarium/bin/boarium config.yml
```

`run/` is ignored by git. `./gradlew :app:distZip` packs the same program into `app/build/distributions/boarium-<version>.zip`.

On its first start, boarium writes a default `config.yml` at the given path, or `config.yml` in the working directory when no path is given:

| Key | Default | Meaning |
|---|---|---|
| `listen` | `":19132"` | Where players connect. An empty host means every address of this machine; put an IPv6 address in quotes. |
| `server` | `127.0.0.1:19133` | The Spectrum server players join first. |
| `fallback_server` | empty | Where players go when their server is lost. Empty for none. |
| `xbox_auth` | `true` | Whether players must sign in with Xbox Live. |
| `motd`, `sub_motd`, `max_players` | `boarium`, `boarium`, `100` | What the server list shows. |
| `latency_interval` | `3000` | How often, in milliseconds, each player's latency is sent to its server. |
| `shutdown_message` | `boarium closed.` | What players see when boarium shuts down. |
| `boar_enabled` | `true` | Whether the Boar anticheat checks players. |
| `boar_mitigations` | `false` | Whether Boar may change what players do: set them back, cancel or undo their actions, make them let go of an item they are using, or kick them. Leave it off until Boar has been tested on a real network. |

Boar keeps its own config in a `boar` folder next to boarium's config, and writes the default `boar/config.yml` on its first start. boarium does not start if that file cannot be read. `boar_mitigations` takes the place of `disable-mitigations` in that file, and boarium warns at start when that file sets `disable-mitigations: true` while `boar_mitigations` is on.
