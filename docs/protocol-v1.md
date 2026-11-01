# BLE Talkie protocol v1

The relay uses HTTP JSON for membership, WebSocket JSON for route controls, and
WebSocket binary messages for Opus audio. The default deployment prefix is
`/ble-talkie`; all paths below are relative to that prefix. Use HTTPS/WSS for a
public deployment. The Go process defaults to `127.0.0.1:18110` behind the TLS
reverse proxy.

## Identity and credentials

Room and member IDs are canonical lowercase UUID strings, for example
`00000000-0000-4000-8000-00000000000a`. The nil UUID is rejected. The Android owner
generates the room ID, owner member ID, owner administration token, and owner
member token before going online. Tokens are 32 cryptographically random bytes
encoded as URL-safe Base64 without padding (43 characters). Administration and
voice credentials are separate. Credentials must never be put in invite URLs,
analytics, or logs.

Nicknames contain 1–32 Unicode code points, with no leading/trailing whitespace,
control characters, or Unicode format characters. Each accepted member occupies
one of four slots, including offline members. A pending applicant occupies no
slot and cannot authenticate or send audio. An invite code permits an application
for approval, not membership or access to the roster.

The server generates a 16-character invite code from 12 random bytes. Join request
IDs are UUIDs and request credentials use the same 32-byte token format.
Credentials are compared in constant time after format validation.

## HTTP

Every request with a body uses `Content-Type: application/json`. Bodies are capped
at 8 KiB; unknown fields, trailing JSON values, and malformed input are rejected.
Bearer credentials use `Authorization: Bearer <token>`. Responses carrying room
state never disclose another member's token. There is no public room/roster
listing. Errors have the shape
`{"error":"machine_code","message":"Human-readable explanation"}`.

| Method and path | Authorization | Request / result |
| --- | --- | --- |
| `GET /` | None | Service metadata with `status:"ready"` and prefixed links. |
| `GET /healthz` | None | `200 {"status":"ready"}` only when initialized and ready; `503` during shutdown or storage failure. `/health` is an alias. |
| `GET /invite?code=<code>` | None | HTML with an explicit `bletalkie://join?code=<code>` link and `/download` link. |
| `GET /download` | None | Configured APK, or JSON `404 apk_unavailable`. |
| `POST /api/rooms` | Credentials in body | Import/create as shown below. New rooms return `201`, existing matching rooms `200`. |
| `POST /api/rooms/{roomId}/members` | Owner administration token | `{id,nickname,token}` upserts a Bluetooth-approved member; returns a room snapshot. A fifth member or conflicting existing token returns `409`. |
| `POST /api/join` | None | `{code,memberId,nickname}` returns `202 {requestId,requestToken,roomId,ownerId,status:"pending"}`. |
| `GET /api/join/{requestId}` | Request token | Pending/rejected status, or approved result below. |
| `DELETE /api/join/{requestId}` | Request token | Withdraw this enrollment, returning `{withdrawn:true}`. Pending/rejected requests disappear; an approved applicant's matching membership and routes are revoked. Missing/expired/already withdrawn requests return `404`. |
| `GET /api/rooms/{roomId}/requests` | Owner administration token | `{requests:[{requestId,memberId,nickname}]}` for pending requests only. |
| `POST /api/rooms/{roomId}/requests/{requestId}` | Owner administration token | `{accept:true}` or `{accept:false}`; returns `{status:"approved"}` or `{status:"rejected"}`. Approval creates an independent member token. A full room returns `409 room_full`. |
| `DELETE /api/rooms/{roomId}/members/{memberId}` | Owner administration token or that member's voice token | Immediately revoke/remove a non-owner member; returns `{removed:memberId}`. The slot becomes reusable. |
| `DELETE /api/rooms/{roomId}` | Owner administration token | Close the room and revoke every socket; returns `{closed:true}`. |

Create/import body:

```json
{
  "roomId": "<room UUID>",
  "ownerId": "<owner member UUID>",
  "nickname": "A",
  "ownerToken": "<owner administration token>",
  "memberToken": "<owner voice token>"
}
```

Response:

