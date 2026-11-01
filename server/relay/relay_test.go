package relay_test

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/string1225/ble-talkie/server/relay"
)

const (
	roomID  = "00000000-0000-4000-8000-000000000001"
	ownerID = "00000000-0000-4000-8000-00000000000a"
	bID     = "00000000-0000-4000-8000-00000000000b"
	cID     = "00000000-0000-4000-8000-00000000000c"
	dID     = "00000000-0000-4000-8000-00000000000d"
	eID     = "00000000-0000-4000-8000-00000000000e"
)

var (
	ownerToken = token(1)
	aToken     = token(2)
	bToken     = token(3)
	cToken     = token(4)
)

func token(n byte) string { return base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{n}, 32)) }

type fixture struct {
	t    *testing.T
	s    *httptest.Server
	code string
}

func setup(t *testing.T) *fixture {
	return setupConfig(t, relay.Config{})
}

func setupConfig(t *testing.T, cfg relay.Config) *fixture {
	t.Helper()
	srv := relay.New(cfg)
	s := httptest.NewServer(srv)
	t.Cleanup(func() { s.Close(); _ = srv.Close() })
	f := &fixture{t: t, s: s}
	created := f.api("POST", "/api/rooms", "", map[string]any{"roomId": roomID, "ownerId": ownerID, "nickname": "A", "ownerToken": ownerToken, "memberToken": aToken}, 201)
	f.code, _ = created["inviteCode"].(string)
	if f.code == "" {
		t.Fatal("create missing inviteCode")
	}
	return f
}

func (f *fixture) api(method, path, bearer string, body any, status int) map[string]any {
	f.t.Helper()
	var r io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			f.t.Fatal(err)
		}
		r = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, f.s.URL+"/ble-talkie"+path, r)
	if err != nil {
		f.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		f.t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	data, _ := io.ReadAll(res.Body)
	if res.StatusCode != status {
		f.t.Fatalf("%s %s status=%d want %d (response body omitted to protect credentials)", method, path, res.StatusCode, status)
	}
	if len(data) > 0 {
		if err := json.Unmarshal(data, &out); err != nil {
			f.t.Fatal("non-JSON response (body omitted to protect credentials)")
		}
	}
	return out
}

func (f *fixture) add(id, name, tok string) {
	f.t.Helper()
	f.api("POST", "/api/rooms/"+roomID+"/members", ownerToken, map[string]any{"id": id, "nickname": name, "token": tok}, 200)
}

func (f *fixture) connect(id, tok string, gen int) *websocket.Conn {
	f.t.Helper()
	c, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(f.s.URL, "http")+"/ble-talkie/ws", nil)
	if err != nil {
		f.t.Fatal(err)
	}
	f.t.Cleanup(func() { _ = c.Close() })
	if err = c.WriteJSON(map[string]any{"type": "auth", "roomId": roomID, "memberId": id, "token": tok, "generation": gen}); err != nil {
		f.t.Fatal(err)
	}
	return c
}

func control(t *testing.T, c *websocket.Conn, want string) map[string]any {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		typ, b, err := c.ReadMessage()
		if err != nil {
			t.Fatalf("waiting %s: %v", want, err)
		}
		if typ != websocket.TextMessage {
			t.Fatalf("expected control %s, got binary", want)
		}
		var out map[string]any
		if err = json.Unmarshal(b, &out); err != nil {
			t.Fatal(err)
		}
		if out["type"] == want {
			return out
		}
		if out["type"] == "error" {
			t.Fatalf("waiting %s got error: %s", want, b)
		}
	}
}

func audio(room, sender string, epoch uint64, seq uint32) []byte {
	b := make([]byte, 61)
	copy(b, "BTK1")
	r, _ := hex.DecodeString(strings.ReplaceAll(room, "-", ""))
	copy(b[4:20], r)
	s, _ := hex.DecodeString(strings.ReplaceAll(sender, "-", ""))
	copy(b[20:36], s)
	binary.BigEndian.PutUint64(b[36:44], epoch)
	binary.BigEndian.PutUint32(b[44:48], seq)
	binary.BigEndian.PutUint64(b[48:56], 123)
	binary.BigEndian.PutUint16(b[56:58], 3)
	copy(b[58:], []byte{1, 2, 3})
	return b
}

