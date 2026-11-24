package rtsp

import (
	"encoding/base64"
	"encoding/binary"
	"strings"
)

// The H.264 half of the RTSP client: RTP depacketization (RFC 6184), the SDP's
// parameter sets, and the picture size out of an SPS. Ported from the old
// intercom app's tsmobile/rtspvideo.go (proven on a phone since 0.30.1-alpha,
// measured 25 fps at 1280x720), with one addition: a cap on the size of an
// assembled NAL unit.

// MaxNAL is the largest NAL unit the client will assemble. A 1080p IDR is far
// smaller; anything bigger is a damaged stream, and the Kotlin side's queue has
// the same cap.
const MaxNAL = 1 << 20

// NAL unit types the client cares about.
const (
	nalSlice = 1
	nalIDR   = 5
	nalSPS   = 7
	nalPPS   = 8
)

func nalType(n []byte) byte { return n[0] & 0x1f }

// nalRef is nal_ref_idc: 0 means no other picture refers to this one, so it is
// the only kind of unit that may be dropped without damaging what follows.
func nalRef(n []byte) byte { return (n[0] >> 5) & 3 }

// annexB prefixes a NAL unit with the 4-byte start code MediaCodec wants.
func annexB(nal []byte) []byte {
	out := make([]byte, 4+len(nal))
	out[3] = 1
	copy(out[4:], nal)
	return out
}

type videoTrack struct {
	control     string
	payloadType int
	sps, pps    []byte
}

// parseVideoTrack picks the H.264 media section out of an SDP. It must pick the
// VIDEO one: the gate offers an audio track as well, and a client that took the
// first m= line would set up the wrong track and wait forever for H.264.
func parseVideoTrack(sdp, base string) (videoTrack, error) {
	var t videoTrack
	t.payloadType = -1
	inVideo := false
	found := false
	for _, raw := range strings.Split(sdp, "\n") {
		line := strings.TrimSpace(raw)
		if strings.HasPrefix(line, "m=") {
			inVideo = strings.HasPrefix(line, "m=video")
			if inVideo {
				found = true
				f := strings.Fields(line)
				if len(f) >= 4 {
					if v, ok := atoi(f[3]); ok {
						t.payloadType = v
					}
				}
			}
			continue
		}
		if !inVideo {
			continue
		}
		switch {
		case strings.HasPrefix(line, "a=control:"):
			t.control = strings.TrimSpace(line[len("a=control:"):])
		case strings.HasPrefix(line, "a=fmtp:"):
			t.sps, t.pps = spropSets(line)
		}
	}
	if !found {
		return t, errNoVideo
	}
	if t.payloadType < 0 {
		return t, errNoPayload
	}
	switch {
	case t.control == "" || t.control == "*":
		t.control = base
	case strings.HasPrefix(strings.ToLower(t.control), "rtsp://"):
		// already absolute
	default:
		t.control = strings.TrimSuffix(base, "/") + "/" + t.control
	}
	return t, nil
}

func atoi(s string) (int, bool) {
	if s == "" || len(s) > 9 {
		return 0, false
	}
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + int(c-'0')
	}
	return n, true
}

// spropSets pulls the base64 SPS and PPS out of an a=fmtp line. The separators
// are ";" with OPTIONAL spaces, and the cameras on this estate disagree about
// them (the gate writes none, the UniFi doorbell writes one).
func spropSets(line string) (sps, pps []byte) {
	i := strings.Index(line, " ")
	if i < 0 {
		return nil, nil
	}
	for _, p := range strings.Split(line[i+1:], ";") {
		p = strings.TrimSpace(p)
		if !strings.HasPrefix(p, "sprop-parameter-sets=") {
			continue
		}
		v := strings.TrimPrefix(p, "sprop-parameter-sets=")
		parts := strings.Split(strings.TrimSpace(v), ",")
		if len(parts) > 0 {
			sps = b64(parts[0])
		}
		if len(parts) > 1 {
			pps = b64(parts[1])
		}
	}
	return sps, pps
}

