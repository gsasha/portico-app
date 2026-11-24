// Package tsnet embeds the tailnet identity gocore dials the device's
// LAN IP through (design doc §4.8) — the same tsmobile-style gomobile
// build (a full userspace WireGuard client), no separate Tailscale app
// install needed on the phone. The single-use auth key is minted by HA's
// enrollment flow and arrives in the enrollment QR (`tailscale_authkey`),
// not over portico/devices as an earlier draft of this comment said. The
// gomobile-bound surface the app calls is gocore/tailnet (DEV-557).
package tsnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	tsnetlib "tailscale.com/tsnet"
)

// ErrNotStarted is returned by Dial (and Stop) when called on a Client
// that never completed Start successfully — a distinct failure mode from
// "tailnet up but this address unreachable" (§4.8), since a future UI
// layer needs to tell those two apart.
var ErrNotStarted = errors.New("tsnet: client not started (call Start first)")

// Config configures one tailnet identity for this app instance.
type Config struct {
	// StateDir is where tsnet persists its node identity and keys
	// between runs. Must be a directory this app process can create and
	// write to (typically the app's private storage on Android).
	StateDir string

	// Hostname is this node's name on the tailnet.
	Hostname string

	// AuthKey is the pre-authorized key minted server-side by the HA
	// integration's enrollment flow and handed to the app via the QR
	// code it scans (§4.7) — never hardcoded, always supplied at
	// runtime by the caller.
	AuthKey string

	// ControlURL overrides the coordination server. Empty means the
	// default (real) Tailscale control plane. Only ever non-empty in a
	// test or a self-hosted-Headscale install; production Portico
	// installs leave it empty (§9.9 assumes the real coordination
	// server).
	ControlURL string

	// ReuseIdentity allows an empty AuthKey: the node authenticates with
	// the node key already stored in StateDir (a phone that joined before).
	// Without a stored identity tsnet then waits for an interactive login,
	// which the caller must treat as a failure (gocore/tailnet does).
	ReuseIdentity bool

	// Logf receives tsnet's own log lines, which are very chatty by
	// default. Optional — nil discards them.
	Logf func(format string, args ...any)

	// OnState, if set, is called about twice a second while Start waits
	// for the node to come up, with the backend state ("NeedsLogin",
	// "NeedsMachineAuth", "Starting", "Running"...) and the interactive
	// login URL when tsnet wants one (empty otherwise). It runs on its own
	// goroutine; a panic in it is contained. gocore/tailnet uses it to tell
	// "this key/identity is dead" and "waiting for an admin's approval"
	// apart from a plain timeout (DEV-557).
	OnState func(backendState, authURL string)

	// OnPhase, if set, is called as the node moves through its start (DEV-694): the
	// first time each backend state is seen ("starting", "running", ...) and the first
	// time a netmap arrives ("netmap"), with the milliseconds since Start began. It is
	// driven by the IPN bus, not a poll, so the times are real. It runs on its own
	// goroutine; a panic in it is contained. Measurement only: it changes nothing.
	OnPhase func(phase string, afterMs int64)
}

func (c Config) validate() error {
	if c.AuthKey == "" && !c.ReuseIdentity {
		return errors.New("tsnet: Config.AuthKey is required (minted by the HA enrollment flow, §4.7 — never hardcode one)")
	}
	if c.StateDir == "" {
		return errors.New("tsnet: Config.StateDir is required")
	}
	if c.Hostname == "" {
		return errors.New("tsnet: Config.Hostname is required")
	}
	return nil
}

// Client wraps a tsnet.Server, one running tailnet identity that the rest
// of gocore (and eventually, via gomobile, the Kotlin signaling/call code)
// dials the device's LAN IP through, instead of dialing it directly.
type Client struct {
	srv  *tsnetlib.Server
	ipv4 string
}

// IPv4 is the node's tailnet IPv4 address ("" if it has none).
func (c *Client) IPv4() string {
	if c == nil {
		return ""
	}
	return c.ipv4
}

