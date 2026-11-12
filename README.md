# boarium

boarium is a Minecraft: Bedrock Edition proxy built on the design of [Spectrum](https://github.com/cooldogedev/spectrum), with [Boar](https://github.com/opencollab-incubator/Boar) as its anti-cheat.

Clients join boarium over NetherNet, and over RakNet when it is turned on. boarium talks to downstream servers with the Spectrum protocol as [oomph-ac/spectrum-internal](https://github.com/oomph-ac/spectrum-internal) speaks it, so servers that run [spectrum-pm-internal](https://github.com/oomph-ac/spectrum-pm-internal) or [spectrum-df-internal](https://github.com/oomph-ac/spectrum-df-internal) can sit behind it. The public spectrum-pm and spectrum-df use an older frame format and do not work with boarium.

boarium is in early development. It logs players in, passes packets between them and their server, and moves them between servers, as Spectrum does. Boar checks players and, with `boar_mitigations` on as it is by default, corrects what they do on the server's side.

To put boarium in front of a Dragonfly server, follow [SETUP.md](SETUP.md).

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
| `raknet.max_connections_per_address` | `10` | How many RakNet connections one IP may have open at once. RakNet does not let in more, and logs the IP, at most once a minute for each. Each IP may also open only 3 new connections in 4 seconds; a client over that can try again after them. Players who share an IP, as at a school or behind a mobile carrier, share these limits. Unused while `raknet.rate_limiting` is false. |
| `raknet.packet_limit` | `120` | How many UDP packets one client, an IP and port, may send to RakNet in each 10 milliseconds. A client that sends more is ignored for 10 seconds. Clients send packets in bursts, so a low limit cuts off normal players. Unused while `raknet.rate_limiting` is false. |
| `raknet.rate_limiting` | `true` | Whether the two limits above apply. Turn it off when players reach RakNet through a proxy, which gives them all its IP. These are Geyser's limits and defaults. |
| `nethernet.enabled` | `true` | Whether players can join over NetherNet, which the game tries first when it joins by address. If NetherNet cannot start, boarium logs why and takes RakNet players only, or does not start when RakNet is off. |
| `nethernet.port` | `0` | The UDP port NetherNet connections use. 0 means the listen port. |
| `nethernet.signaling_port` | `0` | The TCP port of NetherNet's signaling, where the game asks to join. 0 means the listen port, which is where the game looks for it. |
| `nethernet.advertise_addresses` | `[]` | Where joining players are told to reach NetherNet's UDP port, for a server behind NAT or port forwarding: host names or IPs, each with an optional port, such as `play.example.com`, `play.example.com:19133` or your public IP. Put an IPv6 address with a port in brackets and quotes, as `"[IPv6]:19133"`. A missing port means `nethernet.port`. A host name is looked up when NetherNet starts, and NetherNet does not start if one does not resolve. Players also get this machine's own addresses, such as its LAN address, unless the list names one of them. Empty means this machine's own addresses, plus the public address that `stun_discovery` finds. |
| `nethernet.stun_discovery` | `true` | Whether to find this machine's public address through public STUN servers, Cloudflare's (`stun.cloudflare.com:3478`) and Google's (`stun.l.google.com:19302`), while `advertise_addresses` is empty. boarium contacts them when NetherNet starts, and keeps asking while it runs. It skips them when `listen` is a loopback address, from which they cannot be reached. If no server answers, NetherNet still runs, and the NetherNet library warns about 30 seconds after start that it has had no answer from each. |
| `nethernet.https.certificate`, `nethernet.https.private_key`, `nethernet.https.password` | empty | A certificate for NetherNet to serve HTTPS with instead of HTTP, which then turns plain HTTP away: a PEM certificate chain with its PEM private key, or a PKCS12 file alone, with `private_key` left empty; and the password of the PKCS12 file or of an encrypted key. Paths are relative to the config's folder. The game asks its player to trust a server it reaches over plain HTTP. boarium does not start if the files cannot be read or the key does not belong to the certificate, and warns when the certificate is out of its dates. Unused while `nethernet.enabled` is false. |
| `server` | `127.0.0.1:19133` | The Spectrum server players join first. |
| `fallback_server` | empty | Where players go when their server is lost. Empty for none. |
| `xbox_auth` | `true` | Whether players must sign in with Xbox Live. |
| `motd`, `sub_motd`, `max_players` | `boarium`, `boarium`, `100` | What the server list shows. |
| `latency_interval` | `3000` | How often, in milliseconds, each player's latency is sent to its server. |
| `shutdown_message` | `boarium closed.` | What players see when boarium shuts down. |
| `boar_enabled` | `true` | Whether the Boar anticheat checks players. |
| `boar_mitigations` | `true` | Whether Boar may change what players do: set them back, cancel or undo their actions, make them let go of an item they are using, or kick them. When it is off, Boar only logs what it finds. |
| `boar_alert_limit` | `1` | How many of Boar's flags a second, for each player and check, boarium logs to its console. The next line after some were held back ends with `(+N more)`. 0 logs every flag. Boar still counts and acts on every flag. |
| `resource_packs.directory` | empty | Resource-only pack directory, relative to config.yml. Empty offers no custom packs. |
| `resource_packs.required` | `false` | Require players to accept custom packs. |
| `resource_packs.include_exempted` | `true` | Include the vanilla Education/Chemistry stack. |

boarium does not start when its config has a key it does not take, such as a misspelt one, and names that key.

With `listen` on one address, the WebRTC library under NetherNet logs a few warnings at each NetherNet join, such as `juice: Send failed, errno=65` and `juice: STUN message send failed`. The join still works.

While NetherNet is on, boarium keeps the key that the game trusts the server by in `nethernet/identity.pem`, next to boarium's config, and creates it on its first start. Back it up with the config: if it is deleted, every player has to confirm the server again. The file cannot be a symbolic link, as some secret mounts make it, so copy it in. NetherNet does not start if the file cannot be read.

Boar keeps its own config in a `boar` folder next to boarium's config, and writes the default `boar/config.yml` on its first start. boarium does not start if that file cannot be read. `boar_mitigations` takes the place of `disable-mitigations` in that file, and boarium warns at start when that file sets `disable-mitigations: true` while `boar_mitigations` is on.

boarium logs Boar's flags to its console, up to `boar_alert_limit`, in one line each: Boar's alert, with the `prefix` from Boar's config, and whether `boar_mitigations` lets Boar act on the player. A movement trace that Boar adds with `debug-mode` on goes to the DEBUG level, which boarium shows when started with `JAVA_OPTS=-Dorg.slf4j.simpleLogger.log.Boar=debug`.

## Resource packs

boarium can serve unencrypted resource-only `.mcpack` or `.zip` archives during login. The archive must contain `manifest.json` at its root. Configure a directory relative to `config.yml` (or an absolute path):

```yaml
resource_packs:
  directory: packs
  required: false
  include_exempted: true
```

The default directory is empty, which offers no custom packs. `required` defaults to false. `include_exempted` defaults to true for the existing vanilla Education/Chemistry stack; turn it off when these packs override player textures. Packs load in filename order. Each route's catalogue is limited to 64 packs and 256 MiB of combined compressed data per generation, kept in memory; while a symlink swap is in progress, the route holds the old generation and the new one. Manifest JSON is limited to 1 MiB. Duplicate UUIDs, invalid manifests, unsupported module types and marketplace-encrypted packs make a generation invalid. A five-minute download deadline replaces the login deadline from the first nonempty SEND_PACKS request until the client reports HAVE_ALL_PACKS or REFUSED, when a fresh login deadline starts; cached-pack clients keep the ordinary login deadline. Duplicate chunk requests are ignored. Use new manifest versions when changing pack content.

An ordinary directory loads once, before listeners start: if it is missing or invalid, boarium does not start, and changes inside it need a restart. A directory symlink can instead publish complete immutable generations atomically: boarium checks its resolved target every second and loads a changed target off packet threads for new logins, and active downloads keep their snapshot. A symlink whose target is missing or invalid, including at startup, rejects new logins on that route until a valid generation is published; boarium retries only when the target or the files in it change, and other routes stay available. Do not change files inside a published generation.

Optional packs may be declined. Required packs must be accepted to join. Cached packs need no download. Unknown pack IDs, wrong versions and invalid chunk indices disconnect the client. Encrypted packs, behavior packs, scripts and CDN delivery are not supported. boarium does not fetch packs from Spectrum backends: export each backend's complete pack set, including generated custom-item assets, and deploy matching archives before starting the proxy. Servers reached by a transfer must be compatible with the same login pack stack.

## Multiple routes in one process

Pass multiple config paths to run several routes in one JVM:

```
boarium practice/config.yml hcf/config.yml
```

Each config uses the settings above and has its own listener ports, backend,
MOTD, resource-pack catalogue and acceptance policy. Give routes distinct bind
ports. A TLS edge can route public hostnames to these listener ports, preserving
pack selection before login. Each config keeps its NetherNet identity in a
`nethernet` directory next to it, so put each route's config in its own folder:
two configs in one folder share `nethernet/identity.pem`. Backend transfers still
require compatible login packs; routes do not renegotiate packs during an
in-session transfer. Each route's pack catalogue has its own 64-pack and 256 MiB
limits, per generation, and a route holds two generations during a symlink swap.

Boar starts once using the first config's sibling `boar` directory. All route
configs must agree on `boar_enabled`, and while Boar is on, on `boar_mitigations`
and `boar_alert_limit`; conflicting settings fail before binding. A config, pack
or listener failure at startup closes the routes already started and exits.
NetherNet is the exception while RakNet is on: a route whose NetherNet cannot
start, such as one whose port another route holds, logs it and keeps taking
players over RakNet alone, as a single route does. Shutdown closes all routes at
once. Atomic pack publication updates only that route's new logins and does not
restart any listener.