func readAudio(t *testing.T, c *websocket.Conn, want []byte) {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		typ, b, err := c.ReadMessage()
		if err != nil {
			t.Fatal(err)
		}
		if typ == websocket.BinaryMessage {
			if !bytes.Equal(b, want) {
				t.Fatalf("unexpected audio %x", b)
			}
			return
		}
		var ctl map[string]any
		_ = json.Unmarshal(b, &ctl)
		if ctl["type"] == "error" {
			t.Fatalf("unexpected error %s", b)
		}
	}
}

func noAudio(t *testing.T, c *websocket.Conn) {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(120 * time.Millisecond))
	for {
		typ, b, err := c.ReadMessage()
		if err != nil {
			if e, ok := err.(interface{ Timeout() bool }); ok && e.Timeout() {
				return
			}
			t.Fatalf("connection failed: %v", err)
		}
		if typ == websocket.BinaryMessage {
			t.Fatalf("unexpected audio %x", b)
		}
	}
}

func TestHealthPrefixAndMalformedInput(t *testing.T) {
	srv := relay.New(relay.Config{})
	s := httptest.NewServer(srv)
	defer s.Close()
	defer srv.Close()
	for _, path := range []string{"/ble-talkie/healthz", "/ble-talkie/health", "/ble-talkie/"} {
		res, err := http.Get(s.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		data, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 || !bytes.Contains(data, []byte("ready")) {
			t.Fatalf("health %s: %d %s", path, res.StatusCode, data)
		}
	}
	for _, body := range []string{`{}`, `{"roomId":"BAD"}`, `{"roomId":1}`, `{} {}`, strings.Repeat("x", 20000)} {
		req, _ := http.NewRequest("POST", s.URL+"/ble-talkie/api/rooms", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode < 400 || res.StatusCode >= 500 || !json.Valid(b) {
			t.Fatalf("malformed returns %d %s", res.StatusCode, b)
		}
	}
	res, _ := http.Get(s.URL + "/health")
	res.Body.Close()
	if res.StatusCode != 404 {
		t.Fatal("unprefixed health leaked")
	}
}

func TestApprovalRequiredAndOwnerAuthorization(t *testing.T) {
	f := setup(t)
	r := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": cID, "nickname": "C"}, 202)
	id := r["requestId"].(string)
	rt := r["requestToken"].(string)
	f.api("GET", "/api/join/"+id, "", nil, 401)
	f.api("GET", "/api/rooms/"+roomID+"/requests", aToken, nil, 403)
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+id, aToken, map[string]any{"accept": true}, 403)
	c := f.connect(cID, cToken, 1)
	if got := control(t, c, "error"); got["code"] != "unauthorized" {
		t.Fatal(got)
	}
	pending := f.api("GET", "/api/join/"+id, rt, nil, 200)
	if pending["status"] != "pending" {
		t.Fatal(pending)
	}
	list := f.api("GET", "/api/rooms/"+roomID+"/requests", ownerToken, nil, 200)
	if len(list["requests"].([]any)) != 1 {
		t.Fatal(list)
	}
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+id, ownerToken, map[string]any{"accept": true}, 200)
	approved := f.api("GET", "/api/join/"+id, rt, nil, 200)
	if approved["status"] != "approved" || approved["roomId"] != roomID || approved["memberToken"] == "" || approved["maxTeamSize"] != float64(4) {
		t.Fatal("approved response is missing required flat fields")
	}
	control(t, f.connect(cID, approved["memberToken"].(string), 1), "authenticated")
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+cID, aToken, nil, 403)
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+cID, approved["memberToken"].(string), nil, 200)
	control(t, f.connect(cID, approved["memberToken"].(string), 2), "error")
}

func TestRoomImportPreservesMembersAndCapacity(t *testing.T) {
	f := setup(t)
	f.add(bID, "B", bToken)
	f.add(cID, "C", cToken)
	f.add(dID, "D", token(5))
	f.api("POST", "/api/rooms/"+roomID+"/members", ownerToken, map[string]any{"id": eID, "nickname": "E", "token": token(6)}, 409)
	f.api("POST", "/api/rooms/"+roomID+"/members", ownerToken, map[string]any{"id": bID, "nickname": "B", "token": token(7)}, 409)
	body := map[string]any{"roomId": roomID, "ownerId": ownerID, "nickname": "A", "ownerToken": ownerToken, "memberToken": aToken}
	got := f.api("POST", "/api/rooms", "", body, 200)
	if len(got["members"].([]any)) != 4 {
		t.Fatal("import did not preserve all members")
	}
	body["ownerToken"] = token(9)
	f.api("POST", "/api/rooms", "", body, 403)
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+bID, ownerToken, nil, 200)
	f.add(eID, "E", token(6))
}