// Start validates cfg, creates the state directory if needed, and brings
// up a tsnet-backed tailnet connection authenticated with cfg.AuthKey.
//
// A non-nil error here always means the tailnet never came up — a bad or
// already-consumed auth key, no network at all, or the coordination
// server being unreachable are all folded into this one failure mode,
// wrapped with that context rather than tsnet's own bare error text, so a
// future UI layer can tell it apart from Dial's "tailnet is fine, this
// address specifically isn't reachable" failure (§4.8).
//
// Important, verified behavior callers must not assume away: tsnet does
// NOT fail fast when the coordination server is unreachable. It treats
// that as transient (the same as a phone briefly losing signal) and
// keeps retrying for as long as ctx allows. That means ctx MUST carry a
// deadline (or be cancelled some other way by the caller) — passing
// context.Background() here can block indefinitely with no way to
// recover control. A bad/expired auth key, by contrast, is rejected by
// the control server quickly. Both end up as the same wrapped error
// text; only the timing differs.
func Start(ctx context.Context, cfg Config) (*Client, error) {
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		return nil, fmt.Errorf("tsnet: creating state dir %q: %w", cfg.StateDir, err)
	}

	srv := &tsnetlib.Server{
		Dir:        cfg.StateDir,
		Hostname:   cfg.Hostname,
		AuthKey:    cfg.AuthKey,
		ControlURL: cfg.ControlURL,
		Ephemeral:  false, // this is the phone's persistent identity, not a throwaway node
	}
	if cfg.Logf != nil {
		srv.Logf = cfg.Logf
		// UserLogf too: tsnet prints the interactive-login URL there, and
		// with UserLogf nil it goes to log.Printf (stderr) instead.
		srv.UserLogf = cfg.Logf
	} else {
		srv.Logf = func(string, ...any) {}
		srv.UserLogf = func(string, ...any) {}
	}

	upCtx, stopWatch := context.WithCancel(ctx)
	defer stopWatch()
	if cfg.OnState != nil {
		go watchState(upCtx, srv, cfg.OnState)
	}
	if cfg.OnPhase != nil {
		go watchPhases(upCtx, srv, time.Now(), cfg.OnPhase)
	}

	st, err := srv.Up(upCtx)
	stopWatch() // before any Close: the watcher must not outlive the attempt
	if err != nil {
		srv.Close()
		return nil, fmt.Errorf("tsnet: tailnet connection failed to come up (bad/expired auth key, no network, or the coordination server is unreachable — the tailnet itself is not up, distinct from a single address being unreachable): %w", err)
	}

	c := &Client{srv: srv}
	for _, a := range st.TailscaleIPs {
		if a.Is4() {
			c.ipv4 = a.String()
			break
		}
	}
	return c, nil
}

// watchState polls the node's backend state for Config.OnState until ctx
// ends. Best effort: an error from the local API just skips a beat.
func watchState(ctx context.Context, srv *tsnetlib.Server, onState func(string, string)) {
	defer func() { _ = recover() }() // a callback panic must not kill the process (no Java exception on Android)
	if err := srv.Start(); err != nil {
		return
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return
	}
	t := time.NewTicker(500 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			st, err := lc.StatusWithoutPeers(ctx)
			if err != nil || st == nil {
				continue
			}
			onState(st.BackendState, st.AuthURL)
		}
	}
}

// watchPhases reports the node's start to Config.OnPhase from the IPN bus (DEV-694):
// each backend state the first time it is seen, and the first netmap. Best effort:
// any error just ends the watch.
func watchPhases(ctx context.Context, srv *tsnetlib.Server, t0 time.Time, onPhase func(string, int64)) {
	defer func() { _ = recover() }()
	lc, err := srv.LocalClient() // starts the server, as Up does
	if err != nil {
		return
	}
	w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyInitialNetMap)
	if err != nil {
		return
	}
	defer w.Close()
	seen := map[string]bool{}
	for {
		n, err := w.Next()
		if err != nil {
			return
		}
		if n.State != nil {
			if name := strings.ToLower(n.State.String()); !seen[name] {
				seen[name] = true
				onPhase(name, time.Since(t0).Milliseconds())
			}
		}
		if n.NetMap != nil && !seen["netmap"] {
			seen["netmap"] = true
			onPhase("netmap", time.Since(t0).Milliseconds())
		}
		if seen["running"] && seen["netmap"] {
			return
		}
	}
}