```json
{
  "roomId": "<room UUID>",
  "inviteCode": "<server-generated code>",
  "ownerId": "<owner member UUID>",
  "ownerToken": "<owner administration token>",
  "memberToken": "<owner voice token>",
  "members": [
    {"id":"<owner UUID>","nickname":"A","online":false,"via":"","generation":0}
  ],
  "maxTeamSize": 4
}
```

Reopening an existing room requires the same owner ID, administration token, and
owner voice token. It preserves the invite code and all remotely approved members.
It can update the owner's nickname. Token changes are rejected; v1 has no token
rotation endpoint. The owner can upload already accepted offline Bluetooth members
using the owner-authorized member upsert.

An approved poll result has flat fields:

```json
{
  "status": "approved",
  "roomId": "<room UUID>",
  "inviteCode": "<code>",
  "ownerId": "<owner UUID>",
  "memberToken": "<new applicant voice token>",
  "members": [],
  "maxTeamSize": 4
}
```

Clients retain the approved voice token securely before considering enrollment
complete. Removing an approved member also revokes the corresponding approved
poll result. Request credentials expire after ten minutes from application,
including approved/rejected results. Missing authorization returns `401`; wrong
credentials or insufficient privileges return `403`; absent/expired resources
return `404`; conflicting membership/generation/capacity changes return `409`.

Cancel/switch enrollment by withdrawing the request, including when approval may
have raced with cancellation. Withdrawal and approval are serialized: a withdrawn
pending request cannot later be approved, and an already approved request revokes
the membership created by that approval. An old ticket cannot revoke a replacement
member that subsequently reused the same UUID with new credentials. Withdrawal of
an approved membership is durable when state storage is configured. Once request
credentials expire, the normal member/owner revocation endpoint is required.

## WebSocket controls

Connect to `/ws`. Browsers must send the same scheme and host in `Origin` as the
HTTP request. An absent Origin is accepted for Android clients. The relay does not
trust `X-Forwarded-Proto` for origin decisions; a browser connecting through a TLS
terminating proxy needs that trusted proxy to validate the external Origin and
remove it before forwarding. Android's native client omits Origin.

Within five seconds, send one text JSON message:

```json
{"type":"auth","roomId":"<room UUID>","memberId":"<A UUID>","token":"<A voice token>","generation":1}
```

Successful authentication returns
`{type:"authenticated",memberId,adminChallenge,room:{roomId,inviteCode,ownerId,members,maxTeamSize}}`.
Each member has `{id,nickname,online,via,generation}`. `via` is the primary member
ID of the socket carrying that member, or `""` when offline. For a direct member,
`via` equals its own ID. `generation` is zero before a route has existed.

A gateway A attaches accepted offline Bluetooth member B on the same socket:

```json
{"type":"attach","memberId":"<B UUID>","token":"<B voice token>","generation":1}
```

The server checks B's membership/token and returns
`{type:"attached",memberId,adminChallenge,room:<snapshot>}`. A source can be attached only within
the authenticated socket's room. `{"type":"detach","memberId":"<B UUID>"}`
releases A's delegated B route and returns `{type:"detached",memberId}`. Detach
cannot release the primary member or a route owned by another socket.

Generations are positive integers no larger than `9223372036854775807`. A different
socket can replace an active route only with a higher generation. An equal
generation may reconnect after the previous route is absent. A connection may
reaffirm its own current generation; lower generations always fail. A takeover
immediately removes the previous socket's right to emit that member's audio.
Disconnect cleanup checks route ownership and cannot erase a newer route.

Every accepted source needs an independent generation counter that is shared or
coordinated between its direct route and gateways. When B becomes directly online,
B chooses a generation higher than its delegated route. A stale gateway attach
gets `stale_generation`. Generation is a route lease, distinct from the audio
epoch and sequence number.

Other server controls:

- `{type:"roster",room:<snapshot>}` after roster/route changes.
- `{type:"join_request",requestId,memberId,nickname}` to the active owner's socket.
- `{type:"room_closed",reason:"owner_closed"|"room_expired"}` then disconnect.
- `{type:"member_removed",memberId}` then disconnect for a removed primary member.
- `{type:"error",code,message}` for invalid controls/audio, authorization failures,
  stale generation, and rate limits. Bad first authentication closes the socket.
