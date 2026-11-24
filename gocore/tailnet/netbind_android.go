//go:build android

// Ported from the intercom app's tsmobile/netbind.go (DEV-557), where it fixed
// three source-address failures measured on a hostile Wi-Fi on 2026-09-15.
// The Java side (GoTailnetEngine) calls SetNetwork with the active network's
// handle on every network change.

// ⚠️ ANDROID ONLY, and the build tag is load-bearing: this file includes an NDK
// header, so without it `go vet` and `go test` on a desktop fail to compile the
// whole package — which is exactly what 0.35.0 did to the host-side test suite
// (meter, rtspvideo, sipaudio, gatecall) until it was caught by running them.
// gomobile builds with GOOS=android, so the tag costs the real build nothing.

package tailnet

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
*/
import "C"

import (
	"fmt"
	"sync/atomic"

	"golang.org/x/sys/unix"
	"tailscale.com/net/netns"
)

// 🚦 EVERY SOCKET tsnet OPENS IS MARKED TO ONE CHOSEN NETWORK, AND ASKS FOR A
// STABLE IPv6 SOURCE ADDRESS.
//
// ⛔ THE THREE FAILURES THIS ANSWERS, all measured on one hostile Wi-Fi on
// 2026-09-15 and all of them source-address problems rather than firewall
// problems (design register §12):
//
//	K1  derp.Recv: … software caused connection abort
//	    ECONNABORTED is Android's netd DESTROYING every TCP socket bound to an
//	    IPv6 address at the moment that address is removed. The phone's IPv6
//	    privacy address rotated every few seconds on that network, so the relay
//	    connection and the control-plane connection were executed over and over.
//	    No middlebox was involved, which is what the first three diagnoses of
//	    this session all assumed.
//
//	K2  derp.Recv: read tcp4 192.168.2.145:… i/o timeout
//	    Go issues raw socket() syscalls, so NOTHING TAGS ITS SOCKETS WITH A
//	    netId — they follow whatever Android calls the default network at each
//	    routing decision. That socket was opened while cellular was default, the
//	    Wi-Fi then validated and took over, cellular was torn down, and the
//	    socket sat on a dead path until it timed out.
//
//	K3  wg: Sending handshake initiation / Received handshake initiation /
//	    Handshake did not complete after 5 seconds
//	    magicsock's UDP socket is bound to [::], so EACH DATAGRAM picks the
//	    current preferred source — the rotating temporary address, or the CLAT
//	    address, depending on the default of the moment. The estate replied to
//	    an address that had already moved. Both ends initiating and neither
//	    completing is the signature.
//
// ⭐ WHY THIS HOOK AND NOT bindProcessToNetwork(). The obvious Java answer,
// `ConnectivityManager.bindProcessToNetwork()`, would be WORSE THAN NOTHING
// here: it works through bionic's socket wrappers, which Go's raw syscalls
// bypass — but it DOES capture getaddrinfo, so DNS would resolve on one network
// while the connection left by another. tailscale ships the correct hook
// instead: `netns.SetAndroidBindToNetworkFunc`, invoked from netns's `controlC`
// — the Control func on EVERY dialer and listener netns creates, which is
// magicsock's UDP socket, derphttp's TCP, the control client and netcheck's
// STUN. Verified in the vendored tailscale.com v1.102.3 before this was
// written.
//
// ⚠️ This file is the ONLY place in the app that uses cgo. It costs nothing at
// build time (gomobile already compiles cgo for its own JNI bridge) and the two
// calls it makes are both NDK/POSIX, present since API 23 against this build's
// API 24 floor.

// The net_handle_t of the network every new socket should be marked to, or 0
// for "leave it to Android", which is the behaviour every build before this one
// had. Written from Java, read on tsnet's own goroutines.
var netHandle atomic.Uint64

// SetNetwork receives `Network.getNetworkHandle()` from the Java side.
//
// ⚠️ It does NOT re-mark sockets that already exist — it cannot; a mark is set
// between socket() and connect(). Sockets on a network that has gone away fail
// FAST once marked (ENETUNREACH) instead of timing out, which is the whole
// point: tsnet reconnects on error, and the new sockets get the new mark.
//
// ⚠️ 0 disables marking. Pass it when the phone's network cannot be determined,
// never as a way of saying "any network" — "any" is exactly what K2 is.
func SetNetwork(handle int64) {
	old := netHandle.Swap(uint64(handle))
	if old != uint64(handle) {
		logLine("netbind: sockets now marked to network handle %d (was %d)", handle, old)
	}
}

// RFC 5014 source-address selection. ⚠️ Spelled out rather than taken from
// x/sys/unix: IPV6_ADDR_PREFERENCES is not exported by every version of that
// package, and a build that silently compiled without this would look identical
// to one with it — right up until the address rotated.
const (
	ipv6AddrPreferences = 72     // IPV6_ADDR_PREFERENCES
	ipv6PreferSrcPublic = 0x0002 // IPV6_PREFER_SRC_PUBLIC
)

func init() {
	netns.SetAndroidBindToNetworkFunc(bindSocket)
}

// bindSocket runs between socket() and connect(), on tsnet's own goroutines,
// for every socket it opens.
//
// ⚠️ It must be FAST and it must not panic: it is on the path of every dial the
// node makes, and a panic here would take the process down from a goroutine no
// Java catch can see.
func bindSocket(fd int) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("netbind panic: %v", r)
		}
	}()

	// ⭐ FIRST, and regardless of whether a network was chosen: ask the kernel
	// for the STABLE source address on IPv6 sockets. This is the cheapest line
	// in the file and the one that answers K1 and K3 — the kernel stops
	// choosing the temporary address that Android re-mints every few seconds,
	// so netd stops destroying our connections and WireGuard's replies keep
	// arriving where they were sent from.
	//
	// ⚠️ Best effort, deliberately. A kernel that refuses the option leaves us
	// exactly where every previous build was; failing the dial over it would
	// turn a degradation into an outage.
	if dom, derr := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_DOMAIN); derr == nil {
		if dom == unix.AF_INET6 {
			if serr := unix.SetsockoptInt(fd, unix.IPPROTO_IPV6,
				ipv6AddrPreferences, ipv6PreferSrcPublic); serr != nil {
				logLine("netbind: IPV6_PREFER_SRC_PUBLIC refused: %v", serr)
			}
		}
	}

	h := netHandle.Load()
	if h == 0 {
		return nil // no chosen network: behave exactly as before
	}
	if rc, cerr := C.android_setsocknetwork(C.net_handle_t(h), C.int(fd)); rc != 0 {
		// ⚠️ REPORTED, not swallowed. netns turns this into a failed dial, and a
		// dial that fails loudly is worth more than one that quietly leaves over
		// the wrong radio — that is K2, and it cost an afternoon.
		return fmt.Errorf("android_setsocknetwork(handle=%d, fd=%d): %v", h, fd, cerr)
	}
	return nil
}
