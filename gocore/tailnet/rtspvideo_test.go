package tailnet

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gsasha/portico-app/gocore/tsnet"
)

type stateSink struct {
	mu     sync.Mutex
	states []string
}

func (s *stateSink) OnFormat(sps, pps []byte, w, h int)      {}
func (s *stateSink) OnNal(nal []byte, ptsUs int64, key bool) {}
func (s *stateSink) OnState(st string) {
	s.mu.Lock()
	s.states = append(s.states, st)
	s.mu.Unlock()
}
func (s *stateSink) has(want string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, x := range s.states {
		if x == want {
			return true
		}
	}
	return false
}

func TestStartRtspVideoNeedsTheNode(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return nil, errors.New("never") })
	v, err := StartRtspVideo("rtsp://192.168.1.6:8554/gate_intercom", &stateSink{})
	if err == nil || v != nil {
		t.Fatalf("a pull started with no node up: %v %v", v, err)
	}
	if strings.Contains(err.Error(), "192.168.1.6") {
		t.Errorf("the error names the address: %v", err)
	}
	var nilVideo *RtspVideo
	nilVideo.Stop() // never started
	if nilVideo.Stats() == "" {
		t.Error("no stats text for a nil pull")
	}
}

// The pull dials "tcp" through the NODE (not the forwarder) to the address's own host and port.
func TestStartRtspVideoDialsTheNodeNotTheForwarder(t *testing.T) {
	type call struct{ network, addr string }
	calls := make(chan call, 4)
	n := &fakeNode{ip: "100.64.0.1", dial: func(ctx context.Context, nw, a string) (net.Conn, error) {
		calls <- call{nw, a}
		return nil, errors.New("no route")
	}}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return n, nil })
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	sink := &stateSink{}
	v, err := StartRtspVideo("rtsp://192.168.1.6:8554/gate_intercom_sub", sink)
	if err != nil {
		t.Fatal(err)
	}
	defer v.Stop()
	select {
	case c := <-calls:
		if c.network != "tcp" || c.addr != "192.168.1.6:8554" {
			t.Fatalf("the node was asked for %+v", c)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the node was never dialled")
	}
	deadline := time.Now().Add(3 * time.Second)
	for !sink.has("ended:dial") && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !sink.has("ended:dial") {
		t.Fatalf("a failed dial is not reported: %v", sink.states)
	}
	for _, st := range sink.states {
		if strings.Contains(st, "192.168.1.6") || strings.Contains(st, "no route") {
			t.Errorf("a state names the host or the error: %q", st)
		}
	}
	v.Stop()
	v.Stop()
	if fwd != nil {
		t.Error("a pull started the forwarder")
	}
	if _, err := StartRtspVideo("http://192.168.1.6:8554/x", sink); err == nil {
		t.Error("a non-rtsp address was accepted")
	}
	if _, err := StartRtspVideo("rtsp://192.168.1.6:8554/x", nil); err == nil {
		t.Error("a nil sink was accepted")
	}
}
