package cn.sunnystring.bletalkie.core;

import java.util.*;

/** Pure routing policy shared by capture and both ingress transports. */
public final class RoutePlanner {
    public enum Ingress { LOCAL, BLUETOOTH_LEAF, BLUETOOTH_GATEWAY, RELAY }
    public static final class Targets {
        public final Set<String> peers;
        public final boolean relay, play;
        Targets(Set<String> peers, boolean relay, boolean play) {
            this.peers = Collections.unmodifiableSet(peers); this.relay = relay; this.play = play;
        }
    }
    private RoutePlanner() {}
    public static Targets plan(Ingress ingress, String origin, Set<String> leaves, String gateway, boolean relayReady) {
        Set<String> peers = new HashSet<>();
        boolean relay = false;
        if (ingress == Ingress.LOCAL && gateway != null) peers.add(gateway);
        else if (ingress == Ingress.LOCAL || ingress == Ingress.BLUETOOTH_LEAF || (ingress == Ingress.RELAY && gateway == null)) {
            peers.addAll(leaves);
            peers.remove(origin);
            relay = relayReady && ingress != Ingress.RELAY;
        }
        return new Targets(peers, relay, ingress != Ingress.LOCAL);
    }
}
