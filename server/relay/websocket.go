package relay

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	AudioHeaderSize  = 58
	MaxOpusPayload   = 1275
	maxSocketMessage = 4096
	writeTimeout     = 2 * time.Second
	maxAudioAge      = 250 * time.Millisecond
)

type queuedAudio struct {
	data       []byte
	created    time.Time
	source     *client
	memberID   string
	generation uint64
}

// Every physical socket has exactly one reader and one writer, regardless of
// how many logical sources it carries. All route state uses Server.mu.
type client struct {
	s              *Server
	ws             *websocket.Conn
	room           *room
	primary        string
	routes         map[string]bool
	controls       chan []byte
	audio          chan queuedAudio
	terminal       chan [][]byte
	done           chan struct{}
	once           sync.Once
	revoked        atomic.Bool
	adminChallenge string
	adminCommands  map[string]int64
}

func (c *client) stop() { c.once.Do(func() { close(c.done); _ = c.ws.Close() }) }
func (c *client) closeWith(v any) {
	c.closeWithMessages(v)
}
func (c *client) closeWithMessages(values ...any) {
	c.revoked.Store(true)
	batch := make([][]byte, 0, len(values))
	for _, v := range values {
		b, _ := json.Marshal(v)
		batch = append(batch, b)
	}
	select {
	case c.terminal <- batch:
	default:
	}
}
func (c *client) control(v any) {
	b, _ := json.Marshal(v)
	select {
	case <-c.done:
		return
	default:
	}
	select {
	case c.controls <- b:
	default:
		c.stop()
	}
}
func (c *client) error(code, message string) {
	c.control(map[string]string{"type": "error", "code": code, "message": message})
}

func sameOrigin(r *http.Request) bool {
	values := r.Header.Values("Origin")
	if len(values) == 0 {
		return true
	}
	if len(values) != 1 {
		return false
	}
	u, err := url.Parse(values[0])
	if err != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return false
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return u.Scheme == scheme && strings.EqualFold(u.Host, r.Host)
}

func (s *Server) socket(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	full := s.closed || s.storageErr != nil || len(s.connections) >= s.config.MaxConnections
	s.mu.Unlock()
	if full {
		fail(w, 503, "connection_limit", "Connection limit reached")
		return
	}
	challenge, err := randomToken(32)
	if err != nil {
		fail(w, 503, "entropy_unavailable", "Cannot create socket challenge")
		return
	}
	u := websocket.Upgrader{ReadBufferSize: 2048, WriteBufferSize: 2048, HandshakeTimeout: 5 * time.Second, CheckOrigin: sameOrigin, Error: func(w http.ResponseWriter, r *http.Request, status int, _ error) {
		fail(w, status, "websocket_rejected", "WebSocket upgrade rejected")
	}}
	ws, err := u.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	c := &client{s: s, ws: ws, routes: make(map[string]bool), controls: make(chan []byte, 32), audio: make(chan queuedAudio, 8), terminal: make(chan [][]byte, 1), done: make(chan struct{}), adminChallenge: challenge, adminCommands: make(map[string]int64)}
	s.mu.Lock()
	if s.closed || s.storageErr != nil || len(s.connections) >= s.config.MaxConnections {
		s.mu.Unlock()
		c.stop()
		return
	}
	s.connections[c] = true
	s.wg.Add(2)
	s.mu.Unlock()
	go c.writer()
	defer s.wg.Done()
	defer c.cleanup()
	ws.SetReadLimit(maxSocketMessage)
	_ = ws.SetReadDeadline(time.Now().Add(s.config.AuthTimeout))
	typ, raw, err := ws.ReadMessage()
	if err != nil {
		return
	}
	if typ != websocket.TextMessage {
		c.closeWith(map[string]string{"type": "error", "code": "auth_required", "message": "First message must authenticate"})
		c.awaitClose()
		return
	}
	var first controlMessage
	if !parseControl(raw, &first) || first.Type != "auth" {
		c.closeWith(map[string]string{"type": "error", "code": "auth_required", "message": "First message must authenticate"})
		c.awaitClose()
		return
	}
	if !s.authenticate(c, first) {
		c.awaitClose()
		return
	}
	ws.SetPongHandler(func(string) error { return ws.SetReadDeadline(time.Now().Add(45 * time.Second)) })
	_ = ws.SetReadDeadline(time.Now().Add(45 * time.Second))
	period := time.Now()
	messages, controls := 0, 0
	for {
		typ, raw, err = ws.ReadMessage()
		if err != nil {
			return
		}
		if c.revoked.Load() {
			c.awaitClose()
			return
		}
		now := time.Now()
		if now.Sub(period) >= time.Second {
			period = now
			messages = 0
			controls = 0
		}
		messages++
		if typ == websocket.TextMessage {
			controls++
		}
		if messages > 400 || controls > 40 {
			c.closeWith(map[string]string{"type": "error", "code": "rate_limit", "message": "Message rate exceeded"})
			c.awaitClose()
			return
		}
		switch typ {
		case websocket.TextMessage:
			var m controlMessage
			if !parseControl(raw, &m) {
				c.error("invalid_control", "Invalid JSON control")
				continue
			}
			s.command(c, m)
		case websocket.BinaryMessage:
			s.audioFrame(c, raw)
		default:
			c.error("invalid_message", "Expected text control or binary audio")
		}
	}
}