func TestGatewayFanoutNoEchoAndGenerationTakeover(t *testing.T) {
	f := setup(t)
	f.add(bID, "B", bToken)
	f.add(cID, "C", cToken)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	_ = a.WriteJSON(map[string]any{"type": "attach", "memberId": bID, "token": bToken, "generation": 1})
	control(t, a, "attached")
	c := f.connect(cID, cToken, 1)
	control(t, c, "authenticated")
	bFrame := audio(roomID, bID, 9, 1)
	_ = a.WriteMessage(websocket.BinaryMessage, bFrame)
	readAudio(t, c, bFrame)
	cFrame := audio(roomID, cID, 11, 1)
	_ = c.WriteMessage(websocket.BinaryMessage, cFrame)
	readAudio(t, a, cFrame)
	// A owns two logical members; C's frame appears once on A's physical socket.
	// Takeover then an old gateway send must be rejected without displacing B.
	b := f.connect(bID, bToken, 2)
	control(t, b, "authenticated")
	_ = a.WriteJSON(map[string]any{"type": "attach", "memberId": bID, "token": bToken, "generation": 1})
	if got := control(t, a, "error"); got["code"] != "stale_generation" {
		t.Fatal(got)
	}
	_ = a.WriteMessage(websocket.BinaryMessage, audio(roomID, bID, 9, 2))
	if got := control(t, a, "error"); got["code"] != "source_not_owned" {
		t.Fatal(got)
	}
	bFrame = audio(roomID, bID, 9, 3)
	_ = b.WriteMessage(websocket.BinaryMessage, bFrame)
	readAudio(t, a, bFrame)
	readAudio(t, c, bFrame)
	// Replaying the frame is silently suppressed, as is any ingress echo.
	_ = b.WriteMessage(websocket.BinaryMessage, bFrame)
	noAudio(t, a)
	noAudio(t, c)
	noAudio(t, b)
}

func TestAudioValidationAndImmediateRevocation(t *testing.T) {
	f := setup(t)
	f.add(bID, "B", bToken)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	b := f.connect(bID, bToken, 1)
	control(t, b, "authenticated")
	for _, frame := range [][]byte{audio(roomID, cID, 1, 1), audio(cID, ownerID, 1, 1), []byte("BTK1"), append(audio(roomID, ownerID, 1, 1), 1), make([]byte, 2000)} {
		_ = a.WriteMessage(websocket.BinaryMessage, frame)
		control(t, a, "error")
	}
	frame := audio(roomID, ownerID, 1, 2)
	_ = a.WriteMessage(websocket.BinaryMessage, frame)
	readAudio(t, b, frame)
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+bID, ownerToken, nil, 200)
	_ = b.SetReadDeadline(time.Now().Add(time.Second))
	for {
		_, _, err := b.ReadMessage()
		if err != nil {
			break
		}
	}
	control(t, f.connect(bID, bToken, 2), "error")
}

func TestClosedConnectionDoesNotDetachNewRoute(t *testing.T) {
	f := setup(t)
	f.add(bID, "B", bToken)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	old := f.connect(bID, bToken, 1)
	control(t, old, "authenticated")
	next := f.connect(bID, bToken, 2)
	control(t, next, "authenticated")
	_ = old.Close()
	frame := audio(roomID, bID, 1, 1)
	_ = next.WriteMessage(websocket.BinaryMessage, frame)
	readAudio(t, a, frame)
}

func TestWebSocketOriginPolicy(t *testing.T) {
	f := setup(t)
	h := http.Header{"Origin": []string{"https://evil.example"}}
	c, res, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(f.s.URL, "http")+"/ble-talkie/ws", h)
	if c != nil {
		c.Close()
	}
	if err == nil || res.StatusCode != 403 {
		t.Fatal("cross-origin socket accepted")
	}
}

