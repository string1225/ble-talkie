package relay

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// Exercise the invariant while the removed connection's writer has not exited.
// Its bounded terminal queue intentionally remains undrained in this test.
func TestPrimaryRemovalImmediatelyReleasesDelegatedRoutes(t *testing.T) {
	s := New(Config{})
	defer s.Close()
	ownerToken := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	r := &room{id: "00000000-0000-4000-8000-000000000001", ownerID: "00000000-0000-4000-8000-00000000000a", ownerToken: ownerToken, members: make(map[string]*member), requests: make(map[string]*joinRequest), connections: make(map[*client]bool), expires: time.Now().Add(time.Hour)}
	bID := "00000000-0000-4000-8000-00000000000b"
	cID := "00000000-0000-4000-8000-00000000000c"
	c := &client{primary: bID, room: r, routes: map[string]bool{bID: true, cID: true}, controls: make(chan []byte, 32), terminal: make(chan [][]byte, 1), done: make(chan struct{})}
	r.members[r.ownerID] = &member{id: r.ownerID}
	r.members[bID] = &member{id: bID, route: c}
	r.members[cID] = &member{id: cID, route: c, token: base64.RawURLEncoding.EncodeToString(make([]byte, 32))}
	r.connections[c] = true
	s.rooms[r.id] = r
	res := httptest.NewRecorder()
	req := httptest.NewRequest("DELETE", "/ble-talkie/api/rooms/"+r.id+"/members/"+bID, nil)
	req.Header.Set("Authorization", "Bearer "+ownerToken)
	s.ServeHTTP(res, req)
	if res.Code != 200 {
		t.Fatalf("delete returned %d", res.Code)
	}
	if r.members[cID].route != nil || len(c.routes) != 0 {
		t.Fatal("revoked primary retained delegated route rights until writer cleanup")
	}
	// Reusing the slot/ID must not revive the revoked physical session.
	r.members[bID] = &member{id: bID, token: "new credential"}
	s.command(c, controlMessage{Type: "attach", MemberID: cID, Token: r.members[cID].token, Generation: 1})
	if len(c.routes) != 0 || r.members[cID].route != nil {
		t.Fatal("re-added primary identity revived a revoked socket")
	}
}

func TestAudioQueuedBeforeStateLockContentionExpiresBeforeWrite(t *testing.T) {
	s := New(Config{})
	defer s.Close()
	server := httptest.NewServer(s)
	defer server.Close()
	token := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	aID := "00000000-0000-4000-8000-00000000000a"
	bID := "00000000-0000-4000-8000-00000000000b"
	r := &room{id: "00000000-0000-4000-8000-000000000001", ownerID: aID, members: make(map[string]*member), requests: make(map[string]*joinRequest), connections: make(map[*client]bool), expires: time.Now().Add(time.Hour)}
	source := &client{primary: aID, room: r, routes: map[string]bool{aID: true}}
	r.members[aID] = &member{id: aID, route: source, generation: 1}
	r.members[bID] = &member{id: bID, token: token}
	s.mu.Lock()
	s.rooms[r.id] = r
	s.mu.Unlock()
	c, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/ble-talkie/ws", http.Header{})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := c.WriteJSON(controlMessage{Type: "auth", RoomID: r.id, MemberID: bID, Token: token, Generation: 1}); err != nil {
		t.Fatal(err)
	}
	var authenticated map[string]any
	if err := c.ReadJSON(&authenticated); err != nil {
		t.Fatal(err)
	}
	s.mu.Lock()
	target := r.members[bID].route
	target.audio <- queuedAudio{data: []byte("stale-audio"), created: time.Now(), source: source, memberID: aID, generation: 1}
	deadline := time.Now().Add(time.Second)
	for len(target.audio) > 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(target.audio) > 0 {
		s.mu.Unlock()
		t.Fatal("writer never dequeued audio")
	}
	time.Sleep(maxAudioAge + 25*time.Millisecond)
	s.mu.Unlock()
	_ = c.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	for {
		typ, _, err := c.ReadMessage()
		if err != nil {
			if timeout, ok := err.(interface{ Timeout() bool }); ok && timeout.Timeout() {
				return
			}
			t.Fatal(err)
		}
		if typ == websocket.BinaryMessage {
			t.Fatal("audio expired while waiting for state lock was transmitted")
		}
	}
}
