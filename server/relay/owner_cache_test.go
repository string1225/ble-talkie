package relay

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"testing"
	"time"
)

func TestOwnerCommandPrunesOnlyExpiredIDs(t *testing.T) {
	s := New(Config{})
	defer s.Close()
	key := make([]byte, 32)
	tok := base64.RawURLEncoding.EncodeToString(key)
	r := &room{id: "00000000-0000-4000-8000-000000000001", ownerID: "00000000-0000-4000-8000-00000000000a", ownerToken: tok, members: make(map[string]*member), requests: make(map[string]*joinRequest), expires: time.Now().Add(time.Hour)}
	c := &client{primary: r.ownerID, room: r, routes: map[string]bool{r.ownerID: true}, controls: make(chan []byte, 32), done: make(chan struct{}), adminChallenge: tok, adminCommands: make(map[string]int64)}
	r.members[r.ownerID] = &member{id: r.ownerID, route: c, generation: 1}
	s.rooms[r.id] = r
	for i := 0; i < maxOwnerCommands; i++ {
		c.adminCommands[fmt.Sprintf("expired-%d", i)] = time.Now().UnixMilli() - 1
	}
	msg := controlMessage{Type: "owner_command", RoomID: r.id, CommandID: "10000000-0000-4000-8000-000000000001", Action: "requests", Generation: 1, IssuedAt: time.Now().UnixMilli(), Challenge: tok}
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte(ownerCanonical(msg)))
	msg.Signature = base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	s.ownerCommand(c, msg)
	var result map[string]any
	if err := json.Unmarshal(<-c.controls, &result); err != nil {
		t.Fatal(err)
	}
	if result["status"] != "ok" || len(c.adminCommands) != 1 || c.adminCommands[msg.CommandID] != msg.IssuedAt+ownerCommandWindow {
		t.Fatal("expired IDs were not safely replaced by accepted command")
	}
}

// Shared with the independently calculated Node fixture and Android signer.
func TestOwnerHMACMatchesIndependentGoldenVector(t *testing.T) {
	const ownerToken = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
	msg := controlMessage{Type: "owner_command", RoomID: "12a5b83c-0321-49ab-8245-039d35629873", CommandID: "ff000000-0000-4000-8000-000000000001", Action: "approve", TargetID: "ee000000-0000-4000-8000-000000000001", Generation: 7, IssuedAt: 1793534400000, Challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Signature: "DDciPaZroQworCauBWe6K3Rnw0ZieWhO9pz8KcXiICM"}
	const ownerID = "00000000-0000-4000-8000-00000000000a"
	c := &client{primary: ownerID, routes: map[string]bool{ownerID: true}, adminChallenge: msg.Challenge}
	r := &room{id: msg.RoomID, ownerID: ownerID, ownerToken: ownerToken, members: map[string]*member{ownerID: {id: ownerID, route: c, generation: 7}}}
	if err := validateOwnerCommand(c, r, msg, msg.IssuedAt); err != nil {
		t.Fatalf("independent HMAC fixture rejected: %s", err.code)
	}
}