func TestDurableRestartPreservesInviteAndCredentialsOffline(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private", "state.json")
	srv := relay.New(relay.Config{StatePath: path})
	s := httptest.NewServer(srv)
	f := &fixture{t: t, s: s}
	created := f.api("POST", "/api/rooms", "", map[string]any{"roomId": roomID, "ownerId": ownerID, "nickname": "A", "ownerToken": ownerToken, "memberToken": aToken}, 201)
	code := created["inviteCode"].(string)
	f.add(bID, "B", bToken)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	s.Close()
	_ = srv.Close()
	srv2 := relay.New(relay.Config{StatePath: path})
	s2 := httptest.NewServer(srv2)
	defer s2.Close()
	defer srv2.Close()
	f.s = s2
	reopened := f.api("POST", "/api/rooms", "", map[string]any{"roomId": roomID, "ownerId": ownerID, "nickname": "A", "ownerToken": ownerToken, "memberToken": aToken}, 200)
	if reopened["inviteCode"] != code || len(reopened["members"].([]any)) != 2 {
		t.Fatal("room metadata did not survive restart")
	}
	for _, raw := range reopened["members"].([]any) {
		if raw.(map[string]any)["online"] != false {
			t.Fatal("route persisted across restart")
		}
	}
	control(t, f.connect(bID, bToken, 2), "authenticated")
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
	// Corrupt state must never silently produce a fresh, apparently ready relay.
	_ = srv2.Close()
	if err := os.WriteFile(path, []byte(`{"version":1,"rooms":[`), 0600); err != nil {
		t.Fatal(err)
	}
	srv3 := relay.New(relay.Config{StatePath: path})
	defer srv3.Close()
	res := httptest.NewRecorder()
	srv3.ServeHTTP(res, httptest.NewRequest("GET", "/ble-talkie/health", nil))
	if res.Code != 503 {
		t.Fatalf("corrupt state readiness=%d", res.Code)
	}
}

func TestOriginSchemeMustMatch(t *testing.T) {
	f := setup(t)
	h := http.Header{"Origin": []string{"https://" + strings.TrimPrefix(f.s.URL, "http://")}}
	c, res, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(f.s.URL, "http")+"/ble-talkie/ws", h)
	if c != nil {
		c.Close()
	}
	if err == nil || res.StatusCode != 403 {
		t.Fatal("different origin scheme accepted")
	}
}

func TestRejectionAndFourthApprovalLimit(t *testing.T) {
	f := setup(t)
	first := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+first["requestId"].(string), ownerToken, map[string]any{"accept": false}, 200)
	got := f.api("GET", "/api/join/"+first["requestId"].(string), first["requestToken"].(string), nil, 200)
	if got["status"] != "rejected" {
		t.Fatal("rejection not visible")
	}
	f.add(bID, "B", bToken)
	f.add(cID, "C", cToken)
	f.add(dID, "D", token(5))
	next := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": eID, "nickname": "E"}, 202)
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+next["requestId"].(string), ownerToken, map[string]any{"accept": true}, 409)
	f.api("GET", "/api/join/"+next["requestId"].(string), token(8), nil, 403)
	if got := f.api("GET", "/api/join/"+next["requestId"].(string), next["requestToken"].(string), nil, 200); got["status"] != "pending" {
		t.Fatal("failed approval changed membership")
	}
}

func TestPendingRoomExpiryAndRoomClosedEvent(t *testing.T) {
	f := setupConfig(t, relay.Config{RequestTTL: 25 * time.Millisecond, RoomTTL: 300 * time.Millisecond})
	q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	time.Sleep(35 * time.Millisecond)
	f.api("GET", "/api/join/"+q["requestId"].(string), q["requestToken"].(string), nil, 404)
	f.api("DELETE", "/api/rooms/"+roomID, ownerToken, nil, 200)
	control(t, a, "room_closed")
	f.api("GET", "/api/rooms/"+roomID+"/requests", ownerToken, nil, 404)
	f2 := setupConfig(t, relay.Config{RoomTTL: 25 * time.Millisecond})
	time.Sleep(35 * time.Millisecond)
	f2.api("GET", "/api/rooms/"+roomID+"/requests", ownerToken, nil, 404)
}

func TestUnauthenticatedSocketTimeoutAndConnectionLimit(t *testing.T) {
	f := setupConfig(t, relay.Config{AuthTimeout: 60 * time.Millisecond, MaxConnections: 1})
	url := "ws" + strings.TrimPrefix(f.s.URL, "http") + "/ble-talkie/ws"
	c, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	other, res, err := websocket.DefaultDialer.Dial(url, nil)
	if other != nil {
		other.Close()
	}
	if err == nil || res.StatusCode != 503 {
		t.Fatal("global connection limit did not apply")
	}
	_ = c.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err = c.ReadMessage(); err == nil {
		t.Fatal("unauthenticated socket did not close")
	}
	control(t, f.connect(ownerID, aToken, 1), "authenticated")
}

