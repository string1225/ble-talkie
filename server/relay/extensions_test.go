package relay_test

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/string1225/ble-talkie/server/relay"
)

func commandID(n int) string { return fmt.Sprintf("10000000-0000-4000-8000-%012x", n) }

func signedOwnerCommand(id, action, target string, gen uint64, issuedAt int64, challenge, key string) map[string]any {
	canonical := strings.Join([]string{"BTK-ADMIN-1", roomID, id, action, target, strconv.FormatUint(gen, 10), strconv.FormatInt(issuedAt, 10), challenge}, "\n")
	raw, _ := base64.RawURLEncoding.DecodeString(key)
	mac := hmac.New(sha256.New, raw)
	_, _ = mac.Write([]byte(canonical))
	return map[string]any{"type": "owner_command", "commandId": id, "roomId": roomID, "action": action, "targetId": target, "generation": gen, "issuedAt": issuedAt, "challenge": challenge, "signature": base64.RawURLEncoding.EncodeToString(mac.Sum(nil))}
}

func gatewayOwner(t *testing.T) (*fixture, *websocket.Conn, string) {
	t.Helper()
	f := setup(t)
	f.add(cID, "C", cToken)
	c := f.connect(cID, cToken, 1)
	authenticated := control(t, c, "authenticated")
	challenge, _ := authenticated["adminChallenge"].(string)
	raw, err := base64.RawURLEncoding.DecodeString(challenge)
	if err != nil || len(raw) != 32 {
		t.Fatal("authenticated response missing random admin challenge")
	}
	_ = c.WriteJSON(map[string]any{"type": "attach", "memberId": ownerID, "token": aToken, "generation": 10})
	attached := control(t, c, "attached")
	if attached["adminChallenge"] != challenge {
		t.Fatal("attached response changed socket challenge")
	}
	return f, c, challenge
}

func ownerResult(t *testing.T, c *websocket.Conn, cmd map[string]any, status, code string) map[string]any {
	t.Helper()
	if err := c.WriteJSON(cmd); err != nil {
		t.Fatal(err)
	}
	result := control(t, c, "owner_result")
	if result["commandId"] != cmd["commandId"] || result["action"] != cmd["action"] || result["status"] != status {
		t.Fatal("owner result identity or status mismatch")
	}
	if code != "" && result["error"] != code {
		t.Fatalf("owner result error=%v want %s", result["error"], code)
	}
	return result
}

func TestWithdrawPendingRejectedAndApprovedEnrollment(t *testing.T) {
	f := setup(t)
	for _, status := range []string{"pending", "rejected", "approved"} {
		q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
		id := q["requestId"].(string)
		ticket := q["requestToken"].(string)
		f.api("DELETE", "/api/join/"+id, token(9), nil, 403)
		var memberToken string
		var b *websocket.Conn
		if status != "pending" {
			f.api("POST", "/api/rooms/"+roomID+"/requests/"+id, ownerToken, map[string]any{"accept": status == "approved"}, 200)
		}
		if status == "approved" {
			approved := f.api("GET", "/api/join/"+id, ticket, nil, 200)
			memberToken = approved["memberToken"].(string)
			b = f.connect(bID, memberToken, 1)
			control(t, b, "authenticated")
			f.api("DELETE", "/api/join/"+id, token(9), nil, 403)
		}
		if got := f.api("DELETE", "/api/join/"+id, ticket, nil, 200); got["withdrawn"] != true {
			t.Fatal("withdraw did not acknowledge cancellation")
		}
		f.api("GET", "/api/join/"+id, ticket, nil, 404)
		f.api("DELETE", "/api/join/"+id, ticket, nil, 404)
		if requests := f.api("GET", "/api/rooms/"+roomID+"/requests", ownerToken, nil, 200)["requests"].([]any); len(requests) != 0 {
			t.Fatal("withdrawn request remains in pending list")
		}
		if status == "approved" {
			control(t, f.connect(bID, memberToken, 2), "error")
			_ = b.Close()
		}
	}
}

