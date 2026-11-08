# boarium

boarium is a Minecraft: Bedrock Edition proxy built on the design of [Spectrum](https://github.com/cooldogedev/spectrum), with [Boar](https://github.com/opencollab-incubator/Boar) as its anti-cheat.

Clients join boarium over NetherNet, and over RakNet when it is turned on. boarium talks to downstream servers with the Spectrum protocol as [oomph-ac/spectrum-internal](https://github.com/oomph-ac/spectrum-internal) speaks it, so servers that run [spectrum-pm-internal](https://github.com/oomph-ac/spectrum-pm-internal) or [spectrum-df-internal](https://github.com/oomph-ac/spectrum-df-internal) can sit behind it. The public spectrum-pm and spectrum-df use an older frame format and do not work with boarium.

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
| `listen` | `":19132"` | Where players connect: the host, and the port that each port set to 0 takes. An empty host means every address of this machine; put an IPv6 address in quotes. |
| `raknet.enabled` | `false` | Whether players can join over RakNet, which the game tries after NetherNet when it joins by address. boarium does not start with both RakNet and NetherNet off. |
| `raknet.port` | `0` | RakNet's UDP port. 0 means the listen port. boarium does not start when it is NetherNet's UDP port too. |
| `nethernet.enabled` | `true` | Whether players can join over NetherNet, which the game tries first when it joins by address. If NetherNet cannot start, boarium logs why and takes RakNet players only, or does not start when RakNet is off. |
| `nethernet.port` | `0` | The UDP port NetherNet connections use. 0 means the listen port. |
| `nethernet.signaling_port` | `0` | The TCP port of NetherNet's signaling, where the game asks to join. 0 means the listen port, which is where the game looks for it. |
| `nethernet.https.certificate`, `nethernet.https.private_key`, `nethernet.https.password` | empty | A certificate for NetherNet to serve HTTPS with instead of HTTP, which then turns plain HTTP away: a PEM certificate chain with its PEM private key, or a PKCS12 file alone, with `private_key` left empty; and the password of the PKCS12 file or of an encrypted key. Paths are relative to the config's folder. The game asks its player to trust a server it reaches over plain HTTP. boarium does not start if the files cannot be read or the key does not belong to the certificate, and warns when the certificate is out of its dates. Unused while `nethernet.enabled` is false. |
| `server` | `127.0.0.1:19133` | The Spectrum server players join first. |
| `fallback_server` | empty | Where players go when their server is lost. Empty for none. |
| `xbox_auth` | `true` | Whether players must sign in with Xbox Live. |
| `motd`, `sub_motd`, `max_players` | `boarium`, `boarium`, `100` | What the server list shows. |
| `latency_interval` | `3000` | How often, in milliseconds, each player's latency is sent to its server. |
| `shutdown_message` | `boarium closed.` | What players see when boarium shuts down. |
| `boar_enabled` | `true` | Whether the Boar anticheat checks players. |
| `boar_mitigations` | `false` | Whether Boar may change what players do: set them back, cancel or undo their actions, make them let go of an item they are using, or kick them. Leave it off until Boar has been tested on a real network. |

With `listen` on one address, the WebRTC library under NetherNet logs a few warnings at each NetherNet join, such as `juice: Send failed, errno=65` and `juice: STUN message send failed`. The join still works.

Boar keeps its own config in a `boar` folder next to boarium's config, and writes the default `boar/config.yml` on its first start. boarium does not start if that file cannot be read. `boar_mitigations` takes the place of `disable-mitigations` in that file, and boarium warns at start when that file sets `disable-mitigations: true` while `boar_mitigations` is on.
