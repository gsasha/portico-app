// Ported unchanged in substance from the intercom app's tsmobile/interfaces.go
// (proven on Android 11+ phones since 2026-09-07).

package tailnet

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync/atomic"

	"tailscale.com/net/netmon"
)

// ⭐ Android SDK 30+ forbids Go's net.Interfaces().
//
// Go enumerates interfaces with a NETLINK_ROUTE dump, which Android 11 blocked
// for apps. The failure surfaces as the literal string "permission denied" out
// of anything that touches the network monitor — which for tsnet is startup.
// tailscale.com/net/netmon says so itself: "Android SDK 30 no longer permits
// Go's net.Interfaces to work (Issue 2293); this wrapper lets us the Android
// app register an alternate implementation."
//
// Java's NetworkInterface DOES work, because the framework answers it through
// a permitted path. So the app enumerates there and pushes the result here.
// This is not a workaround of ours — it is the mechanism Tailscale's own
// Android client uses, and the only supported one.

var ifaceLines atomic.Value // string

func init() {
	netmon.RegisterInterfaceGetter(func() (ifs []netmon.Interface, err error) {
		// ⚠️ This runs on tsnet's OWN goroutines, on its own schedule. A panic
		// here is not catchable by anything in Java and would kill the process
		// at an arbitrary moment — so it is contained at the boundary.
		defer func() {
			if r := recover(); r != nil {
				ifs, err = nil, fmt.Errorf("panic parsing the interface table: %v", r)
			}
		}()
		s, _ := ifaceLines.Load().(string)
		return parseInterfaces(s)
	})
}

// SetInterfaces receives the interface table enumerated on the Java side. One
// interface per line, tab-separated:
//
//	name \t index \t mtu \t flags \t ip/prefix,ip/prefix
//
// flags are Go's net.Flags bits (1 up, 2 broadcast, 4 loopback, 8 p2p,
// 16 multicast, 32 running), which the Java side composes.
//
// Call it before Connect and again whenever the network changes — netmon
// re-queries on its own schedule, and a stale table means tsnet keeps trying
// to reach DERP over an interface that has gone away.
func SetInterfaces(table string) { ifaceLines.Store(table) }

func parseInterfaces(s string) ([]netmon.Interface, error) {
	if strings.TrimSpace(s) == "" {
		// Deliberately an error, not an empty list: an empty list reads as
		// "this machine has no network", which sends tsnet down a much more
		// confusing path than an honest failure here.
		return nil, errors.New("no interface table supplied from the Java side " +
			"(call SetInterfaces first: Go cannot enumerate interfaces on Android 11+)")
	}
	var out []netmon.Interface
	for _, line := range strings.Split(strings.TrimSpace(s), "\n") {
		f := strings.Split(strings.TrimSpace(line), "\t")
		if len(f) < 4 {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		fl, _ := strconv.Atoi(f[3])
		ni := &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: net.Flags(fl)}

		// AltAddrs must be populated, not left nil: netmon.Interface.Addrs()
		// falls through to net.Interface.Addrs() when it is, which is the same
		// blocked syscall by another name.
		var addrs []net.Addr
		if len(f) >= 5 {
			for _, a := range strings.Split(f[4], ",") {
				if a = strings.TrimSpace(a); a == "" {
					continue
				}
				ip, ipnet, err := net.ParseCIDR(a)
				if err != nil {
					continue
				}
				// ParseCIDR's second return is the MASKED network; the address
				// list wants the interface's own IP with that mask.
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
			}
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	if len(out) == 0 {
		return nil, errors.New("interface table supplied but nothing parsed from it")
	}
	return out, nil
}
