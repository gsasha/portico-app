package rtsp

import (
	"fmt"
	"sync"
)

type kind int

const (
	kindFormat kind = iota
	kindNal
	kindState
)

type item struct {
	kind     kind
	sps, pps []byte
	w, h     int
	nal      []byte
	pts      int64
	key      bool
	state    string
}

// queue is the bounded hand-off between the reader goroutine and the goroutine
// that calls the sink: the only place H.264 waits on the phone, so the only place
// it could pile up. The reader never blocks on it and it never grows past its caps.
//
// When the sink lags: a NON-REFERENCE unit (nal_ref_idc 0: no other picture
// refers to it) is simply dropped; when a unit that others depend on does not fit,
// the sink is far behind, and dropping one picture of a stream corrupts every
// picture that refers to it, so the queue empties itself of pictures and refuses
// everything until the next IDR. A session starts the same way: nothing but an
// IDR is delivered first.
type queue struct {
	mu       sync.Mutex
	cond     *sync.Cond
	items    []item
	bytes    int
	maxItems int
	maxBytes int
	closed   bool
	needIDR  bool
	dropped  int64
	resyncs  int64
}

func newQueue(maxItems, maxBytes int) *queue {
	if maxItems <= 0 {
		maxItems = 90
	}
	if maxBytes <= 0 {
		maxBytes = 4 << 20
	}
	q := &queue{maxItems: maxItems, maxBytes: maxBytes, needIDR: true}
	q.cond = sync.NewCond(&q.mu)
	return q
}

func (q *queue) push(it item) {
	q.items = append(q.items, it)
	q.cond.Signal()
}

func (q *queue) pushState(s string) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.closed {
		q.push(item{kind: kindState, state: s})
	}
}

func (q *queue) pushFormat(sps, pps []byte, w, h int) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if !q.closed {
		q.push(item{kind: kindFormat, sps: sps, pps: pps, w: w, h: h})
	}
}

// pushNal queues one Annex-B unit. key is an IDR slice, ref says other pictures may depend on it.
func (q *queue) pushNal(nal []byte, pts int64, key, ref bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.closed {
		return
	}
	if q.needIDR {
		if !key {
			q.dropped++
			return
		}
		q.needIDR = false
	}
	if len(q.items) >= q.maxItems || q.bytes+len(nal) > q.maxBytes {
		if !ref && !key {
			q.dropped++
			return
		}
		q.purgePictures()
		q.resyncs++
		if !key {
			q.needIDR = true
			q.dropped++
			return
		}
	}
	q.bytes += len(nal)
	q.push(item{kind: kindNal, nal: nal, pts: pts, key: key})
}

// loss: a unit was lost upstream (too big, or its start never came). The pictures already queued
// are whole; what follows is not trustworthy until an IDR.
func (q *queue) loss() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.needIDR = true
	q.dropped++
}

// purgePictures removes every queued picture and keeps the parameter sets and states in order.
func (q *queue) purgePictures() {
	kept := q.items[:0]
	for _, it := range q.items {
		if it.kind == kindNal {
			q.dropped++
			continue
		}
		kept = append(kept, it)
	}
	for i := len(kept); i < len(q.items); i++ {
		q.items[i] = item{}
	}
	q.items = kept
	q.bytes = 0
}

// pop blocks for the next item; false once the queue is closed.
func (q *queue) pop() (item, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for len(q.items) == 0 && !q.closed {
		q.cond.Wait()
	}
	if q.closed {
		return item{}, false
	}
	it := q.items[0]
	q.items[0] = item{}
	q.items = q.items[1:]
	if it.kind == kindNal {
		q.bytes -= len(it.nal)
	}
	return it, true
}

// close drops what is queued and wakes the consumer.
func (q *queue) close() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.closed = true
	q.items = nil
	q.bytes = 0
	q.cond.Broadcast()
}

func (q *queue) counts() (dropped, resyncs int64) {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.dropped, q.resyncs
}

func (q *queue) len() (items, bytes int) {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.items), q.bytes
}

func fmtCounts(nals, bytes, dropped, resyncs int64) string {
	return fmt.Sprintf("%d units, %d KB, %d dropped, %d resyncs", nals, bytes/1024, dropped, resyncs)
}
