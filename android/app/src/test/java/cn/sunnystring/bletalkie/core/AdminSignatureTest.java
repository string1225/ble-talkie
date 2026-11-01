package cn.sunnystring.bletalkie.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdminSignatureTest {
    @Test public void actionBoundSignatureMatchesIndependentFixture() {
        String signature = AdminSignature.sign("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            "12a5b83c-0321-49ab-8245-039d35629873", "ff000000-0000-4000-8000-000000000001", "approve",
            "ee000000-0000-4000-8000-000000000001", 7, 1793534400000L, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertEquals("DDciPaZroQworCauBWe6K3Rnw0ZieWhO9pz8KcXiICM", signature);
    }
    @Test public void signatureChangesWhenGatewaySessionChanges() {
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String first = AdminSignature.sign(token, "room", "command", "requests", "", 1, 1000, "old");
        String next = AdminSignature.sign(token, "room", "command", "requests", "", 1, 1000, "new");
        assertNotEquals(first, next);
    }
}