// Give the bounded writer time to deliver a final event; the write deadline is
// the upper bound even if the peer stops reading.
func (c *client) awaitClose() {
	select {
	case <-c.done:
	case <-time.After(writeTimeout + 100*time.Millisecond):
		c.stop()
	}
}

func (c *client) cleanup() {
	c.stop()
	s := c.s
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.connections, c)
	if c.room == nil {
		return
	}
	r := c.room
	delete(r.connections, c)
	changed := false
	for id := range c.routes {
		if m := r.members[id]; m != nil && m.route == c {
			m.route = nil
			changed = true
		}
	}
	if changed && s.rooms[r.id] == r {
		s.rosterLocked(r)
	}
}

func (c *client) writer() {
	defer c.s.wg.Done()
	defer c.stop()
	ping := time.NewTicker(15 * time.Second)
	defer ping.Stop()
	for {
		// Priority passes prevent queued voice from delaying roster/revocation.
		select {
		case batch := <-c.terminal:
			c.writeTerminal(batch)
			return
		default:
		}
		select {
		case b := <-c.controls:
			if c.write(websocket.TextMessage, b) != nil {
				return
			}
			continue
		default:
		}
		select {
		case <-c.done:
			return
		case batch := <-c.terminal:
			c.writeTerminal(batch)
			return
		case b := <-c.controls:
			if c.write(websocket.TextMessage, b) != nil {
				return
			}
		case a := <-c.audio:
			if time.Since(a.created) > maxAudioAge {
				continue
			}
			s := c.s
			s.mu.Lock()
			valid := !c.revoked.Load() && !a.source.revoked.Load() && !s.closed && s.storageErr == nil && c.room != nil && s.rooms[c.room.id] == c.room && c.room.members[c.primary] != nil && len(c.routes) > 0
			if valid {
				m := c.room.members[a.memberID]
				valid = m != nil && m.route == a.source && m.generation == a.generation
			}
			s.mu.Unlock()
			if valid && time.Since(a.created) <= maxAudioAge && c.write(websocket.BinaryMessage, a.data) != nil {
				return
			}
		case <-ping.C:
			if c.write(websocket.PingMessage, nil) != nil {
				return
			}
		}
	}
}

func (c *client) write(typ int, b []byte) error {
	_ = c.ws.SetWriteDeadline(time.Now().Add(writeTimeout))
	return c.ws.WriteMessage(typ, b)
}

func (c *client) writeTerminal(batch [][]byte) {
	for _, b := range batch {
		if c.write(websocket.TextMessage, b) != nil {
			return
		}
	}
}

type controlMessage struct {
	Type       string `json:"type"`
	RoomID     string `json:"roomId,omitempty"`
	MemberID   string `json:"memberId,omitempty"`
	Token      string `json:"token,omitempty"`
	Generation uint64 `json:"generation,omitempty"`
	CommandID  string `json:"commandId,omitempty"`
	Action     string `json:"action,omitempty"`
	TargetID   string `json:"targetId,omitempty"`
	IssuedAt   int64  `json:"issuedAt,omitempty"`
	Challenge  string `json:"challenge,omitempty"`
	Signature  string `json:"signature,omitempty"`
}

func parseControl(raw []byte, v *controlMessage) bool {
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return false
	}
	return d.Decode(new(any)) == io.EOF
}

func (s *Server) authenticate(c *client, msg controlMessage) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || s.storageErr != nil {
		c.closeWith(map[string]string{"type": "error", "code": "not_ready", "message": "Relay is unavailable"})
		return false
	}
	r := s.rooms[msg.RoomID]
	if !validID(msg.RoomID) || !validID(msg.MemberID) || !validToken(msg.Token) || r == nil || r.members[msg.MemberID] == nil || !same(r.members[msg.MemberID].token, msg.Token) {
		c.closeWith(map[string]string{"type": "error", "code": "unauthorized", "message": "Room or member credentials do not match"})
		return false
	}
	if len(r.connections) >= 16 {
		c.closeWith(map[string]string{"type": "error", "code": "connection_limit", "message": "Room connection limit reached"})
		return false
	}
	m := r.members[msg.MemberID]
	if code := routeError(m, c, msg.Generation); code != "" {
		c.closeWith(map[string]string{"type": "error", "code": code, "message": "Use a valid, newer route generation"})
		return false
	}
	c.room = r
	c.primary = m.id
	r.connections[c] = true
	s.setRouteLocked(m, c, msg.Generation)
	c.control(map[string]any{"type": "authenticated", "memberId": m.id, "room": snapshot(r), "adminChallenge": c.adminChallenge})
	s.rosterLocked(r)
	return true
}

func routeError(m *member, c *client, gen uint64) string {
	if gen == 0 || gen > math.MaxInt64 {
		return "invalid_generation"
	}
	if gen < m.generation || (m.route != nil && m.route != c && gen <= m.generation) {
		return "stale_generation"
	}
	return ""
}