func TestWithdrawApprovedEnrollmentIsDurableAndFreesSlot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private", "state.json")
	f := setupConfig(t, relay.Config{StatePath: path})
	q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
	id := q["requestId"].(string)
	ticket := q["requestToken"].(string)
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+id, ownerToken, map[string]any{"accept": true}, 200)
	approved := f.api("GET", "/api/join/"+id, ticket, nil, 200)
	bt := approved["memberToken"].(string)
	f.add(cID, "C", cToken)
	f.add(dID, "D", token(5))
	f.api("DELETE", "/api/join/"+id, ticket, nil, 200)
	f.add(eID, "E", token(6))
	f.s.Close()
	_ = f.s.Config.Handler.(*relay.Server).Close()
	srv := relay.New(relay.Config{StatePath: path})
	server := httptest.NewServer(srv)
	defer server.Close()
	defer srv.Close()
	f.s = server
	control(t, f.connect(bID, bt, 2), "error")
	control(t, f.connect(eID, token(6), 1), "authenticated")
}

func TestFailedSignedOwnerActionConsumesCommandID(t *testing.T) {
	f, c, challenge := gatewayOwner(t)
	f.add(bID, "B", bToken)
	f.add(dID, "D", token(5))
	q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": eID, "nickname": "E"}, 202)
	cmd := signedOwnerCommand(commandID(31), "approve", q["requestId"].(string), 10, time.Now().UnixMilli(), challenge, ownerToken)
	ownerResult(t, c, cmd, "error", "room_full")
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+dID, ownerToken, nil, 200)
	ownerResult(t, c, cmd, "error", "command_replayed")
	ownerResult(t, c, signedOwnerCommand(commandID(32), "approve", q["requestId"].(string), 10, time.Now().UnixMilli(), challenge, ownerToken), "ok", "")
}

func TestSignedOwnerCommandCapNeverEvictsUnexpiredIDs(t *testing.T) {
	_, c, challenge := gatewayOwner(t)
	var first map[string]any
	for i := 1; i <= 256; i++ {
		cmd := signedOwnerCommand(commandID(1000+i), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken)
		if i == 1 {
			first = cmd
		}
		ownerResult(t, c, cmd, "ok", "")
		// Stay below the public40-text-controls/second limit while filling the
		// independent replay cache through real authenticated WebSocket traffic.
		if i%32 == 0 && i < 256 {
			time.Sleep(1050 * time.Millisecond)
		}
	}
	ownerResult(t, c, signedOwnerCommand(commandID(2000), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken), "error", "command_limit")
	ownerResult(t, c, first, "error", "command_replayed")
}

func TestSignedRemoveGatewayReturnsResultBeforeDisconnect(t *testing.T) {
	f, c, challenge := gatewayOwner(t)
	cmd := signedOwnerCommand(commandID(40), "remove", cID, 10, time.Now().UnixMilli(), challenge, ownerToken)
	ownerResult(t, c, cmd, "ok", "")
	control(t, c, "member_removed")
	control(t, f.connect(cID, cToken, 2), "error")
	a := f.connect(ownerID, aToken, 11)
	control(t, a, "authenticated")
}

func TestOldEnrollmentWithdrawalCannotRevokeReplacementMember(t *testing.T) {
	f := setup(t)
	q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
	id := q["requestId"].(string)
	ticket := q["requestToken"].(string)
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+id, ownerToken, map[string]any{"accept": true}, 200)
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+bID, ownerToken, nil, 200)
	f.add(bID, "replacement", bToken)
	f.api("DELETE", "/api/join/"+id, ticket, nil, 200)
	control(t, f.connect(bID, bToken, 1), "authenticated")
}

