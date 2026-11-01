package relay

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

const ownerCommandWindow = int64(120000)
const maxOwnerCommands = 256

type operationError struct {
	status        int
	code, message string
}

func writeOperation(w http.ResponseWriter, result any, err *operationError) {
	if err != nil {
		fail(w, err.status, err.code, err.message)
		return
	}
	writeJSON(w, 200, result)
}

func (s *Server) commitOperationLocked() *operationError {
	if !s.persistLocked() {
		return &operationError{503, "storage_unavailable", "Durable room state unavailable"}
	}
	return nil
}

// These operations require Server.mu and an already-authorized room. Both HTTP
// and signed WebSocket controls use them, preserving one atomic authorization /
// mutation boundary without recursively dispatching handlers.
func (s *Server) adminActionLocked(r *room, action, target string) (any, *operationError) {
	switch action {
	case "requests":
		requests := make([]map[string]string, 0)
		for _, q := range r.requests {
			if q.status == "pending" {
				requests = append(requests, map[string]string{"requestId": q.id, "memberId": q.memberID, "nickname": q.nickname})
			}
		}
		sort.Slice(requests, func(i, j int) bool { return requests[i]["requestId"] < requests[j]["requestId"] })
		return map[string]any{"requests": requests}, nil
	case "approve", "reject":
		q := r.requests[target]
		if q == nil || q.status != "pending" {
			return nil, &operationError{409, "request_unavailable", "Pending request unavailable"}
		}
		if action == "reject" {
			q.status = "rejected"
			return map[string]string{"status": "rejected"}, nil
		}
		if r.members[q.memberID] != nil {
			return nil, &operationError{409, "member_exists", "Member already belongs to room"}
		}
		if len(r.members) >= MaxTeamSize {
			return nil, &operationError{409, "room_full", "Room already has four members"}
		}
		t, err := randomToken(32)
		if err != nil {
			return nil, &operationError{503, "entropy_unavailable", "Cannot create credentials"}
		}
		q.memberToken = t
		q.status = "approved"
		r.members[q.memberID] = &member{id: q.memberID, nickname: q.nickname, token: t}
		if err := s.commitOperationLocked(); err != nil {
			return nil, err
		}
		s.rosterLocked(r)
		return map[string]string{"status": "approved"}, nil
	case "remove":
		return s.removeMemberLocked(r, target)
	case "close":
		s.closeRoomLocked(r, "owner_closed")
		if err := s.commitOperationLocked(); err != nil {
			return nil, err
		}
		return map[string]bool{"closed": true}, nil
	default:
		return nil, &operationError{400, "invalid_owner_command", "Unknown owner action"}
	}
}

func (s *Server) removeMemberLocked(r *room, id string) (any, *operationError) {
	return s.removeMemberReplyLocked(r, id, nil)
}

type removalReply struct {
	client  *client
	command controlMessage
}

func (s *Server) removeMemberReplyLocked(r *room, id string, reply *removalReply) (any, *operationError) {
	m := r.members[id]
	if m == nil {
		return nil, &operationError{404, "not_found", "Member unavailable"}
	}
	if m.id == r.ownerID {
		return nil, &operationError{409, "owner_member", "Close the room to remove its owner"}
	}
	delete(r.members, m.id)
	for _, q := range r.requests {
		if q.memberID == m.id && q.status == "approved" {
			q.status = "rejected"
			q.memberToken = ""
		}
	}
	closing := make([]*client, 0)
	for c := range r.connections {
		delete(c.routes, m.id)
		if c.primary == m.id {
			c.revoked.Store(true)
			for delegatedID := range c.routes {
				if delegated := r.members[delegatedID]; delegated != nil && delegated.route == c {
					delegated.route = nil
				}
				delete(c.routes, delegatedID)
			}
			closing = append(closing, c)
		}
	}
	if err := s.commitOperationLocked(); err != nil {
		return nil, err
	}
	result := map[string]string{"removed": m.id}
	for _, c := range closing {
		event := map[string]any{"type": "member_removed", "memberId": m.id}
		if reply != nil && reply.client == c {
			c.closeWithMessages(ownerResultPayload(reply.command, result, nil), event)
		} else {
			c.closeWith(event)
		}
	}
	s.rosterLocked(r)
	return result, nil
}

