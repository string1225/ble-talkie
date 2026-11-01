package cn.sunnystring.bletalkie.ui;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Parses only this app's invite codes and owned invite links. */
public final class InviteCode {
    private InviteCode() {}

    public static String parse(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (valid(value)) return value;
        if (value.length() > 2048) return null;
        try {
            URI uri = new URI(value);
            if (uri.getUserInfo() != null || uri.getFragment() != null) return null;
            boolean app = "bletalkie".equalsIgnoreCase(uri.getScheme())
                    && "join".equalsIgnoreCase(uri.getHost()) && uri.getPort() == -1
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
            boolean web = "https".equalsIgnoreCase(uri.getScheme())
                    && "home.sunny-string.cn".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && "/ble-talkie/invite".equals(uri.getPath());
            if ((!app && !web) || uri.getRawQuery() == null) return null;
            String code = null;
            for (String entry : uri.getRawQuery().split("&")) {
                int separator = entry.indexOf('=');
                if (separator < 0) continue;
                String key = URLDecoder.decode(entry.substring(0, separator), StandardCharsets.UTF_8.name());
                if (!"code".equals(key)) continue;
                if (code != null) return null;
                code = URLDecoder.decode(entry.substring(separator + 1), StandardCharsets.UTF_8.name());
            }
            return valid(code) ? code : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    public static String link(String code) {
        if (!valid(code)) throw new IllegalArgumentException("Invalid invite code");
        return "https://home.sunny-string.cn/ble-talkie/invite?code=" + code;
    }

    private static boolean valid(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{16}");
    }
}