func TestSignedOwnerCommandsOverAttachedGateway(t *testing.T) {
	f, c, challenge := gatewayOwner(t)
	q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
	id := q["requestId"].(string)
	ticket := q["requestToken"].(string)
	cmd := signedOwnerCommand(commandID(1), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken)
	listed := ownerResult(t, c, cmd, "ok", "")["result"].(map[string]any)
	if len(listed["requests"].([]any)) != 1 {
		t.Fatal("owner request list omitted applicant")
	}
	ownerResult(t, c, signedOwnerCommand(commandID(2), "approve", id, 10, time.Now().UnixMilli(), challenge, ownerToken), "ok", "")
	approved := f.api("GET", "/api/join/"+id, ticket, nil, 200)
	if approved["status"] != "approved" {
		t.Fatal("signed approval did not accept applicant")
	}
	ownerResult(t, c, signedOwnerCommand(commandID(3), "remove", bID, 10, time.Now().UnixMilli(), challenge, ownerToken), "ok", "")
	control(t, f.connect(bID, approved["memberToken"].(string), 2), "error")
	q = f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": dID, "nickname": "D"}, 202)
	ownerResult(t, c, signedOwnerCommand(commandID(4), "reject", q["requestId"].(string), 10, time.Now().UnixMilli(), challenge, ownerToken), "ok", "")
	if got := f.api("GET", "/api/join/"+q["requestId"].(string), q["requestToken"].(string), nil, 200); got["status"] != "rejected" {
		t.Fatal("signed rejection did not reject applicant")
	}
	// Closing may supersede the final result with the normal room_closed event.
	_ = c.WriteJSON(signedOwnerCommand(commandID(5), "close", "", 10, time.Now().UnixMilli(), challenge, ownerToken))
	control(t, c, "room_closed")
	f.api("GET", "/api/rooms/"+roomID+"/requests", ownerToken, nil, 404)
}

func TestSignedOwnerCommandsRejectTamperingReplayAndStaleRoute(t *testing.T) {
	f, c, challenge := gatewayOwner(t)
	cmd := signedOwnerCommand(commandID(11), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken)
	ownerResult(t, c, cmd, "ok", "")
	ownerResult(t, c, cmd, "error", "command_replayed")
	bad := signedOwnerCommand(commandID(12), "requests", "", 10, time.Now().UnixMilli(), challenge, cToken)
	ownerResult(t, c, bad, "error", "invalid_signature")
	bad = signedOwnerCommand(commandID(13), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken)
	bad["action"] = "close"
	ownerResult(t, c, bad, "error", "invalid_signature")
	bad = signedOwnerCommand(commandID(14), "requests", "", 10, time.Now().UnixMilli(), token(8), ownerToken)
	ownerResult(t, c, bad, "error", "invalid_challenge")
	for i, delta := range []int64{-121000, 121000} {
		bad = signedOwnerCommand(commandID(15+i), "requests", "", 10, time.Now().UnixMilli()+delta, challenge, ownerToken)
		ownerResult(t, c, bad, "error", "invalid_timestamp")
	}
	bad = signedOwnerCommand(commandID(17), "requests", "", 9, time.Now().UnixMilli(), challenge, ownerToken)
	ownerResult(t, c, bad, "error", "owner_route")
	a := f.connect(ownerID, aToken, 11)
	direct := control(t, a, "authenticated")
	ownerResult(t, c, signedOwnerCommand(commandID(18), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken), "error", "owner_route")
	if direct["adminChallenge"] == challenge {
		t.Fatal("different socket reused admin challenge")
	}
}

func TestOwnerResultsArePrivateAndSocketChallengeChanges(t *testing.T) {
	f, c, challenge := gatewayOwner(t)
	f.add(bID, "B", bToken)
	b := f.connect(bID, bToken, 1)
	control(t, b, "authenticated")
	ownerResult(t, c, signedOwnerCommand(commandID(21), "requests", "", 10, time.Now().UnixMilli(), challenge, ownerToken), "ok", "")
	_ = b.WriteJSON(map[string]any{"type": "ping"})
	_ = b.SetReadDeadline(time.Now().Add(time.Second))
	for {
		var got map[string]any
		if err := b.ReadJSON(&got); err != nil {
			t.Fatal(err)
		}
		if got["type"] == "owner_result" {
			t.Fatal("owner result leaked to another physical socket")
		}
		if got["type"] == "pong" {
			break
		}
	}
	_ = c.Close()
	next := f.connect(cID, cToken, 2)
	auth := control(t, next, "authenticated")
	newChallenge, _ := auth["adminChallenge"].(string)
	if newChallenge == challenge || newChallenge == "" {
		t.Fatal("reconnect did not rotate admin challenge")
	}
	_ = next.WriteJSON(map[string]any{"type": "attach", "memberId": ownerID, "token": aToken, "generation": 11})
	control(t, next, "attached")
	ownerResult(t, next, signedOwnerCommand(commandID(22), "requests", "", 11, time.Now().UnixMilli(), challenge, ownerToken), "error", "invalid_challenge")
}