func (s *Server) withdraw(w http.ResponseWriter, req *http.Request, id string) {
	if !validID(id) {
		fail(w, 400, "invalid_id", "Invalid request ID")
		return
	}
	ticket := bearer(req)
	if ticket == "" {
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
	if !validToken(ticket) || !same(ticket, q.token) {
		fail(w, 403, "forbidden", "Request credentials do not match")
		return
	}
	// Only the member created by this exact approval may be revoked. A revoked
	// ticket must never remove a later member that reused the same UUID/slot.
	if m := q.room.members[q.memberID]; q.status == "approved" && m != nil && same(m.token, q.memberToken) {
		if _, err := s.removeMemberLocked(q.room, q.memberID); err != nil {
			writeOperation(w, nil, err)
			return
		}
	}
	delete(q.room.requests, id)
	delete(s.requests, id)
	writeJSON(w, 200, map[string]bool{"withdrawn": true})
}

func ownerCanonical(msg controlMessage) string {
	return strings.Join([]string{"BTK-ADMIN-1", msg.RoomID, msg.CommandID, msg.Action, msg.TargetID, strconv.FormatUint(msg.Generation, 10), strconv.FormatInt(msg.IssuedAt, 10), msg.Challenge}, "\n")
}

func (c *client) ownerResult(msg controlMessage, result any, err *operationError) {
	c.control(ownerResultPayload(msg, result, err))
}

func ownerResultPayload(msg controlMessage, result any, err *operationError) map[string]any {
	v := map[string]any{"type": "owner_result", "commandId": msg.CommandID, "action": msg.Action, "status": "ok"}
	if err != nil {
		v["status"] = "error"
		v["error"] = err.code
		v["message"] = err.message
	} else {
		v["result"] = result
	}
	return v
}

func (s *Server) ownerCommand(c *client, msg controlMessage) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	s.expireLocked(now)
	if c.revoked.Load() || s.closed || s.storageErr != nil {
		c.ownerResult(msg, nil, &operationError{503, "not_ready", "Session is unavailable"})
		return
	}
	r := c.room
	if r == nil || s.rooms[r.id] != r || r.members[c.primary] == nil || msg.RoomID != r.id {
		c.ownerResult(msg, nil, &operationError{403, "room_mismatch", "Command room differs from the active session"})
		return
	}
	if err := validateOwnerCommand(c, r, msg, now.UnixMilli()); err != nil {
		c.ownerResult(msg, nil, err)
		return
	}
	// Consume every verified command, including action failures, so changing
	// capacity or membership later cannot make a signed failure replayable.
	if c.adminCommands == nil {
		c.adminCommands = make(map[string]int64)
	}
	for id, expires := range c.adminCommands {
		if expires < now.UnixMilli() {
			delete(c.adminCommands, id)
		}
	}
	if _, exists := c.adminCommands[msg.CommandID]; exists {
		c.ownerResult(msg, nil, &operationError{409, "command_replayed", "Command ID already accepted"})
		return
	}
	if len(c.adminCommands) >= maxOwnerCommands {
		c.ownerResult(msg, nil, &operationError{429, "command_limit", "Too many unexpired owner commands"})
		return
	}
	c.adminCommands[msg.CommandID] = msg.IssuedAt + ownerCommandWindow
	if msg.Action == "remove" && msg.TargetID == c.primary {
		result, err := s.removeMemberReplyLocked(r, msg.TargetID, &removalReply{c, msg})
		if err != nil {
			c.ownerResult(msg, result, err)
		}
		return
	}
	result, err := s.adminActionLocked(r, msg.Action, msg.TargetID)
	c.ownerResult(msg, result, err)
}

func validateOwnerCommand(c *client, r *room, msg controlMessage, now int64) *operationError {
	if !validID(msg.CommandID) || !validID(msg.RoomID) || msg.Generation == 0 || msg.Generation > uint64(^uint64(0)>>1) {
		return &operationError{400, "invalid_owner_command", "Invalid command identity or generation"}
	}
	switch msg.Action {
	case "requests", "close":
		if msg.TargetID != "" {
			return &operationError{400, "invalid_owner_command", "This action has no target"}
		}
	case "approve", "reject", "remove":
		if !validID(msg.TargetID) {
			return &operationError{400, "invalid_owner_command", "Valid target ID required"}
		}
	default:
		return &operationError{400, "invalid_owner_command", "Unknown owner action"}
	}
	owner := r.members[r.ownerID]
	if owner == nil || owner.route != c || !c.routes[r.ownerID] || owner.generation != msg.Generation {
		return &operationError{403, "owner_route", "Owner is not currently routed through this socket and generation"}
	}
	if !validToken(msg.Challenge) || !same(msg.Challenge, c.adminChallenge) {
		return &operationError{403, "invalid_challenge", "Socket challenge does not match"}
	}
	if msg.IssuedAt < now-ownerCommandWindow || msg.IssuedAt > now+ownerCommandWindow {
		return &operationError{403, "invalid_timestamp", "Owner command timestamp is outside the allowed window"}
	}
	key, _ := base64.RawURLEncoding.DecodeString(r.ownerToken)
	signature, err := base64.RawURLEncoding.DecodeString(msg.Signature)
	if err != nil || len(signature) != sha256.Size || base64.RawURLEncoding.EncodeToString(signature) != msg.Signature {
		return &operationError{403, "invalid_signature", "Owner signature does not match"}
	}
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte(ownerCanonical(msg)))
	if !hmac.Equal(mac.Sum(nil), signature) {
		return &operationError{403, "invalid_signature", "Owner signature does not match"}
	}
	return nil
}
