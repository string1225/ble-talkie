// Package relay implements the bounded, in-memory BTK1 room and voice relay.
package relay

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

const MaxTeamSize = 4

// Config zero values select production defaults. Prefix is the deployment path.
type Config struct {
	Prefix         string
	ListenAddr     string
	APKPath        string
	StatePath      string
	RoomTTL        time.Duration
	RequestTTL     time.Duration
	AuthTimeout    time.Duration
	MaxRooms       int
	MaxRequests    int
	MaxConnections int
}

func ConfigFromEnv() (Config, error) {
	c := Config{Prefix: os.Getenv("BLE_TALKIE_PREFIX"), ListenAddr: os.Getenv("BLE_TALKIE_LISTEN"), APKPath: os.Getenv("BLE_TALKIE_APK"), StatePath: os.Getenv("BLE_TALKIE_STATE")}
	if c.Prefix != "" && (!strings.HasPrefix(c.Prefix, "/") || strings.ContainsAny(c.Prefix, "?#\\") || strings.Contains(c.Prefix, "..")) {
		return Config{}, errors.New("BLE_TALKIE_PREFIX must be an absolute URL path")
	}
	if c.ListenAddr == "" {
		c.ListenAddr = "127.0.0.1:18110"
	}
	return c, nil
}

type Server struct {
	mu          sync.Mutex
	config      Config
	rooms       map[string]*room
	codes       map[string]*room
	requests    map[string]*joinRequest
	connections map[*client]bool
	closed      bool
	storageErr  error
	done        chan struct{}
	wg          sync.WaitGroup
}

type room struct {
	id, code, ownerID, ownerToken string
	members                       map[string]*member
	requests                      map[string]*joinRequest
	connections                   map[*client]bool
	expires                       time.Time
}

type member struct {
	id, nickname, token string
	route               *client
	generation          uint64
	replay              replayWindow
}

type joinRequest struct {
	id, token, memberID, nickname, status, memberToken string
	room                                               *room
	expires                                            time.Time
}

type memberView struct {
	ID         string `json:"id"`
	Nickname   string `json:"nickname"`
	Online     bool   `json:"online"`
	Via        string `json:"via"`
	Generation uint64 `json:"generation"`
}

type roomView struct {
	RoomID      string       `json:"roomId"`
	InviteCode  string       `json:"inviteCode"`
	OwnerID     string       `json:"ownerId"`
	Members     []memberView `json:"members"`
	MaxTeamSize int          `json:"maxTeamSize"`
}

func New(c Config) *Server {
	if c.Prefix == "" {
		c.Prefix = "/ble-talkie"
	}
	c.Prefix = strings.TrimRight(c.Prefix, "/")
	if c.ListenAddr == "" {
		c.ListenAddr = "127.0.0.1:18110"
	}
	if c.RoomTTL <= 0 {
		c.RoomTTL = 24 * time.Hour
	}
	if c.RequestTTL <= 0 {
		c.RequestTTL = 10 * time.Minute
	}
	if c.AuthTimeout <= 0 {
		c.AuthTimeout = 5 * time.Second
	}
	if c.MaxRooms <= 0 {
		c.MaxRooms = 1024
	}
	if c.MaxRequests <= 0 {
		c.MaxRequests = 4096
	}
	if c.MaxConnections <= 0 {
		c.MaxConnections = 1024
	}
	s := &Server{config: c, rooms: make(map[string]*room), codes: make(map[string]*room), requests: make(map[string]*joinRequest), connections: make(map[*client]bool), done: make(chan struct{})}
	s.storageErr = s.loadState()
	s.wg.Add(1)
	go s.expirer()
	return s
}

// Close marks readiness false and closes all sockets. It is safe to call twice.
func (s *Server) Close() error {
	s.mu.Lock()
	if !s.closed {
		s.closed = true
		close(s.done)
		for c := range s.connections {
			c.stop()
		}
	}
	s.mu.Unlock()
	s.wg.Wait()
	return nil
}

func (s *Server) expirer() {
	defer s.wg.Done()
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-s.done:
			return
		case now := <-tick.C:
			s.mu.Lock()
			s.expireLocked(now)
			s.mu.Unlock()
		}
	}
}

