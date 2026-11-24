package tailnet

import (
	"errors"

	"github.com/gsasha/portico-app/gocore/internal/rtsp"
)

// Live video AWAY from home (DEV-713 S1b): an RTSP/H.264 client that dials go2rtc
// THROUGH THE TAILNET NODE and hands NAL units to a Kotlin sink for MediaCodec. The
// client itself is gocore/internal/rtsp (stdlib only, tested on its own); this file
// is its gomobile surface and the one place it meets the node.
//
// Why not the forwarder (StartForwarder): the forwarder bridges ONE fixed target,
// token first, for OkHttp's websocket to Home Assistant. The video goes to a different
// host and port (go2rtc, 192.168.1.6:8554), so it takes the node's own Dial("tcp", ...),
// exactly as the old intercom app's StartVideo did. Needs the Tailscale grant
// tag:portico-phone -> 192.168.1.6:tcp:8554.
//
// Every argument and result is a string, an int, a []byte, an error or one of the two
// types below, the shapes gomobile can carry.

// VideoSink is implemented in Kotlin (a gomobile interface). Its methods are called from
// one Go goroutine, in order, and never after Stop returned (a call in flight may finish).
type VideoSink interface {
	// OnFormat delivers the parameter sets (Annex-B, start code included) and the picture
	// size before any picture, and again when the camera changes them.
	OnFormat(sps, pps []byte, width, height int)
	// OnNal delivers one Annex-B NAL unit; key is an IDR slice. Pictures start at an IDR.
	OnNal(nal []byte, ptsUs int64, key bool)
	// OnState: "connecting", "live", and once "ended:<kind>" (dial, rtsp, protocol, stalled,
	// lost) when the connection ends on its own. Never the address, a host or a credential.
	OnState(state string)
}

// RtspVideo is one pull of one camera stream: one connection attempt. When it ends on its
// own it says why through OnState and stays ended; the caller decides about a retry.
type RtspVideo struct {
	s *rtsp.Session
}

// StartRtspVideo begins pulling url (rtsp://host[:port]/name) in the background and returns
// at once. The error is returned when nothing was started: the node is not up, or the
// address is unusable (the message never contains it). The Kotlin side has already allowed
// only rtsp://host[:port]/name with no credentials.
func StartRtspVideo(url string, sink VideoSink) (v *RtspVideo, err error) {
	defer containPanic(&err)
	if sink == nil {
		return nil, errors.New("tailnet: no video sink")
	}
	mu.Lock()
	n, up := cur, state == stateUp
	mu.Unlock()
	if !up || n == nil {
		return nil, errors.New("tailnet: the node is not up")
	}
	s, err := rtsp.Start(url, n.Dial, sink, rtsp.DefaultConfig())
	if err != nil {
		return nil, err
	}
	return &RtspVideo{s: s}, nil
}

// Stop tells go2rtc, closes the connection and ends every goroutine of the pull. Idempotent,
// safe from any thread, and it does not wait for the sink.
func (v *RtspVideo) Stop() {
	if v != nil {
		v.s.Stop()
	}
}

// Stats is "N units, K KB, D dropped, R resyncs": counts only.
func (v *RtspVideo) Stats() string {
	if v == nil {
		return "0 units"
	}
	return v.s.Stats()
}