func (s *Server) setRouteLocked(m *member, c *client, gen uint64) {
	if m.route != nil && m.route != c {
		delete(m.route.routes, m.id)
	}
	m.route = c
	m.generation = gen
	c.routes[m.id] = true
}

func (s *Server) command(c *client, msg controlMessage) {
	if msg.Type == "owner_command" {
		s.ownerCommand(c, msg)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	r := c.room
	if c.revoked.Load() || s.closed || s.storageErr != nil || s.rooms[r.id] != r || r.members[c.primary] == nil {
		c.closeWith(map[string]string{"type": "error", "code": "unauthorized", "message": "Session revoked"})
		return
	}
	if msg.RoomID != "" && msg.RoomID != r.id {
		c.error("room_mismatch", "Delegated source must belong to this room")
		return
	}
	switch msg.Type {
	case "attach":
		m := r.members[msg.MemberID]
		if !validID(msg.MemberID) || !validToken(msg.Token) || m == nil || !same(m.token, msg.Token) {
			c.error("unauthorized", "Member credentials do not match")
			return
		}
		if code := routeError(m, c, msg.Generation); code != "" {
			c.error(code, "Use a valid, newer route generation")
			return
		}
		s.setRouteLocked(m, c, msg.Generation)
		c.control(map[string]any{"type": "attached", "memberId": m.id, "room": snapshot(r), "adminChallenge": c.adminChallenge})
		s.rosterLocked(r)
	case "detach":
		if msg.MemberID == c.primary {
			c.error("primary_route", "Primary route cannot be detached")
			return
		}
		m := r.members[msg.MemberID]
		if m == nil || m.route != c {
			c.error("source_not_owned", "Source is not attached to this socket")
			return
		}
		m.route = nil
		delete(c.routes, m.id)
		c.control(map[string]any{"type": "detached", "memberId": m.id})
		s.rosterLocked(r)
	case "ping":
		c.control(map[string]string{"type": "pong"})
	default:
		c.error("invalid_control", "Unknown control type")
	}
}

func (s *Server) audioFrame(c *client, raw []byte) {
	if len(raw) < AudioHeaderSize || len(raw) > AudioHeaderSize+MaxOpusPayload || !bytes.Equal(raw[:4], []byte("BTK1")) {
		c.error("invalid_audio", "Invalid BTK1 frame size or magic")
		return
	}
	length := int(binary.BigEndian.Uint16(raw[56:58]))
	if length < 1 || length > MaxOpusPayload || len(raw) != AudioHeaderSize+length {
		c.error("invalid_audio", "Payload length does not match frame")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	r := c.room
	if c.revoked.Load() || s.closed || s.storageErr != nil || r.members[c.primary] == nil {
		c.closeWith(map[string]string{"type": "error", "code": "unauthorized", "message": "Session revoked"})
		return
	}
	if s.rooms[r.id] != r {
		c.error("room_closed", "Room unavailable")
		return
	}
	rid := uuidBytes(r.id)
	if !bytes.Equal(raw[4:20], rid[:]) {
		c.error("room_mismatch", "Frame room differs from authenticated room")
		return
	}
	sender := rawUUID(raw[20:36])
	m := r.members[sender]
	if m == nil || m.route != c || !c.routes[sender] {
		c.error("source_not_owned", "Source is not routed through this socket")
		return
	}
	if !m.replay.accept(binary.BigEndian.Uint64(raw[36:44]), binary.BigEndian.Uint32(raw[44:48])) {
		return
	}
	a := queuedAudio{data: raw, created: time.Now(), source: c, memberID: m.id, generation: m.generation}
	for target := range r.connections {
		if target == c || len(target.routes) == 0 {
			continue
		}
		select {
		case <-target.done:
			continue
		default:
		}
		select {
		case target.audio <- a:
		default:
			select {
			case <-target.audio:
			default:
			}
			select {
			case target.audio <- a:
			default:
			}
		}
	}
}

func rawUUID(b []byte) string {
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

// A 64-packet sliding window for each of the four most recent source epochs.
// This bounds memory while allowing normal network reordering and restarts.
type replayWindow struct {
	epochs      [4]epochWindow
	count, next int
}
type epochWindow struct {
	epoch   uint64
	highest uint32
	bits    uint64
}

func (r *replayWindow) accept(epoch uint64, seq uint32) bool {
	for i := 0; i < r.count; i++ {
		w := &r.epochs[i]
		if w.epoch != epoch {
			continue
		}
		if seq > w.highest {
			delta := seq - w.highest
			if delta >= 64 {
				w.bits = 1
			} else {
				w.bits = (w.bits << delta) | 1
			}
			w.highest = seq
			return true
		}
		delta := w.highest - seq
		if delta >= 64 || w.bits&(uint64(1)<<delta) != 0 {
			return false
		}
		w.bits |= uint64(1) << delta
		return true
	}
	r.epochs[r.next] = epochWindow{epoch: epoch, highest: seq, bits: 1}
	r.next = (r.next + 1) % len(r.epochs)
	if r.count < len(r.epochs) {
		r.count++
	}
	return true
}
