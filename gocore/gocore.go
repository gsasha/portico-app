// Package gocore is the entry point gomobile compiles into an .aar for
// the Android app (design doc §9.8), following tsmobile's proven shape
// (§4.3, §6): SIP/RTP audio, embedded tsnet client, video demux.
//
// Always dials SOMETHING when answering (§9.6, mode from
// portico/answer's response): the device itself in `direct` mode, or
// internal/call/relay.go's local endpoint (URI + credential from that
// same response) in `relay` mode — same pkg/sip code either way (once
// the cross-repo dependency on portico-ha's transport module is wired
// up, go.mod), never a transcode/push-session client itself, since
// relay.go owns that whenever it's the one dialing the device.
//
// Only dahua_sip-family drivers ever reach `direct` mode — unifi_protect
// never gives the app a direct-dial shortcut (§9.6, §9.8).
package gocore

// Dial answers a call in the given mode, using the target and
// credential from portico/answer's response.
//
// TODO (Phase 2): import example.org/portico-ha/transport/pkg/sip
// once the cross-repo Go module dependency is set up (go.mod).
func Dial(mode string, host string, port int) error {
	panic("Phase 2: not implemented (§9.6, §9.8)")
}