func TestInviteDownloadAndEnvironment(t *testing.T) {
	path := filepath.Join(t.TempDir(), "app.apk")
	if err := os.WriteFile(path, []byte("APK fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	f := setupConfig(t, relay.Config{APKPath: path})
	for _, item := range []struct{ path, part string }{{"/invite?code=" + f.code, "bletalkie://join?code=" + f.code}, {"/download", "APK fixture"}} {
		res, err := http.Get(f.s.URL + "/ble-talkie" + item.path)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 || !bytes.Contains(b, []byte(item.part)) {
			t.Fatalf("invalid invite/download result %d", res.StatusCode)
		}
	}
	f.api("GET", "/invite?code=%3Cscript%3E", "", nil, 400)
	t.Setenv("BLE_TALKIE_STATE", filepath.Join(t.TempDir(), "state.json"))
	t.Setenv("BLE_TALKIE_PREFIX", "/custom")
	t.Setenv("BLE_TALKIE_APK", path)
	t.Setenv("BLE_TALKIE_LISTEN", "")
	cfg, err := relay.ConfigFromEnv()
	if err != nil || cfg.StatePath == "" || cfg.Prefix != "/custom" || cfg.APKPath != path || cfg.ListenAddr != "127.0.0.1:18110" {
		t.Fatal("environment config mismatch")
	}
	srv := relay.New(cfg)
	defer srv.Close()
	r := httptest.NewRecorder()
	srv.ServeHTTP(r, httptest.NewRequest("GET", "/custom/health", nil))
	if r.Code != 200 {
		t.Fatal("custom prefix failed")
	}
	t.Setenv("BLE_TALKIE_PREFIX", "/unsafe/../path")
	if _, err := relay.ConfigFromEnv(); err == nil {
		t.Fatal("invalid configured prefix accepted")
	}
}

func TestSlowVoiceConsumerDoesNotBlockControls(t *testing.T) {
	f := setup(t)
	f.add(bID, "B", bToken)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	b := f.connect(bID, bToken, 1)
	control(t, b, "authenticated")
	if tcp, ok := b.UnderlyingConn().(*net.TCPConn); ok {
		if err := tcp.SetReadBuffer(1024); err != nil {
			t.Fatal(err)
		}
	}
	// B deliberately stops reading. A can continue voice and control traffic;
	// bounded audio queues drop frames rather than blocking the room lock.
	frame := make([]byte, relay.AudioHeaderSize+relay.MaxOpusPayload)
	copy(frame, audio(roomID, ownerID, 1, 1))
	binary.BigEndian.PutUint16(frame[56:58], relay.MaxOpusPayload)
	for seq := uint32(1); seq <= 360; seq++ {
		binary.BigEndian.PutUint32(frame[44:48], seq)
		if err := a.WriteMessage(websocket.BinaryMessage, frame); err != nil {
			t.Fatal(err)
		}
		if seq%10 == 0 {
			time.Sleep(30 * time.Millisecond)
		}
	}
	if err := a.WriteJSON(map[string]any{"type": "ping"}); err != nil {
		t.Fatal(err)
	}
	control(t, a, "pong")
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+bID, ownerToken, nil, 200)
	// Removal still completes while B's voice writer is back-pressured.
	f.add(cID, "C", cToken)
}

func TestUnsolicitedPongCannotExtendAuthenticationDeadline(t *testing.T) {
	f := setupConfig(t, relay.Config{AuthTimeout: 40 * time.Millisecond, MaxConnections: 1})
	c, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(f.s.URL, "http")+"/ble-talkie/ws", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for i := 0; i < 8; i++ {
		_ = c.WriteControl(websocket.PongMessage, nil, time.Now().Add(time.Second))
		time.Sleep(10 * time.Millisecond)
	}
	_ = c.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
	_, _, err = c.ReadMessage()
	if err == nil {
		t.Fatal("unauthenticated socket remained open")
	}
	if e, ok := err.(interface{ Timeout() bool }); ok && e.Timeout() {
		t.Fatal("Pong bypassed initial authentication deadline")
	}
	control(t, f.connect(ownerID, aToken, 1), "authenticated")
}

func TestMemberUpsertCannotReuseOwnerAdministrationCredential(t *testing.T) {
	f := setup(t)
	f.api("POST", "/api/rooms/"+roomID+"/members", ownerToken, map[string]any{"id": bID, "nickname": "B", "token": ownerToken}, 400)
}

func TestGenerationBoundariesAndEqualReconnect(t *testing.T) {
	f := setup(t)
	f.add(bID, "B", bToken)
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	for _, gen := range []any{0, -1, uint64(1) << 63} {
		_ = a.WriteJSON(map[string]any{"type": "attach", "memberId": bID, "token": bToken, "generation": gen})
		control(t, a, "error")
	}
	b := f.connect(bID, bToken, 7)
	control(t, b, "authenticated")
	control(t, f.connect(bID, bToken, 7), "error")
	_ = b.Close()
	// Observe route cleanup before attempting an equal generation reconnect.
	for {
		roster := control(t, a, "roster")["room"].(map[string]any)
		offline := false
		for _, raw := range roster["members"].([]any) {
			m := raw.(map[string]any)
			if m["id"] == bID && m["online"] == false {
				offline = true
			}
		}
		if offline {
			break
		}
	}
	b2 := f.connect(bID, bToken, 7)
	control(t, b2, "authenticated")
	_ = a.WriteJSON(map[string]any{"type": "attach", "memberId": bID, "token": bToken, "generation": 6})
	if got := control(t, a, "error"); got["code"] != "stale_generation" {
		t.Fatal("lower generation accepted")
	}
	_ = b2.Close()
}

func TestDurableApprovalRevocationAndCloseSurviveRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private", "state.json")
	var srv *relay.Server
	var server *httptest.Server
	f := &fixture{t: t}
	open := func() { srv = relay.New(relay.Config{StatePath: path}); server = httptest.NewServer(srv); f.s = server }
	stop := func() { server.Close(); _ = srv.Close() }
	open()
	defer func() { stop() }()
	created := f.api("POST", "/api/rooms", "", map[string]any{"roomId": roomID, "ownerId": ownerID, "nickname": "A", "ownerToken": ownerToken, "memberToken": aToken}, 201)
	f.code = created["inviteCode"].(string)
	q := f.api("POST", "/api/join", "", map[string]any{"code": f.code, "memberId": bID, "nickname": "B"}, 202)
	f.api("POST", "/api/rooms/"+roomID+"/requests/"+q["requestId"].(string), ownerToken, map[string]any{"accept": true}, 200)
	approved := f.api("GET", "/api/join/"+q["requestId"].(string), q["requestToken"].(string), nil, 200)
	bt := approved["memberToken"].(string)
	stop()
	open()
	control(t, f.connect(bID, bt, 1), "authenticated")
	f.api("DELETE", "/api/rooms/"+roomID+"/members/"+bID, ownerToken, nil, 200)
	stop()
	open()
	control(t, f.connect(bID, bt, 2), "error")
	f.api("DELETE", "/api/rooms/"+roomID, ownerToken, nil, 200)
	stop()
	open()
	f.api("GET", "/api/rooms/"+roomID+"/requests", ownerToken, nil, 404)
}