func (s *Server) expireLocked(now time.Time) {
	if s.storageErr != nil {
		return
	}
	changed := false
	for _, r := range s.rooms {
		if !now.Before(r.expires) {
			s.closeRoomLocked(r, "room_expired")
			changed = true
		}
	}
	for id, q := range s.requests {
		if !now.Before(q.expires) {
			delete(s.requests, id)
			delete(q.room.requests, id)
		}
	}
	if changed {
		_ = s.persistLocked()
	}
}

func (s *Server) closeRoomLocked(r *room, reason string) {
	for c := range r.connections {
		c.closeWith(map[string]any{"type": "room_closed", "reason": reason})
	}
	for id := range r.requests {
		delete(s.requests, id)
	}
	delete(s.codes, r.code)
	delete(s.rooms, r.id)
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Cache-Control", "no-store")
	prefix := s.config.Prefix
	if r.URL.Path != prefix && !strings.HasPrefix(r.URL.Path, prefix+"/") {
		fail(w, 404, "not_found", "Unknown endpoint")
		return
	}
	path := strings.TrimPrefix(r.URL.Path, prefix)
	s.mu.Lock()
	s.expireLocked(time.Now())
	closed := s.closed || s.storageErr != nil
	s.mu.Unlock()
	if closed {
		fail(w, 503, "not_ready", "Relay is shutting down")
		return
	}
	switch {
	case (path == "" || path == "/") && r.Method == "GET":
		writeJSON(w, 200, map[string]any{"service": "ble-talkie", "status": "ready", "health": prefix + "/healthz", "invite": prefix + "/invite", "download": prefix + "/download"})
	case (path == "/healthz" || path == "/health") && r.Method == "GET":
		writeJSON(w, 200, map[string]any{"status": "ready"})
	case path == "/invite" && r.Method == "GET":
		s.invite(w, r)
	case path == "/download" && r.Method == "GET":
		s.download(w, r)
	case path == "/ws" && r.Method == "GET":
		s.socket(w, r)
	case path == "/api/rooms" && r.Method == "POST":
		s.create(w, r)
	case path == "/api/join" && r.Method == "POST":
		s.join(w, r)
	case strings.HasPrefix(path, "/api/join/") && r.Method == "GET":
		s.poll(w, r, strings.TrimPrefix(path, "/api/join/"))
	case strings.HasPrefix(path, "/api/join/") && r.Method == "DELETE":
		s.withdraw(w, r, strings.TrimPrefix(path, "/api/join/"))
	case strings.HasPrefix(path, "/api/rooms/"):
		s.roomAPI(w, r, strings.TrimPrefix(path, "/api/rooms/"))
	default:
		fail(w, 404, "not_found", "Unknown endpoint")
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"error": code, "message": message})
}

func (s *Server) readyLocked(w http.ResponseWriter) bool {
	if s.closed || s.storageErr != nil {
		fail(w, 503, "not_ready", "Relay is unavailable")
		return false
	}
	return true
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if strings.Split(r.Header.Get("Content-Type"), ";")[0] != "application/json" {
		fail(w, 415, "json_required", "Use application/json")
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		fail(w, 400, "invalid_json", "Invalid or oversized JSON body")
		return false
	}
	if err := d.Decode(new(any)); err != io.EOF {
		fail(w, 400, "invalid_json", "Expected exactly one JSON object")
		return false
	}
	return true
}

func validID(v string) bool {
	if len(v) != 36 || v[8] != '-' || v[13] != '-' || v[18] != '-' || v[23] != '-' {
		return false
	}
	for i, b := range []byte(v) {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			continue
		}
		if !((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f')) {
			return false
		}
	}
	return v != "00000000-0000-0000-0000-000000000000"
}