// Dial reaches addr (host:port — typically a device's LAN IP behind the
// estate's subnet router, §4.8) through the tailnet.
//
// Per §4.8/§9.9, the door station's LAN is only reachable at all once an
// installer has advertised the matching subnet route elsewhere on the
// tailnet — this package never creates that route, it only dials through
// whatever routes already exist. A failure here, with the tailnet
// otherwise up, most often means that specific route (or an ACL) isn't in
// place, which is why the wrapped error says so explicitly instead of
// just propagating tsnet's own dial error text.
func (c *Client) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	if c == nil || c.srv == nil {
		return nil, ErrNotStarted
	}
	conn, err := c.srv.Dial(ctx, network, addr)
	if err != nil {
		return nil, fmt.Errorf("tsnet: dial %s %s failed — tailnet is up, but this address is unreachable (likely a missing subnet route to the device's LAN, or an ACL denying tag:intercom-phone, §4.8/§9.9 — check those before assuming the device itself is down): %w", network, addr, err)
	}
	return conn, nil
}

// LinkChanged tells the node the phone's network changed (DEV-705). Android 11+ gives Go no netlink,
// so tailscale's network monitor only learns of a change when it next re-reads the interface table
// the app supplies, on its own schedule: measured on a phone, a node that kept its old sockets
// after Wi-Fi to mobile data sent into the void for 20 to 55 s. InjectEvent makes the monitor
// re-check now, which rebinds the UDP socket and probes again. Call it after the interface table
// is updated. Safe on a nil or stopped client.
func (c *Client) LinkChanged() {
	if c == nil || c.srv == nil {
		return
	}
	defer func() { _ = recover() }() // a monitor that is not there yet is not worth a crash
	if mon, ok := c.srv.Sys().NetMon.GetOK(); ok && mon != nil {
		mon.InjectEvent()
	}
}

// Status is the node's own view of the tailnet (tsnet's LocalClient().Status,
// peers included).
func (c *Client) Status(ctx context.Context) (*ipnstate.Status, error) {
	if c == nil || c.srv == nil {
		return nil, ErrNotStarted
	}
	lc, err := c.srv.LocalClient()
	if err != nil {
		return nil, err
	}
	return lc.Status(ctx)
}

// PeerRoutes is what one peer offers to route, as this node's netmap has it.
// PrimaryRoutes are the subnet routes the control plane made this peer the
// primary router for (approved and advertised); AllowedIPs are every prefix
// this node would send to the peer (its own addresses plus accepted routes).
type PeerRoutes struct {
	Name          string
	PrimaryRoutes []netip.Prefix
	AllowedIPs    []netip.Prefix
	TailscaleIPs  []netip.Addr
}

// Peers lists every peer's routes. running is false until the backend is
// Running (before that the netmap, and so the peer list, is not known).
func (c *Client) Peers(ctx context.Context) (peers []PeerRoutes, running bool, err error) {
	st, err := c.Status(ctx)
	if err != nil {
		return nil, false, err
	}
	if st.BackendState != "Running" {
		return nil, false, nil
	}
	for _, p := range st.Peer {
		if p == nil {
			continue
		}
		pr := PeerRoutes{Name: p.HostName, TailscaleIPs: p.TailscaleIPs}
		if pr.Name == "" {
			pr.Name, _, _ = strings.Cut(p.DNSName, ".")
		}
		if p.PrimaryRoutes != nil {
			pr.PrimaryRoutes = p.PrimaryRoutes.AsSlice()
		}
		if p.AllowedIPs != nil {
			pr.AllowedIPs = p.AllowedIPs.AsSlice()
		}
		peers = append(peers, pr)
	}
	return peers, true, nil
}

// Listen accepts connections from the tailnet on addr (":port"). The phone
// never listens; it exists for tests that stand up a peer to dial.
func (c *Client) Listen(network, addr string) (net.Listener, error) {
	if c == nil || c.srv == nil {
		return nil, ErrNotStarted
	}
	return c.srv.Listen(network, addr)
}

// Stop tears down the tailnet connection and releases all state. Safe to
// call on a nil-underlying or never-started Client, and safe to call more
// than once.
func (c *Client) Stop() error {
	if c == nil || c.srv == nil {
		return nil
	}
	return c.srv.Close()
}