func TestDurableWriteFailureFailsClosed(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	path := filepath.Join(dir, "state.json")
	f := setupConfig(t, relay.Config{StatePath: path})
	a := f.connect(ownerID, aToken, 1)
	control(t, a, "authenticated")
	// Replacing the configured directory with a regular file deterministically
	// makes atomic writes fail even when this test runs as an administrator.
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(dir); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dir, []byte("blocked"), 0600); err != nil {
		t.Fatal(err)
	}
	f.api("POST", "/api/rooms/"+roomID+"/members", ownerToken, map[string]any{"id": bID, "nickname": "B", "token": bToken}, 503)
	f.api("GET", "/health", "", nil, 503)
	_ = a.SetReadDeadline(time.Now().Add(time.Second))
	for {
		_, _, err := a.ReadMessage()
		if err != nil {
			if e, ok := err.(interface{ Timeout() bool }); ok && e.Timeout() {
				t.Fatal("storage failure did not close active socket")
			}
			break
		}
	}
}

func TestShutdownDuringBodyDecodeCannotAcknowledgeMutation(t *testing.T) {
	srv := relay.New(relay.Config{})
	defer srv.Close()
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	req := httptest.NewRequest("POST", "/ble-talkie/api/rooms", reader)
	req.Header.Set("Content-Type", "application/json")
	res := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { srv.ServeHTTP(res, req); close(done) }()
	body, _ := json.Marshal(map[string]any{"roomId": roomID, "ownerId": ownerID, "nickname": "A", "ownerToken": ownerToken, "memberToken": aToken})
	if _, err := writer.Write(body[:1]); err != nil {
		t.Fatal(err)
	}
	_ = srv.Close()
	if _, err := writer.Write(body[1:]); err != nil {
		t.Fatal(err)
	}
	_ = writer.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("body handler stuck")
	}
	if res.Code != 503 {
		t.Fatalf("mutation acknowledged after shutdown: %d", res.Code)
	}
}
