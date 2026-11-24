package rtsp

import (
	"bufio"
	"crypto/md5"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"
)

// The RTSP client: DESCRIBE, SETUP over TCP-interleaved, PLAY, an OPTIONS
// keepalive, and the interleaved `$`-framed RTP that follows. Ported from the old
// intercom app (tsmobile/rtspvideo.go), with these differences:
//   - errors never contain the URL, a host or a credential (a Journal line or a
//     Go log must be safe to post);
//   - line, header and body sizes are capped;
//   - the timeouts come from the session's Config.
//
// TCP interleaved, not UDP: the media rides the one socket the tailnet dial
// opened, so no second path is needed. It costs head-of-line blocking on a lossy
// link; the session's watchdog is what turns a stall into a reconnect.

var (
	errNoVideo   = errors.New("the stream has no video track")
	errNoPayload = errors.New("the video track names no payload type")
	errNoSession = errors.New("SETUP gave no session id")
	errSync      = errors.New("interleaved frame lost sync")
)

const (
	maxLine    = 8 << 10
	maxHeaders = 64
	maxBody    = 1 << 20
)

// statusError is a non-2xx answer; it carries the method and the code only.
type statusError struct {
	method string
	code   int
}

func (e *statusError) Error() string { return fmt.Sprintf("%s answered %d", e.method, e.code) }

type rtspClient struct {
	conn    net.Conn
	rd      *bufio.Reader
	base    string
	session string
	// rtpCh is the interleaved channel the SERVER put the video RTP on, read from
	// the SETUP reply's Transport header (go2rtc numbers channels by track index and
	// ignores the request). 0 until a reply says otherwise. RTCP is rtpCh+1 by
	// convention; the reply's second number is kept for completeness only.
	rtpCh  int
	rtcpCh int
	cseq   int
	user   string
	pass   string
	reqTO  time.Duration
	// The challenge from a 401, kept so every later request can answer it.
	authRealm, authNonce, authScheme string
}

func newClient(c net.Conn, base, user, pass string, requestTimeout time.Duration) *rtspClient {
	return &rtspClient{conn: c, rd: bufio.NewReaderSize(c, 64<<10), base: base, user: user, pass: pass, reqTO: requestTimeout}
}

func (c *rtspClient) next() int { c.cseq++; return c.cseq }

// request sends one RTSP request and reads its response, retrying ONCE with
// credentials if the server challenges (a second 401 means the credential is
// wrong, and resending it is how an account gets locked).
func (c *rtspClient) request(method, uri string, extra map[string]string) (int, map[string]string, string, error) {
	code, hdr, body, err := c.send(method, uri, extra)
	if err != nil {
		return 0, nil, "", err
	}
	if code == 401 && c.user != "" {
		c.parseChallenge(hdr["www-authenticate"])
		code, hdr, body, err = c.send(method, uri, extra)
		if err != nil {
			return 0, nil, "", err
		}
	}
	if code < 200 || code > 299 {
		return code, hdr, body, &statusError{method, code}
	}
	return code, hdr, body, nil
}

func (c *rtspClient) send(method, uri string, extra map[string]string) (int, map[string]string, string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "%s %s RTSP/1.0\r\n", method, uri)
	fmt.Fprintf(&b, "CSeq: %d\r\n", c.next())
	b.WriteString("User-Agent: portico-app\r\n")
	if c.session != "" {
		fmt.Fprintf(&b, "Session: %s\r\n", c.session)
	}
	if a := c.authHeader(method, uri); a != "" {
		fmt.Fprintf(&b, "Authorization: %s\r\n", a)
	}
	for k, v := range extra {
		fmt.Fprintf(&b, "%s: %s\r\n", k, v)
	}
	b.WriteString("\r\n")

	_ = c.conn.SetWriteDeadline(time.Now().Add(c.reqTO))
	if _, err := io.WriteString(c.conn, b.String()); err != nil {
		return 0, nil, "", err
	}
	_ = c.conn.SetReadDeadline(time.Now().Add(c.reqTO))
	return c.readResponse()
}