// b64 decodes with and without padding: the estate produces both.
func b64(s string) []byte {
	s = strings.TrimSpace(s)
	if d, err := base64.StdEncoding.DecodeString(s); err == nil {
		return d
	}
	if d, err := base64.RawStdEncoding.DecodeString(s); err == nil {
		return d
	}
	return nil
}

// h264Depack turns RTP payloads into whole NAL units.
type h264Depack struct {
	fu       []byte // the FU-A currently being reassembled
	fuActive bool
	tsWrap   int64
	lastTS   uint32
	haveTS   bool
	// lost is set when a unit was discarded (too big, or its start was never
	// seen); the caller must then wait for the next IDR.
	lost bool
}

// push consumes one RTP packet and returns any NAL units it completed.
func (d *h264Depack) push(pkt []byte, wantPT int) ([][]byte, int64, bool) {
	if len(pkt) < 12 {
		return nil, 0, false
	}
	if pkt[0]>>6 != 2 { // RTP version 2
		return nil, 0, false
	}
	pt := int(pkt[1] & 0x7f)
	if wantPT >= 0 && pt != wantPT {
		return nil, 0, false
	}
	cc := int(pkt[0] & 0x0f)
	off := 12 + cc*4
	if pkt[0]&0x10 != 0 { // extension header
		if len(pkt) < off+4 {
			return nil, 0, false
		}
		extLen := int(binary.BigEndian.Uint16(pkt[off+2:off+4])) * 4
		off += 4 + extLen
	}
	if off >= len(pkt) {
		return nil, 0, false
	}
	// 32-bit RTP timestamps wrap about every 13 hours at 90 kHz; a tile left open
	// overnight would otherwise hand MediaCodec a PTS that leaps backwards.
	ts := binary.BigEndian.Uint32(pkt[4:8])
	if d.haveTS && ts < d.lastTS && d.lastTS-ts > 1<<31 {
		d.tsWrap++
	}
	d.lastTS = ts
	d.haveTS = true
	ptsUs := (d.tsWrap<<32 + int64(ts)) * 1000000 / 90000

	body := pkt[off:]
	typ := body[0] & 0x1f
	var out [][]byte

	switch {
	case typ >= 1 && typ <= 23:
		if len(body) > MaxNAL {
			d.lost = true
			return nil, 0, false
		}
		out = append(out, append([]byte(nil), body...))

	case typ == 24: // STAP-A: several NAL units in one packet
		p := body[1:]
		for len(p) >= 2 {
			n := int(binary.BigEndian.Uint16(p[:2]))
			p = p[2:]
			if n <= 0 || n > len(p) {
				break
			}
			out = append(out, append([]byte(nil), p[:n]...))
			p = p[n:]
		}

	case typ == 28: // FU-A: one NAL unit split across packets
		if len(body) < 2 {
			return nil, 0, false
		}
		start := body[1]&0x80 != 0
		end := body[1]&0x40 != 0
		nalT := body[1] & 0x1f
		if start {
			d.fu = []byte{(body[0] & 0xe0) | nalT}
			d.fuActive = true
		}
		if !d.fuActive {
			// Joined mid-NAL, or lost the start packet. Half a NAL unit fed to a
			// decoder is worse than a dropped one: it corrupts the reference.
			d.lost = true
			return nil, 0, false
		}
		if len(d.fu)+len(body)-2 > MaxNAL {
			d.fu = nil
			d.fuActive = false
			d.lost = true
			return nil, 0, false
		}
		d.fu = append(d.fu, body[2:]...)
		if end {
			out = append(out, d.fu)
			d.fu = nil
			d.fuActive = false
		}

	default:
		// 25-27 (STAP-B, MTAP) and 29 (FU-B) are not produced by any camera on this estate.
		return nil, 0, false
	}

	if len(out) == 0 {
		return nil, 0, false
	}
	return out, ptsUs, true
}