- Client `{type:"ping"}` receives `{type:"pong"}`. Protocol WebSocket Ping runs
  every 15 seconds; Pong extends the read deadline to 45 seconds.

Controls are text messages capped at 4 KiB. The server permits 400 total messages
and 40 text controls per second per physical socket. Applications should send one
Opus packet per audio message and handle roster events independently from audio.

## Signed owner commands through a gateway

An owner A may use Bluetooth while another accepted member C provides the network
socket. A retains its administration credential locally. C attaches A's logical
voice route and forwards the socket's `adminChallenge` to A over the trusted
Bluetooth transport. Every socket gets a fresh challenge containing 32 random
bytes encoded as URL-safe Base64 without padding. It is unchanged for that socket
and is included at the top level of both `authenticated` and `attached` replies.
Reconnect/restart creates a different challenge. The challenge is a nonce, not an
administration credential.

A signs a specific action and C forwards this text control unchanged:

```json
{
  "type": "owner_command",
  "commandId": "<new canonical UUID>",
  "roomId": "<room UUID>",
  "action": "approve",
  "targetId": "<join request UUID>",
  "generation": 10,
  "issuedAt": 1793548800000,
  "challenge": "<this socket's adminChallenge>",
  "signature": "<URL-safe Base64 HMAC-SHA256 without padding>"
}
```

`generation` is the owner's current logical route generation, including when that
route is attached to C. `issuedAt` is Unix milliseconds. The HMAC key is the raw
32 bytes obtained by decoding A's `ownerToken`; do not use the 43-character token
text as the key. The UTF-8 signed message is exactly these eight lines, joined
with a single LF character and **no final newline**:

```text
BTK-ADMIN-1
<roomId>
<commandId>
<action>
<targetId>
<generation as unsigned decimal>
<issuedAt as signed decimal>
<challenge>
```

`targetId` is an empty string for `requests`/`close`, leaving an empty line in the
canonical message. It is a request UUID for `approve`/`reject`, or a member UUID
for `remove`. The five actions use the same validation, authorization result
shapes, capacity checks, revocation, and persistence as the corresponding HTTP
administration endpoints. `requests` returns `{requests:[...]}`; approve/reject
return `{status:"approved"|"rejected"}`; remove returns `{removed:memberId}`;
close returns `{closed:true}`. An applicant's voice token is still retrieved only
through its authorized approval poll, never through the gateway's owner result.

The relay requires the owner's **current** route to belong to the requesting
physical socket, the exact route generation and challenge to match, timestamp
within ±120 seconds, and a valid constant-time HMAC comparison. An accepted
member's voice token alone cannot authorize these commands. A captured command
cannot be transferred to another socket or used after a route takeover.

Each verified command ID is remembered until `issuedAt + 120000` milliseconds,
including when the requested operation fails, so a failed approval cannot later
be replayed after a slot becomes free. Each socket retains at most 256 unexpired
IDs. Expired IDs are pruned; unexpired IDs are never evicted. New commands at the
limit fail with `command_limit`, and repeated accepted IDs fail with
`command_replayed`. Other validation errors include `invalid_owner_command`,
`room_mismatch`, `owner_route`, `invalid_challenge`, `invalid_timestamp`, and
`invalid_signature`. Existing text-control rate limits also apply.

Results go only to the physical requesting socket, which forwards them to A:

```json
{"type":"owner_result","commandId":"<UUID>","action":"requests","status":"ok","result":{"requests":[]}}
```

```json
{"type":"owner_result","commandId":"<UUID>","action":"approve","status":"error","error":"room_full","message":"Room already has four members"}
```

If A removes its current gateway C, C's source/delegation rights are revoked
immediately; its correlated `owner_result` is sent before the terminal
`member_removed` event and disconnect. For `close`, the normal `room_closed`
event may supersede the final result. Malformed JSON/control frames still use the
normal `error` control. Existing clients that use only HTTP administration and
voice controls can ignore the added challenge field and continue unchanged.