// readLine reads one line, at most maxLine bytes.
func (c *rtspClient) readLine() (string, error) {
	line, err := c.rd.ReadSlice('\n')
	if err == bufio.ErrBufferFull {
		return "", errors.New("RTSP line too long")
	}
	if err != nil {
		return "", err
	}
	if len(line) > maxLine {
		return "", errors.New("RTSP line too long")
	}
	return string(line), nil
}

// readResponse reads one RTSP response, skipping any interleaved media that
// arrives in front of it: after PLAY the server is already pushing RTP down the
// same socket, so the reply to a keepalive can be preceded by `$`-framed packets.
func (c *rtspClient) readResponse() (int, map[string]string, string, error) {
	for {
		b, err := c.rd.Peek(1)
		if err != nil {
			return 0, nil, "", err
		}
		if b[0] == '$' {
			if _, _, err := c.readOneInterleaved(); err != nil {
				return 0, nil, "", err
			}
			continue
		}
		line, err := c.readLine()
		if err != nil {
			return 0, nil, "", err
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if !strings.HasPrefix(line, "RTSP/") {
			return 0, nil, "", errors.New("not an RTSP response")
		}
		f := strings.Fields(line)
		if len(f) < 2 {
			return 0, nil, "", errors.New("malformed status line")
		}
		code, _ := strconv.Atoi(f[1])

		hdr := map[string]string{}
		for n := 0; ; n++ {
			if n > maxHeaders {
				return 0, nil, "", errors.New("too many RTSP headers")
			}
			h, err := c.readLine()
			if err != nil {
				return 0, nil, "", err
			}
			h = strings.TrimRight(h, "\r\n")
			if h == "" {
				break
			}
			i := strings.Index(h, ":")
			if i < 0 {
				continue
			}
			hdr[strings.ToLower(strings.TrimSpace(h[:i]))] = strings.TrimSpace(h[i+1:])
		}
		body := ""
		if n, _ := strconv.Atoi(hdr["content-length"]); n > 0 {
			if n > maxBody {
				return 0, nil, "", errors.New("absurd content-length")
			}
			buf := make([]byte, n)
			if _, err := io.ReadFull(c.rd, buf); err != nil {
				return 0, nil, "", err
			}
			body = string(buf)
		}
		return code, hdr, body, nil
	}
}

func (c *rtspClient) describe() (string, error) {
	_, hdr, body, err := c.request("DESCRIBE", c.base, map[string]string{"Accept": "application/sdp"})
	if err != nil {
		return "", err
	}
	// Content-Base, when present, is authoritative for resolving a relative
	// a=control, and it is frequently NOT the URL we asked for.
	if cb := hdr["content-base"]; cb != "" {
		c.base = strings.TrimSuffix(cb, "/")
	}
	return body, nil
}

func (c *rtspClient) setup(control string) error {
	_, hdr, _, err := c.request("SETUP", control, map[string]string{"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1"})
	if err != nil {
		return err
	}
	// A reply without an interleaved= parameter keeps what we asked for (0-1).
	if rtp, rtcp, ok := parseInterleaved(hdr["transport"]); ok {
		c.rtpCh, c.rtcpCh = rtp, rtcp
	} else {
		c.rtpCh, c.rtcpCh = 0, 1
	}
	s := hdr["session"]
	if i := strings.Index(s, ";"); i >= 0 {
		s = s[:i]
	}
	c.session = strings.TrimSpace(s)
	if c.session == "" {
		return errNoSession
	}
	return nil
}

// parseInterleaved reads `interleaved=a-b` (or `interleaved=a`) from a Transport
// header. ok is false when the parameter is absent or malformed.
func parseInterleaved(transport string) (rtp, rtcp int, ok bool) {
	for _, part := range strings.Split(transport, ";") {
		part = strings.TrimSpace(part)
		if !strings.HasPrefix(strings.ToLower(part), "interleaved=") {
			continue
		}
		v := part[len("interleaved="):]
		a, b, hasB := strings.Cut(v, "-")
		x, err := strconv.Atoi(strings.TrimSpace(a))
		if err != nil || x < 0 || x > 255 {
			return 0, 0, false
		}
		y := x + 1
		if hasB {
			if y, err = strconv.Atoi(strings.TrimSpace(b)); err != nil || y < 0 || y > 255 {
				return 0, 0, false
			}
		}
		return x, y, true
	}
	return 0, 0, false
}

func (c *rtspClient) play() error {
	_, _, _, err := c.request("PLAY", c.base, map[string]string{"Range": "npt=0.000-"})
	return err
}

func (c *rtspClient) keepalive() error {
	_, _, _, err := c.request("OPTIONS", c.base, nil)
	return err
}

// readInterleaved returns the next media packet, or reports that an RTSP
// response was consumed instead (the reply to a keepalive).
func (c *rtspClient) readInterleaved() (ch int, pkt []byte, isRTSP bool, err error) {
	b, err := c.rd.Peek(1)
	if err != nil {
		return 0, nil, false, err
	}
	if b[0] != '$' {
		if _, _, _, err := c.readResponse(); err != nil {
			return 0, nil, false, err
		}
		return 0, nil, true, nil
	}
	ch, pkt, err = c.readOneInterleaved()
	return ch, pkt, false, err
}

func (c *rtspClient) readOneInterleaved() (int, []byte, error) {
	var h [4]byte
	if _, err := io.ReadFull(c.rd, h[:]); err != nil {
		return 0, nil, err
	}
	if h[0] != '$' {
		return 0, nil, errSync
	}
	n := int(binary.BigEndian.Uint16(h[2:4]))
	buf := make([]byte, n)
	if _, err := io.ReadFull(c.rd, buf); err != nil {
		return 0, nil, err
	}
	return int(h[1]), buf, nil
}

func (c *rtspClient) parseChallenge(h string) {
	if h == "" {
		return
	}
	lower := strings.ToLower(h)
	switch {
	case strings.HasPrefix(lower, "digest"):
		c.authScheme = "Digest"
	case strings.HasPrefix(lower, "basic"):
		c.authScheme = "Basic"
	default:
		return
	}
	c.authRealm = quoted(h, "realm")
	c.authNonce = quoted(h, "nonce")
}

func (c *rtspClient) authHeader(method, uri string) string {
	if c.user == "" {
		return ""
	}
	switch c.authScheme {
	case "Basic":
		return "Basic " + base64.StdEncoding.EncodeToString([]byte(c.user+":"+c.pass))
	case "Digest":
		ha1 := md5hex(c.user + ":" + c.authRealm + ":" + c.pass)
		ha2 := md5hex(method + ":" + uri)
		resp := md5hex(ha1 + ":" + c.authNonce + ":" + ha2)
		return fmt.Sprintf(`Digest username="%s", realm="%s", nonce="%s", uri="%s", response="%s"`, c.user, c.authRealm, c.authNonce, uri, resp)
	}
	return ""
}

func md5hex(s string) string {
	sum := md5.Sum([]byte(s))
	return hex.EncodeToString(sum[:])
}

func quoted(h, key string) string {
	i := strings.Index(strings.ToLower(h), key+"=\"")
	if i < 0 {
		return ""
	}
	rest := h[i+len(key)+2:]
	j := strings.Index(rest, "\"")
	if j < 0 {
		return ""
	}
	return rest[:j]
}

// teardown tells the server we have gone, before the socket is dropped. go2rtc
// otherwise keeps the RTSP session and keeps writing H.264 into it until its own
// 60 s timeout, about 1 Mbps per abandoned session across the tunnel (found in
// the old app's tsnet log on 2026-09-15). WRITE ONLY: the read loop owns the
// reader. A fixed CSeq, best effort, a short deadline: a server that has
// already gone must not hold up a tile that is closing.
func teardown(c net.Conn, base, session string, wait time.Duration) {
	if session == "" || base == "" {
		return
	}
	defer func() { _ = recover() }()
	_ = c.SetWriteDeadline(time.Now().Add(wait))
	fmt.Fprintf(c, "TEARDOWN %s RTSP/1.0\r\nCSeq: 999999\r\nSession: %s\r\n\r\n", base, session)
}