// spsGeometry decodes the picture size out of an H.264 SPS. MediaCodec's
// configure() wants a size up front and the honest one is in the bitstream (the
// gate's sub stream is 352x288, not even 16:9). Returns 0, 0 when it cannot.
func spsGeometry(sps []byte) (int, int) {
	if len(sps) < 4 {
		return 0, 0
	}
	r := &bitReader{d: unescape(sps[1:])} // skip the NAL header byte
	profile := r.u(8)
	r.u(8) // constraint flags
	r.u(8) // level
	r.ue() // seq_parameter_set_id

	switch profile {
	case 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135:
		chroma := r.ue()
		if chroma == 3 {
			r.u(1) // separate_colour_plane_flag
		}
		r.ue() // bit_depth_luma_minus8
		r.ue() // bit_depth_chroma_minus8
		r.u(1) // qpprime_y_zero_transform_bypass_flag
		if r.u(1) == 1 {
			n := 8
			if chroma == 3 {
				n = 12
			}
			for i := 0; i < n; i++ {
				if r.u(1) == 1 {
					size := 16
					if i >= 6 {
						size = 64
					}
					last, next := 8, 8
					for j := 0; j < size; j++ {
						if next != 0 {
							next = (last + r.se() + 256) % 256
						}
						if next != 0 {
							last = next
						}
					}
				}
			}
		}
	}

	r.ue() // log2_max_frame_num_minus4
	pocType := r.ue()
	switch pocType {
	case 0:
		r.ue()
	case 1:
		r.u(1)
		r.se()
		r.se()
		for i, n := 0, r.ue(); i < n && !r.bad; i++ {
			r.se()
		}
	}
	r.ue() // max_num_ref_frames
	r.u(1) // gaps_in_frame_num_value_allowed_flag

	widthMbs := r.ue() + 1
	heightMapUnits := r.ue() + 1
	frameMbsOnly := r.u(1)
	if frameMbsOnly == 0 {
		r.u(1) // mb_adaptive_frame_field_flag
	}
	r.u(1) // direct_8x8_inference_flag

	w := widthMbs * 16
	h := (2 - frameMbsOnly) * heightMapUnits * 16

	if r.u(1) == 1 { // frame_cropping_flag
		cl, cr, ct, cb := r.ue(), r.ue(), r.ue(), r.ue()
		// Cropping is in chroma units for 4:2:0, which is every camera here.
		// Ignoring it turns 1920x1080 into 1920x1088.
		w -= (cl + cr) * 2
		h -= (ct + cb) * 2 * (2 - frameMbsOnly)
	}
	if r.bad || w <= 0 || h <= 0 || w > 8192 || h > 8192 {
		return 0, 0
	}
	return w, h
}

// unescape removes H.264 emulation-prevention bytes (00 00 03 -> 00 00).
func unescape(b []byte) []byte {
	out := make([]byte, 0, len(b))
	for i := 0; i < len(b); i++ {
		if i+2 < len(b) && b[i] == 0 && b[i+1] == 0 && b[i+2] == 3 {
			out = append(out, 0, 0)
			i += 2
			continue
		}
		out = append(out, b[i])
	}
	return out
}

type bitReader struct {
	d   []byte
	p   int
	bad bool
}

func (r *bitReader) u(n int) int {
	v := 0
	for i := 0; i < n; i++ {
		if r.p>>3 >= len(r.d) {
			r.bad = true
			return v
		}
		v = v<<1 | int((r.d[r.p>>3]>>(7-uint(r.p&7)))&1)
		r.p++
	}
	return v
}

func (r *bitReader) ue() int {
	z := 0
	for r.u(1) == 0 {
		z++
		if z > 32 || r.bad {
			r.bad = true
			return 0
		}
	}
	if z == 0 {
		return 0
	}
	return (1 << uint(z)) - 1 + r.u(z)
}

func (r *bitReader) se() int {
	k := r.ue()
	if k%2 == 1 {
		return (k + 1) / 2
	}
	return -(k / 2)
}