func validToken(v string) bool {
	b, err := base64.RawURLEncoding.DecodeString(v)
	return err == nil && len(b) == 32 && base64.RawURLEncoding.EncodeToString(b) == v
}
func validNickname(v string) bool {
	if !utf8.ValidString(v) || strings.TrimSpace(v) != v || utf8.RuneCountInString(v) < 1 || utf8.RuneCountInString(v) > 32 {
		return false
	}
	for _, r := range v {
		if unicode.IsControl(r) || unicode.In(r, unicode.Cf) {
			return false
		}
	}
	return true
}
func same(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }
func bearer(r *http.Request) string {
	s := r.Header.Get("Authorization")
	if !strings.HasPrefix(s, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(s, "Bearer ")
}

func randomToken(size int) (string, error) {
	b := make([]byte, size)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
func randomID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}

func snapshot(r *room) roomView {
	members := make([]memberView, 0, len(r.members))
	for _, m := range r.members {
		via := ""
		if m.route != nil {
			via = m.route.primary
		}
		members = append(members, memberView{m.id, m.nickname, m.route != nil, via, m.generation})
	}
	sort.Slice(members, func(i, j int) bool { return members[i].ID < members[j].ID })
	return roomView{r.id, r.code, r.ownerID, members, MaxTeamSize}
}

func (s *Server) rosterLocked(r *room) {
	view := snapshot(r)
	for c := range r.connections {
		c.control(map[string]any{"type": "roster", "room": view})
	}
}

func (s *Server) create(w http.ResponseWriter, req *http.Request) {
	var body struct {
		RoomID      string `json:"roomId"`
		OwnerID     string `json:"ownerId"`
		Nickname    string `json:"nickname"`
		OwnerToken  string `json:"ownerToken"`
		MemberToken string `json:"memberToken"`
	}
	if !decode(w, req, &body) {
		return
	}
	if !validID(body.RoomID) || !validID(body.OwnerID) || !validNickname(body.Nickname) || !validToken(body.OwnerToken) || !validToken(body.MemberToken) || same(body.OwnerToken, body.MemberToken) {
		fail(w, 400, "invalid_input", "Valid IDs, nickname and distinct 32-byte tokens are required")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.readyLocked(w) {
		return
	}
	r := s.rooms[body.RoomID]
	status := 200
	if r != nil {
		if !same(r.ownerToken, body.OwnerToken) || r.ownerID != body.OwnerID {
			fail(w, 403, "forbidden", "Owner credentials do not match")
			return
		}
		if !same(r.members[r.ownerID].token, body.MemberToken) {
			fail(w, 409, "token_conflict", "Member token rotation requires a separate secure flow")
			return
		}
		r.members[r.ownerID].nickname = body.Nickname
	} else {
		if len(s.rooms) >= s.config.MaxRooms {
			fail(w, 503, "room_limit", "Room limit reached")
			return
		}
		code, err := randomToken(12)
		if err != nil {
			fail(w, 503, "entropy_unavailable", "Cannot create credentials")
			return
		}
		r = &room{id: body.RoomID, code: code, ownerID: body.OwnerID, ownerToken: body.OwnerToken, members: make(map[string]*member), requests: make(map[string]*joinRequest), connections: make(map[*client]bool), expires: time.Now().Add(s.config.RoomTTL)}
		r.members[body.OwnerID] = &member{id: body.OwnerID, nickname: body.Nickname, token: body.MemberToken}
		s.rooms[r.id] = r
		s.codes[r.code] = r
		status = 201
	}
	if !s.persistHTTP(w) {
		return
	}
	s.rosterLocked(r)
	view := snapshot(r)
	writeJSON(w, status, map[string]any{"roomId": r.id, "inviteCode": r.code, "ownerId": r.ownerID, "ownerToken": body.OwnerToken, "memberToken": body.MemberToken, "members": view.Members, "maxTeamSize": MaxTeamSize})
}

func (s *Server) ownerAuthorized(w http.ResponseWriter, req *http.Request, r *room) bool {
	t := bearer(req)
	if t == "" {
		fail(w, 401, "unauthorized", "Bearer credentials required")
		return false
	}
	if !validToken(t) || !same(t, r.ownerToken) {
		fail(w, 403, "forbidden", "Owner authorization required")
		return false
	}
	return true
}

func (s *Server) roomAPI(w http.ResponseWriter, req *http.Request, path string) {
	parts := strings.Split(path, "/")
	if len(parts) == 0 || !validID(parts[0]) {
		fail(w, 400, "invalid_id", "Invalid room ID")
		return
	}
	// Decode before taking the state lock so slow HTTP clients cannot block audio.
	var upsert struct {
		ID       string `json:"id"`
		Nickname string `json:"nickname"`
		Token    string `json:"token"`
	}
	var decision struct {
		Accept *bool `json:"accept"`
	}
	if len(parts) == 2 && parts[1] == "members" && req.Method == "POST" {
		if !decode(w, req, &upsert) {
			return
		}
		if !validID(upsert.ID) || !validNickname(upsert.Nickname) || !validToken(upsert.Token) {
			fail(w, 400, "invalid_input", "Valid member ID, nickname and token required")
			return
		}
	}
	if len(parts) == 3 && parts[1] == "requests" && req.Method == "POST" {
		if !decode(w, req, &decision) {
			return
		}
		if decision.Accept == nil {
			fail(w, 400, "invalid_input", "accept is required")
			return
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.readyLocked(w) {
		return
	}
	r := s.rooms[parts[0]]
	if r == nil {
		fail(w, 404, "not_found", "Room unavailable")
		return
	}
	if len(parts) == 3 && parts[1] == "members" && req.Method == "DELETE" {
		if !validID(parts[2]) {
			fail(w, 400, "invalid_id", "Invalid member ID")
			return
		}
		m := r.members[parts[2]]
		if m == nil {
			fail(w, 404, "not_found", "Member unavailable")
			return
		}
		t := bearer(req)
		if t == "" {
			fail(w, 401, "unauthorized", "Bearer credentials required")
			return
		}
		if !validToken(t) || (!same(t, r.ownerToken) && !same(t, m.token)) {
			fail(w, 403, "forbidden", "Member or owner authorization required")
			return
		}
		result, err := s.removeMemberLocked(r, m.id)
		writeOperation(w, result, err)
		return
	}
	if !s.ownerAuthorized(w, req, r) {
		return
	}
	switch {
	case len(parts) == 1 && req.Method == "DELETE":
		result, err := s.adminActionLocked(r, "close", "")
		writeOperation(w, result, err)
	case len(parts) == 2 && parts[1] == "members" && req.Method == "POST":
		if same(upsert.Token, r.ownerToken) {
			fail(w, 400, "invalid_token", "Member credentials must differ from owner administration credentials")
			return
		}
		m := r.members[upsert.ID]
		if m != nil {
			if !same(m.token, upsert.Token) {
				fail(w, 409, "token_conflict", "Existing member token differs")
				return
			}
			m.nickname = upsert.Nickname
		} else {
			if len(r.members) >= MaxTeamSize {
				fail(w, 409, "room_full", "Room already has four members")
				return
			}
			r.members[upsert.ID] = &member{id: upsert.ID, nickname: upsert.Nickname, token: upsert.Token}
		}
		if !s.persistHTTP(w) {
			return
		}
		s.rosterLocked(r)
		writeJSON(w, 200, snapshot(r))
	case len(parts) == 2 && parts[1] == "requests" && req.Method == "GET":
		result, err := s.adminActionLocked(r, "requests", "")
		writeOperation(w, result, err)
	case len(parts) == 3 && parts[1] == "requests" && req.Method == "POST":
		action := "reject"
		if *decision.Accept {
			action = "approve"
		}
		result, err := s.adminActionLocked(r, action, parts[2])
		writeOperation(w, result, err)
	default:
		fail(w, 404, "not_found", "Unknown endpoint")
	}
}

func (s *Server) join(w http.ResponseWriter, req *http.Request) {
	var b struct {
		Code     string `json:"code"`
		MemberID string `json:"memberId"`
		Nickname string `json:"nickname"`
	}
	if !decode(w, req, &b) {
		return
	}
	if !validID(b.MemberID) || !validNickname(b.Nickname) || len(b.Code) != 16 {
		fail(w, 400, "invalid_input", "Valid code, member ID and nickname required")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.readyLocked(w) {
		return
	}
	r := s.codes[b.Code]
	if r == nil {
		fail(w, 404, "not_found", "Invite unavailable")
		return
	}
	if r.members[b.MemberID] != nil {
		fail(w, 409, "member_exists", "Member already belongs to room")
		return
	}
	if len(s.requests) >= s.config.MaxRequests || len(r.requests) >= 32 {
		fail(w, 429, "request_limit", "Join request limit reached")
		return
	}
	for _, q := range r.requests {
		if q.memberID == b.MemberID && q.status == "pending" {
			fail(w, 409, "request_exists", "Join request already pending")
			return
		}
	}
	id, err := randomID()
	if err != nil {
		fail(w, 503, "entropy_unavailable", "Cannot create credentials")
		return
	}
	t, err := randomToken(32)
	if err != nil {
		fail(w, 503, "entropy_unavailable", "Cannot create credentials")
		return
	}
	q := &joinRequest{id: id, token: t, memberID: b.MemberID, nickname: b.Nickname, status: "pending", room: r, expires: time.Now().Add(s.config.RequestTTL)}
	r.requests[id] = q
	s.requests[id] = q
	if owner := r.members[r.ownerID]; owner.route != nil {
		owner.route.control(map[string]any{"type": "join_request", "requestId": id, "memberId": b.MemberID, "nickname": b.Nickname})
	}
	writeJSON(w, 202, map[string]string{"requestId": id, "requestToken": t, "roomId": r.id, "ownerId": r.ownerID, "status": "pending"})
}

func (s *Server) poll(w http.ResponseWriter, req *http.Request, id string) {
	if !validID(id) {
		fail(w, 400, "invalid_id", "Invalid request ID")
		return
	}
	t := bearer(req)
	if t == "" {
		fail(w, 401, "unauthorized", "Bearer credentials required")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.readyLocked(w) {
		return
	}
	q := s.requests[id]
	if q == nil {
		fail(w, 404, "not_found", "Join request unavailable")
		return
	}
	if !validToken(t) || !same(t, q.token) {
		fail(w, 403, "forbidden", "Request credentials do not match")
		return
	}
	if q.status != "approved" {
		writeJSON(w, 200, map[string]string{"status": q.status})
		return
	}
	r := q.room
	view := snapshot(r)
	writeJSON(w, 200, map[string]any{"status": "approved", "roomId": r.id, "inviteCode": r.code, "ownerId": r.ownerID, "memberToken": q.memberToken, "members": view.Members, "maxTeamSize": MaxTeamSize})
}

var invitePage = template.Must(template.New("invite").Parse(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BLE Talkie invite</title><body><h1>BLE Talkie</h1><p>Open this invite in the Android app. Your room owner approves new members.</p>{{if .Code}}<p><a href="{{.Join}}">Open BLE Talkie</a></p><p>Invite code: {{.Code}}</p>{{else}}<p>Enter your invite code in the Android app.</p>{{end}}<p><a href="{{.Download}}">Download Android APK</a></p></body></html>`))

func (s *Server) invite(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("code")
	if code != "" {
		raw, err := base64.RawURLEncoding.DecodeString(code)
		if err != nil || len(raw) != 12 || base64.RawURLEncoding.EncodeToString(raw) != code {
			fail(w, 400, "invalid_code", "Invalid invite code")
			return
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'")
	// Only a validated base64url code is converted to a trusted custom URL.
	_ = invitePage.Execute(w, struct {
		Code     string
		Join     template.URL
		Download string
	}{code, template.URL("bletalkie://join?code=" + code), s.config.Prefix + "/download"})
}

func (s *Server) download(w http.ResponseWriter, r *http.Request) {
	if s.config.APKPath == "" {
		fail(w, 404, "apk_unavailable", "APK is not configured")
		return
	}
	f, err := os.Open(s.config.APKPath)
	if err != nil {
		fail(w, 404, "apk_unavailable", "APK is unavailable")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		fail(w, 404, "apk_unavailable", "APK is unavailable")
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="ble-talkie.apk"`)
	http.ServeContent(w, r, filepath.Base(s.config.APKPath), info.ModTime(), f)
}

func uuidBytes(id string) [16]byte {
	var out [16]byte
	b, _ := hex.DecodeString(strings.ReplaceAll(id, "-", ""))
	copy(out[:], b)
	return out
}