## Binary audio frame

All integers are unsigned and big endian. UUID fields use their raw 16 bytes in
canonical network order. A binary message must contain exactly one complete frame.

| Offset | Bytes | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII `BTK1` |
| 4 | 16 | Room UUID |
| 20 | 16 | Original source member UUID |
| 36 | 8 | Source audio epoch |
| 44 | 4 | Sequence number within the epoch |
| 48 | 8 | Source monotonic timestamp, milliseconds |
| 56 | 2 | Opus payload length, 1–1275 |
| 58 | 1–1275 | One Opus packet |

The header is exactly 58 bytes; the largest valid message is 1333 bytes. The relay
does not decode Opus or rewrite the original sender. A gateway forwarding B uses
B's source UUID, epoch, sequence, timestamp, and unchanged payload. The audio epoch
must change when sequence numbering restarts, including a sequence wrap.

The relay validates magic, exact length, room, current source route ownership,
and a bounded replay window. Each source retains 64 sequence positions for each
of its four most recent epochs. Duplicates and packets older than that sequence
window are silently dropped. Old epoch history eventually ages out; clients
must not recycle epoch identifiers.

For A carrying A+B and C connected directly:

1. A submits B's frame under B's logical source rights. C receives it once.
2. C submits its frame. A receives one copy per physical socket and locally fans
   it to B over Bluetooth.
3. Audio is never returned to its ingress socket, including other logical members
   carried on that socket. Local gateway fanout handles those listeners.
4. If B takes over with a higher generation, A cannot continue emitting B. C's
   next frame reaches A once and B's new direct socket once.

Each socket has one reader/writer, a 32-entry control queue and an eight-frame
voice queue. Controls take priority. Full voice queues discard oldest queued
frames; frames older than 250 ms or belonging to superseded/revoked routes are
discarded. Full control queues disconnect slow clients. Socket writes have a
two-second deadline. Oversized WebSocket messages above 4 KiB close the connection.

## Limits, persistence, and deployment configuration

`BLE_TALKIE_PREFIX` changes the prefix. `BLE_TALKIE_LISTEN` changes the listen
address. `BLE_TALKIE_APK` optionally points to a regular APK file.
`BLE_TALKIE_STATE` optionally points to a private durable state file, for example
`/var/lib/ble-talkie/state.json`.

With state configured, successful create/import, member upsert, approval,
revocation, close, and room expiry atomically replace a versioned JSON state file.
New directories are created with mode `0700`; the immediate state directory must
be private on Unix. Temporary/final files use `0600`, with file and directory
sync on Unix. Windows deployments must also restrict directory ACLs to the
service account because Unix permission bits do not enforce Windows ACLs.

The state file contains room IDs/codes, fixed expiry, owner administration token,
member IDs/nicknames/voice tokens. It is secret service data and should be kept
outside the repository and web root. It contains no routes, connections, audio,
replay windows, generations, socket challenges/owner-command replay caches, or
pending/request polling credentials.

After restart with durable state, the invite code and accepted members survive;
all members start offline and reconnect with their retained voice tokens. Pending
applicants apply again. An approved applicant must have saved its voice token
before restart. Without a configured state file, restart loses all room, code,
membership, and request state; an owner may recreate a locally retained room and
upload retained Bluetooth members, producing a new invite code. Corrupt state or
a storage write failure fails readiness and closes active sessions; the service
must be repaired/restarted before resuming.

Rooms expire 24 hours after original creation, including across restart. Import,
activity, and reconnect do not extend expiry. Requests expire after ten minutes.
The relay bounds rooms at 1024, join requests at 4096 globally/32 per room,
physical sockets at 1024 globally/16 per room, and members at four per room.
Room expiry is checked on requests and once per second by the janitor.

Build/run from `server/` with Go 1.24 or later:

```text
go test ./...
go vet ./...
go build -o bin/relay ./cmd/relay
```

The only external Go dependency is `github.com/gorilla/websocket v1.5.3`. Builds
support `CGO_ENABLED=0` for a static Linux deployment; the relay has no native
codec or system library dependency.
