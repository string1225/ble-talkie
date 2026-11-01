package cn.sunnystring.bletalkie.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs a single owner action for one active gateway session, without exporting the owner key. */
public final class AdminSignature {
    private AdminSignature() {}
    public static String sign(String ownerToken, String room, String command, String action, String target,
                              long generation, long issuedAt, String challenge) {
        try {
            byte[] key = Base64.getUrlDecoder().decode(ownerToken);
            if (key.length != 32) throw new IllegalArgumentException("Invalid administration key");
            String canonical = "BTK-ADMIN-1\n" + room + "\n" + command + "\n" + action + "\n" + target
                + "\n" + generation + "\n" + issuedAt + "\n" + challenge;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("HMAC unavailable", error); }
    }
}
