package cn.sunnystring.bletalkie.team;

import cn.sunnystring.bletalkie.core.Json;
import org.json.*;
import java.security.SecureRandom;
import java.util.*;

/** Owned by the controller's control executor; public snapshots never contain credentials. */
public final class TeamState {
    public final String roomId, ownerId, memberToken, ownerToken;
    public String inviteCode = "";
    public final LinkedHashMap<String, Member> members = new LinkedHashMap<>();
    public final LinkedHashMap<String, String> delegatedTokens = new LinkedHashMap<>();
    public final LinkedHashSet<String> unsyncedMembers = new LinkedHashSet<>();
    public TeamState(String roomId, String ownerId, String memberToken, String ownerToken) {
        this.roomId = roomId; this.ownerId = ownerId; this.memberToken = memberToken; this.ownerToken = ownerToken;
    }
    public static String token() {
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        return android.util.Base64.encodeToString(secret, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
    public static TeamState local(String self, String name) {
        TeamState team = new TeamState(UUID.randomUUID().toString(), self, token(), token());
        team.members.put(self, new Member(self, name));
        return team;
    }
    public static TeamState load(JSONObject json) throws JSONException {
        String roomId = json.getString("roomId"), ownerId = json.getString("ownerId");
        UUID.fromString(roomId); UUID.fromString(ownerId);
        TeamState team = new TeamState(roomId, ownerId, json.getString("memberToken"), json.optString("ownerToken"));
        team.updateRoster(json);
        JSONObject delegated = json.optJSONObject("delegatedTokens");
        if (delegated != null) {
            Iterator<String> ids = delegated.keys();
            while (ids.hasNext()) { String id = ids.next(); team.delegatedTokens.put(id, delegated.getString(id)); }
        }
        JSONArray unsynced = json.optJSONArray("unsyncedMembers");
        if (unsynced != null) for (int i = 0; i < unsynced.length(); i++) team.unsyncedMembers.add(unsynced.optString(i));
        return team;
    }
    public void updateRoster(JSONObject json) {
        inviteCode = json.optString("inviteCode", inviteCode);
        JSONArray list = json.optJSONArray("members");
        if (list == null) return;
        members.clear();
        for (int i = 0; i < list.length() && i < 4; i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id");
            try { UUID.fromString(id); } catch (IllegalArgumentException invalid) { continue; }
            Member member = new Member(id, entry.optString("nickname", "队员"));
            member.online = entry.optBoolean("online"); member.via = entry.optString("via");
            member.generation = entry.optLong("generation");
            members.put(id, member);
        }
    }
    public JSONObject publicRoom() {
        JSONArray array = new JSONArray();
        for (Member member : members.values()) array.put(member.json());
        return Json.object("roomId", roomId, "ownerId", ownerId, "inviteCode", inviteCode, "members", array, "maxTeamSize", 4);
    }
    public JSONObject saved() {
        JSONObject json = publicRoom();
        try {
            json.put("memberToken", memberToken).put("ownerToken", ownerToken);
            JSONObject tokens = new JSONObject();
            for (Map.Entry<String, String> entry : delegatedTokens.entrySet()) tokens.put(entry.getKey(), entry.getValue());
            json.put("delegatedTokens", tokens);
            json.put("unsyncedMembers", new JSONArray(unsyncedMembers));
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        return json;
    }
    public static final class Member {
        public final String id;
        public String nickname, via = "";
        public boolean online;
        public long generation;
        public Member(String id, String nickname) { this.id = id; this.nickname = nickname; }
        public JSONObject json() { return Json.object("id", id, "nickname", nickname, "online", online, "via", via, "generation", generation); }
    }
}
