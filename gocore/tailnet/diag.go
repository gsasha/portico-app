package tailnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gsasha/portico-app/gocore/tsnet"
)

// Diagnostics for Setup: why the WebSocket through the tailnet does not open.
//
// The forwarder is the only place that sees the tailnet dial fail. When it
// does, it just closes the local connection, so OkHttp on the Kotlin side sees
// a bare end of stream during the TLS handshake and cannot tell a policy drop
// from a missing route or a refused port. ForwarderStatus hands the Kotlin
// side the last dial's outcome, classified; :core's LinkFailures maps it to a
// Setup line. RouteCheck answers "does any peer route to the dial IP at all".

// Dial outcome kinds, the first field of ForwarderStatus.
const (
	DialOK          = "ok"           // the tailnet dial connected
	DialNoSuchHost  = "no_such_host" // the target is a name that did not resolve (no ha_dial in the QR)
	DialTimeout     = "timeout"      // no answer: a policy rule drops it, or no peer routes it
	DialRefused     = "refused"      // the target answered with a reset: nothing listens on that port
	DialUnreachable = "unreachable"  // no route to host / network unreachable
	DialToken       = "token"        // a local connection without the right token was refused
	DialError       = "error"        // anything else
)

const dialDetailMax = 160

var (
	dialMu     sync.Mutex
	dialKind   string
	dialAt     time.Time
	dialDetail string
	// tokenAt is the last refused local connection. Kept apart from the dial
	// outcome: any app on the phone can reach 127.0.0.1, and its refusals must
	// not overwrite the last real dial failure.
	tokenAt time.Time
)

// classifyDialErr maps a tailnet dial error to a Dial* kind. A name lookup
// comes first: a DNS timeout is still a name problem, not a route problem.
func classifyDialErr(err error) string {
	if err == nil {
		return DialOK
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return DialNoSuchHost
	}
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "no such host"):
		return DialNoSuchHost
	case errors.Is(err, syscall.ECONNREFUSED), strings.Contains(msg, "refused"):
		// gVisor (netstack) says "connection was refused", the OS "connection refused".
		return DialRefused
	case errors.Is(err, syscall.EHOSTUNREACH), errors.Is(err, syscall.ENETUNREACH),
		strings.Contains(msg, "no route to host"), strings.Contains(msg, "unreachable"):
		return DialUnreachable
	case errors.Is(err, context.DeadlineExceeded), strings.Contains(msg, "i/o timeout"),
		strings.Contains(msg, "timed out"):
		return DialTimeout
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return DialTimeout
	}
	return DialError
}

// recordDial keeps the forwarder's latest outcome. The detail is the
// innermost cause only (tsnet's wrapper is a paragraph of prose), on one line,
// short, with the forwarder's token masked should it ever appear.
func recordDial(kind string, err error, tokenHex string) {
	if kind == DialToken {
		dialMu.Lock()
		tokenAt = time.Now()
		dialMu.Unlock()
		return
	}
	detail := ""
	if err != nil {
		inner := err
		for {
			u := errors.Unwrap(inner)
			if u == nil {
				break
			}
			inner = u
		}
		detail = inner.Error()
		if tokenHex != "" {
			detail = strings.ReplaceAll(detail, tokenHex, "<token>")
		}
		detail = redactKey(detail, "")
		detail = strings.Join(strings.Fields(detail), " ")
		if len(detail) > dialDetailMax {
			detail = detail[:dialDetailMax] + "…"
		}
	}
	dialMu.Lock()
	dialKind, dialAt, dialDetail = kind, time.Now(), detail
	dialMu.Unlock()
}

// resetDial forgets the last outcome (tests).
func resetDial() {
	dialMu.Lock()
	dialKind, dialAt, dialDetail = "", time.Time{}, ""
	tokenAt = time.Time{}
	dialMu.Unlock()
}

// ForwarderStatus is the forwarder's last dial through the tailnet, one line:
//
//	none                                  (nothing dialled or refused since the process started)
//	<kind>\t<unix ms>                     (kind ok)
//	<kind>\t<unix ms>\t<short cause>      (a failure)
//
// A refused local connection (kind token) is reported only while no real dial
// outcome exists; afterwards it never hides the last dial. :core's ForwarderDial parses it. Never contains the token or a key.
func ForwarderStatus() string {
	dialMu.Lock()
	defer dialMu.Unlock()
	if dialKind == "" {
		if !tokenAt.IsZero() {
			return fmt.Sprintf("%s\t%d", DialToken, tokenAt.UnixMilli())
		}
		return "none"
	}
	s := fmt.Sprintf("%s\t%d", dialKind, dialAt.UnixMilli())
	if dialDetail != "" {
		s += "\t" + strings.ReplaceAll(dialDetail, "\t", " ")
	}
	return s
}

// RouteCheck tells whether any tailnet peer offers a route to target's IP:
//
//	via\t<peer name>\t<prefix>   (the longest matching prefix and its peer)
//	none\t<ip>\t<peers seen>
//	unknown                      (the node is not up, or its peers are not known yet)
//	not_ip                       (target's host is a name, or target is malformed)
//
// It reads the node's netmap (tsnet LocalClient().Status(): each peer's
// PrimaryRoutes, AllowedIPs and TailscaleIPs) and never dials anything.
func RouteCheck(target string) (res string) {
	defer func() {
		if r := recover(); r != nil {
			logLine("PANIC contained in RouteCheck: %v", r)
			res = "unknown"
		}
	}()
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return "not_ip"
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return "not_ip"
	}
	mu.Lock()
	n := cur
	up := state == stateUp
	mu.Unlock()
	if !up || n == nil {
		return "unknown"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	peers, running, err := n.Peers(ctx)
	if err != nil {
		return "unknown"
	}
	return routeVerdict(ip.Unmap(), peers, running)
}

// routeVerdict is RouteCheck's pure part. An exit node's 0.0.0.0/0 or ::/0 is
// not counted: the app never selects an exit node, so it routes nothing here.
func routeVerdict(ip netip.Addr, peers []tsnet.PeerRoutes, running bool) string {
	if !running {
		return "unknown"
	}
	best := netip.Prefix{}
	bestPeer := ""
	consider := func(p netip.Prefix, name string) {
		if !p.IsValid() || p.Bits() == 0 || !p.Contains(ip) {
			return
		}
		if !best.IsValid() || p.Bits() > best.Bits() {
			best, bestPeer = p, name
		}
	}
	for _, p := range peers {
		name := strings.Join(strings.Fields(p.Name), " ")
		if name == "" {
			name = "(unnamed peer)"
		}
		for _, a := range p.TailscaleIPs {
			consider(netip.PrefixFrom(a, a.BitLen()), name)
		}
		for _, r := range p.PrimaryRoutes {
			consider(r, name)
		}
		for _, r := range p.AllowedIPs {
			consider(r, name)
		}
	}
	if best.IsValid() {
		return "via\t" + bestPeer + "\t" + best.String()
	}
	return fmt.Sprintf("none\t%s\t%d", ip, len(peers))
}
