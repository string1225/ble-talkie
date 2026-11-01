package cn.sunnystring.bletalkie.core;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RoutePlannerTest {
    @Test public void offlineLeafUsesBluetoothGatewayEvenWhenInternetIsAvailable() {
        RoutePlanner.Targets targets = RoutePlanner.plan(RoutePlanner.Ingress.LOCAL, null, Set.of(), "A", true);
        assertEquals(Set.of("A"), targets.peers);
        assertFalse(targets.relay);
        assertFalse(targets.play);
    }
    @Test public void gatewayForwardsBToOtherLocalMembersAndServerWithoutEcho() {
        RoutePlanner.Targets targets = RoutePlanner.plan(RoutePlanner.Ingress.BLUETOOTH_LEAF, "B", Set.of("B", "D"), null, true);
        assertEquals(Set.of("D"), targets.peers);
        assertTrue(targets.relay);
        assertTrue(targets.play);
    }
    @Test public void remoteCIsPlayedAndForwardedToBOnlyOnce() {
        RoutePlanner.Targets targets = RoutePlanner.plan(RoutePlanner.Ingress.RELAY, null, Set.of("B"), null, true);
        assertEquals(Set.of("B"), targets.peers);
        assertFalse(targets.relay);
        assertTrue(targets.play);
    }
    @Test public void serverFallbackRequiresAnActualNetworkRoute() {
        assertTrue(RoutePlanner.plan(RoutePlanner.Ingress.LOCAL, null, Set.of(), null, true).relay);
        assertFalse(RoutePlanner.plan(RoutePlanner.Ingress.LOCAL, null, Set.of(), null, false).relay);
    }
    @Test public void gatewayDownstreamIsNotSentBackToInternet() {
        RoutePlanner.Targets targets = RoutePlanner.plan(RoutePlanner.Ingress.BLUETOOTH_GATEWAY, "A", Set.of(), "A", true);
        assertTrue(targets.play);
        assertTrue(targets.peers.isEmpty());
        assertFalse(targets.relay);
    }
}
