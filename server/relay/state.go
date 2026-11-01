package relay

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"time"
)

type diskState struct {
	Version int        `json:"version"`
	Rooms   []diskRoom `json:"rooms"`
}
type diskRoom struct {
	ID         string       `json:"roomId"`
	InviteCode string       `json:"inviteCode"`
	OwnerID    string       `json:"ownerId"`
	OwnerToken string       `json:"ownerToken"`
	Expires    time.Time    `json:"expires"`
	Members    []diskMember `json:"members"`
}
type diskMember struct {
	ID       string `json:"id"`
	Nickname string `json:"nickname"`
	Token    string `json:"token"`
}

// Err returns a safe readiness error; it never contains state contents or tokens.
func (s *Server) Err() error { s.mu.Lock(); defer s.mu.Unlock(); return s.storageErr }

func (s *Server) loadState() error {
	path := s.config.StatePath
	if path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return errors.New("cannot create private state directory")
	}
	info, err := os.Stat(filepath.Dir(path))
	if err != nil || !info.IsDir() {
		return errors.New("state directory unavailable")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0 {
		return errors.New("state directory must have permissions 0700")
	}
	f, err := os.Open(path)
	if os.IsNotExist(err) {
		return s.writeStateLocked()
	}
	if err != nil {
		return errors.New("cannot open state file")
	}
	defer f.Close()
	info, err = f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > 4<<20 {
		return errors.New("invalid state file")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0 {
		return errors.New("state file must have permissions 0600")
	}
	var d diskState
	decoder := json.NewDecoder(io.LimitReader(f, 4<<20))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&d) != nil || decoder.Decode(new(any)) != io.EOF || d.Version != 1 || len(d.Rooms) > s.config.MaxRooms {
		return errors.New("invalid state format")
	}
	for _, dr := range d.Rooms {
		if !validID(dr.ID) || !validID(dr.OwnerID) || !validToken(dr.OwnerToken) || dr.Expires.IsZero() || len(dr.Members) < 1 || len(dr.Members) > MaxTeamSize {
			return errors.New("invalid room in state")
		}
		raw, err := base64.RawURLEncoding.DecodeString(dr.InviteCode)
		if err != nil || len(raw) != 12 || base64.RawURLEncoding.EncodeToString(raw) != dr.InviteCode {
			return errors.New("invalid invite in state")
		}
		if s.rooms[dr.ID] != nil || s.codes[dr.InviteCode] != nil {
			return errors.New("duplicate room in state")
		}
		r := &room{id: dr.ID, code: dr.InviteCode, ownerID: dr.OwnerID, ownerToken: dr.OwnerToken, expires: dr.Expires, members: make(map[string]*member), requests: make(map[string]*joinRequest), connections: make(map[*client]bool)}
		for _, dm := range dr.Members {
			if !validID(dm.ID) || !validNickname(dm.Nickname) || !validToken(dm.Token) || r.members[dm.ID] != nil || same(dm.Token, dr.OwnerToken) {
				return errors.New("invalid member in state")
			}
			r.members[dm.ID] = &member{id: dm.ID, nickname: dm.Nickname, token: dm.Token}
		}
		if r.members[r.ownerID] == nil {
			return errors.New("state room has no owner")
		}
		if time.Now().Before(r.expires) {
			s.rooms[r.id] = r
			s.codes[r.code] = r
		}
	}
	return nil
}

// A failed durable write fails closed. No subsequent request is allowed to
// mistake an in-memory-only mutation for durable, acknowledged state.
func (s *Server) persistLocked() bool {
	if s.storageErr != nil {
		return false
	}
	if err := s.writeStateLocked(); err != nil {
		s.storageErr = err
		for c := range s.connections {
			c.stop()
		}
		return false
	}
	return true
}
func (s *Server) persistHTTP(w http.ResponseWriter) bool {
	if !s.persistLocked() {
		fail(w, 503, "storage_unavailable", "Durable room state unavailable")
		return false
	}
	return true
}

func (s *Server) writeStateLocked() error {
	path := s.config.StatePath
	if path == "" {
		return nil
	}
	d := diskState{Version: 1, Rooms: make([]diskRoom, 0, len(s.rooms))}
	for _, r := range s.rooms {
		dr := diskRoom{ID: r.id, InviteCode: r.code, OwnerID: r.ownerID, OwnerToken: r.ownerToken, Expires: r.expires, Members: make([]diskMember, 0, len(r.members))}
		for _, m := range r.members {
			dr.Members = append(dr.Members, diskMember{m.id, m.nickname, m.token})
		}
		sort.Slice(dr.Members, func(i, j int) bool { return dr.Members[i].ID < dr.Members[j].ID })
		d.Rooms = append(d.Rooms, dr)
	}
	sort.Slice(d.Rooms, func(i, j int) bool { return d.Rooms[i].ID < d.Rooms[j].ID })
	f, err := os.CreateTemp(filepath.Dir(path), ".ble-talkie-state-*")
	if err != nil {
		return errors.New("cannot create state temporary file")
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		err = json.NewEncoder(f).Encode(d)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		return errors.New("cannot write state file")
	}
	if os.Rename(name, path) != nil {
		return errors.New("cannot atomically replace state file")
	}
	if runtime.GOOS != "windows" {
		dir, err := os.Open(filepath.Dir(path))
		if err != nil {
			return errors.New("cannot sync state directory")
		}
		err = dir.Sync()
		_ = dir.Close()
		if err != nil {
			return errors.New("cannot sync state directory")
		}
	}
	return nil
}
