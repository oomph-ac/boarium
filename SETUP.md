# Setting up boarium with a Dragonfly server

This guide puts boarium in front of a [Dragonfly](https://github.com/df-mc/dragonfly) server. Players join boarium, and boarium passes them on to Dragonfly. Boar, the anticheat inside boarium, checks the players on the way.

It is written for a person or an AI agent doing the setup. Each step gives the commands to run and what to check. A checklist for agents is at the end.

```
Bedrock players ──NetherNet (TCP + UDP 19132)──▶ boarium ──Spectrum over TCP (127.0.0.1:19133)──▶ Dragonfly
                 ──RakNet (UDP, if turned on)──▶
```

boarium is in early development. Boar only logs what it finds, unless `boar_mitigations` is turned on. So while you try it, Boar cannot kick a player or set one back.

## Before you start

- **JDK 21** to build boarium. The build asks for Java 21 itself, so a newer JDK alone is not enough; have JDK 21 installed. Java 21 or newer can run it.
- **Go 1.26** or newer for the Dragonfly server. `go version` must say 1.26 or newer.
- **git**.
- **Internet access** for boarium. It fetches Mojang's login settings when it starts, and does not start without them. It also needs Mojang's keys while players log in.
- A machine that players can reach on **TCP and UDP port 19132**, or the port you choose. It can run Linux (x86_64 or arm64), macOS or Windows; NetherNet's native library ships for these.

### Which game versions can play

boarium lets in Bedrock clients from 26.0 to 26.60. It passes their packets on in the client's own version, and does not translate between versions. So **a player can only play when the server behind boarium speaks that player's version.** Whether to support one version or several is up to the server.

Dragonfly v0.11.6, used below, speaks 26.60 only. A player on another version gets into boarium, but cannot play: the join fails, or the game drops them.

Boar checks players on 26.20, 26.30, 26.40, 26.45, 26.50 and 26.60. Players on 26.0 and 26.10 are let in, but not checked.

## Step 1: build boarium

```
git clone https://github.com/oomph-ac/boarium.git
cd boarium
git checkout dev
./gradlew :app:installDist
```

The work is on the `dev` branch. On Windows, run `gradlew.bat` in place of `./gradlew`, and `boarium.bat` in place of `boarium` below.

The program is now in `app/build/install/boarium/`. To run it on another machine, build a zip with `./gradlew :app:distZip` instead. Copy `app/build/distributions/boarium-<version>.zip` there and unzip it; that machine only needs Java 21 or newer.

**Check:** `app/build/install/boarium/bin/boarium` exists.

## Step 2: the Dragonfly server

Dragonfly takes its players from boarium through [spectrum-df](https://github.com/oomph-ac/spectrum-df-internal), oomph-ac's fork. The fork's Go module keeps the name `github.com/cooldogedev/spectrum-df`. So a `replace` line points Go at the fork. Without it, Go fetches the original spectrum-df, which does not work with boarium.

Make the server's folder next to the `boarium` folder, not inside it:

```
cd ..
mkdir myserver
cd myserver
go mod init example.com/myserver
go mod edit -replace github.com/cooldogedev/spectrum-df=github.com/oomph-ac/spectrum-df-internal@main
go get github.com/df-mc/dragonfly@v0.11.6
```

The last line pins Dragonfly to v0.11.6, the version spectrum-df is built for, which speaks 26.60.

Create `main.go`:

```go
// A Dragonfly server that takes its players from boarium.
package main

import (
	"io"
	"log/slog"
	"net"
	"os"

	spectrum "github.com/cooldogedev/spectrum-df"
	"github.com/df-mc/dragonfly/server"
	"github.com/df-mc/dragonfly/server/player/chat"
)

// The address that boarium's server setting points at. Keep it off the internet: only boarium should reach it.
const spectrumAddress = "127.0.0.1:19133"

func main() {
	log := slog.New(slog.NewTextHandler(os.Stdout, nil))
	chat.Global.Subscribe(chat.StdoutSubscriber{})

	conf, err := server.DefaultConfig().Config(log)
	if err != nil {
		panic(err)
	}
	// Players come only through boarium, so the server opens no Bedrock listener of its own.
	conf.Listeners = []func(server.Config) (server.Listener, error){
		func(server.Config) (server.Listener, error) {
			return spectrum.NewListener(spectrumAddress, &tcpTransport{})
		},
	}

	srv := conf.New()
	srv.CloseOnProgramEnd()
	srv.Listen()
	for p := range srv.Accept() {
		log.Info("Player joined.", "name", p.Name())
	}
}

// tcpTransport is the transport boarium uses: one TCP connection per player.
type tcpTransport struct {
	listener net.Listener
}

func (t *tcpTransport) Listen(addr string) (err error) {
	t.listener, err = net.Listen("tcp", addr)
	return err
}

func (t *tcpTransport) Accept() (io.ReadWriteCloser, error) {
	return t.listener.Accept()
}

func (t *tcpTransport) Close() error {
	return t.listener.Close()
}
```

Then build and start it:

```
go mod tidy
go build -o myserver .
./myserver
```

On Windows, build `myserver.exe` and run `myserver.exe`. Leave the server running, and use a second terminal for the next steps.

**Check:** a log line says `Dragonfly server started.` with `mc-version=1.26.60`. And TCP `127.0.0.1:19133` is listening: `lsof -nP -iTCP:19133 -sTCP:LISTEN` on macOS, `ss -ltn` on Linux, or `netstat -an` on Windows.

- **An existing Dragonfly server:** add the two `go mod edit` and `go get` lines above to its module. Set `conf.Listeners` as above, in place of its own listeners, and add the `tcpTransport` type to the same package.
- **Keep the Spectrum address private.** The server believes whatever player name and Xbox ID boarium sends it. So anyone who can reach `127.0.0.1:19133` could join as anyone. Keep it on `127.0.0.1` when boarium runs on the same machine, or on a private network that only boarium can reach.
- **To move the server to another address,** change `spectrumAddress` here and `server` in boarium's config (Step 3) together.

## Step 3: configure boarium

Go back to the `boarium` folder, and make a folder there for boarium's own files:

```
cd ../boarium
mkdir run
cd run
```

Then write `config.yml` in it. Keys you leave out take their defaults:

```yaml
listen: ":19132"          # where players connect; an empty host means every address of this machine
server: 127.0.0.1:19133   # the Dragonfly server from Step 2
xbox_auth: true           # players must sign in with Xbox Live
motd: My server           # the name in the server list
boar_mitigations: false   # Boar only logs; turn this on once you trust its results
```

Keep `xbox_auth: true` with Dragonfly. spectrum-df gives each player an entity ID made from their Xbox ID. Players without Xbox Live have none, so they would all get the same ID.

If you start boarium with no `config.yml`, it writes one with every key and its default, and starts on port 19132 at once. Every key, with what it means, is in the [README](README.md#running). boarium refuses a key it does not know, such as a misspelt one, and says which.

### Older clients, and networks where NetherNet does not work

NetherNet is on, and RakNet is off, by default. When a player joins by address, the game tries NetherNet first, then RakNet. To also take players over RakNet, give RakNet its own UDP port:

```yaml
raknet:
  enabled: true
  port: 19134
```

RakNet and NetherNet cannot share a UDP port; boarium does not start if they do.

## Step 4: open the ports

| Port | Protocol | What uses it |
|---|---|---|
| 19132 (`listen`) | **TCP** | NetherNet signaling, where the game asks to join |
| 19132 (`listen`) | **UDP** | NetherNet connections |
| `raknet.port` | UDP | RakNet, only if you turned it on |
| 19133 (`server`) | TCP | Spectrum, between boarium and Dragonfly: **do not open this one** |

Open both TCP and UDP for the listen port in the firewall. If the machine is behind a router, forward both there too.

Behind a router, boarium finds its public address through public STUN servers, Cloudflare's and Google's, by default. If that does not work on your network, list the address that players should use:

```yaml
nethernet:
  advertise_addresses: [play.example.com]
```

## Step 5: start and check

With Dragonfly running, start boarium from its `run` folder:

```
../app/build/install/boarium/bin/boarium config.yml
```

**Check** that boarium's log has these lines. They can come in any order:

- `NetherNet signaling on TCP *:19132 over HTTP, NetherNet on UDP *:19132`
- `NetherNet advertises …`
- `Boar started with its config in …, mitigations off`
- `started, sending players to 127.0.0.1:19133, for Bedrock 26.0, …, 26.60`
- `RakNet on UDP *:19134`, if you turned RakNet on

To stop boarium, press Ctrl+C, or send it SIGTERM. It logs `shutting down`.

## Step 6: join

In Minecraft 26.60, add a server with your machine's address and port 19132, and join.

- The first time, the game asks the player to trust the server, since boarium's NetherNet signaling is plain HTTP. Accept it.
- boarium keeps the key the game remembers in `run/nethernet/identity.pem`. Back it up with `config.yml`; if it is lost, every player is asked again.
- To skip the question, give boarium a trusted certificate under `nethernet.https` (see the README).

**Check:** boarium logs `<name> joined 127.0.0.1:19133`, and Dragonfly logs `Player joined.`

boarium logs each of Boar's flags as one line, such as `Boar > Steve failed Bad Packet (B) x1 … [mitigations off]`. It logs at most one a second for each player and check (`boar_alert_limit`).

## Troubleshooting

| What you see | Why, and what to do |
|---|---|
| A player gets in but cannot play, or drops soon after | Their game version is not the one the server speaks. With Dragonfly v0.11.6, only 26.60 can play. |
| `could not start: …`, and boarium stops | The rest of the line says why: a bad or unknown config key, a port in use, a missing certificate, and so on. |
| `NetherNet could not start …; RakNet is off, so no player could join` | NetherNet's native library or its port failed, and the line names the cause. Turn RakNet on to keep taking players while you fix it. |
| `<name> could not join 127.0.0.1:19133: …ConnectException…` | Dragonfly is not running, or `server` does not match `spectrumAddress`. |
| Players on the same network can join, but players outside cannot | Open and forward both TCP and UDP 19132. Set `nethernet.advertise_addresses` if needed. |
| Players are asked to trust the server after every restart | `run/nethernet/identity.pem` was lost. Keep it with the config. |
| `WARN Send failed, errno=65` and `WARN STUN message send failed`, at start or at a join | Harmless. NetherNet's library logs them when it cannot reach an address, such as over IPv6. |
| `BedrockPalette - StartGame gives components or permutations to vanilla blocks, 98 of them …` at a join | Expected with Dragonfly. Boar uses the vanilla shapes for those blocks. |
| A RakNet player cannot connect, and boarium logs `RakNet turned away …` | RakNet's limits for each IP; players behind one IP share them. See `raknet.*` in the README. |

## Checklist for AI agents

Run these in order. Stop at the first check that fails, and report the exact log line.

1. Check `java -version` and `go version`. JDK 21 must be installed, even if a newer Java is the default. Go must be 1.26 or newer.
2. Run `git clone https://github.com/oomph-ac/boarium.git && cd boarium && git checkout dev && ./gradlew :app:installDist`. Check that `app/build/install/boarium/bin/boarium` exists.
3. Next to the `boarium` folder, not inside it, make `myserver`. In it run `go mod init example.com/myserver`, `go mod edit -replace github.com/cooldogedev/spectrum-df=github.com/oomph-ac/spectrum-df-internal@main` and `go get github.com/df-mc/dragonfly@v0.11.6`. Write `main.go` from Step 2 exactly. Then run `go mod tidy` and `go build -o myserver .`.
4. Start `./myserver` in the background and keep its log. Check the log says `Dragonfly server started.` and that TCP `127.0.0.1:19133` listens. Never expose that port publicly.
5. In `boarium/run`, write `config.yml` from Step 3, with `motd` and anything else the owner asked for. Keep `server: 127.0.0.1:19133`, `xbox_auth: true` and `boar_mitigations: false`.
6. Ask the owner to open and forward TCP and UDP 19132. You cannot check from the inside that players outside can reach it.
7. Start boarium in the background from `boarium/run` and keep its log. Check the log for `NetherNet signaling on TCP`, `Boar started` and `started, sending players to 127.0.0.1:19133`. To stop it later, send SIGTERM (`kill <pid>`); a background job ignores Ctrl+C.
8. Tell the owner to join with a 26.60 client on port 19132 and accept the trust prompt, and to back up `run/config.yml` and `run/nethernet/identity.pem`.
