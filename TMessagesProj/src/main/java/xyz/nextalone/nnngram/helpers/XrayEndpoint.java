package xyz.nextalone.nnngram.helpers;

/**
 * A loopback SOCKS5 endpoint produced by an Xray session.
 *
 * <p>{@link #FAIL_CLOSED} is the placeholder used whenever no session is ready.
 * It points at a closed loopback port on purpose: the Telegram native proxy
 * layer never falls back to a direct connection once a proxy address is set, so
 * a failed start shows as "connecting to proxy" instead of leaking traffic
 * outside the proxy.
 */
public final class XrayEndpoint {

    public static final int FAIL_CLOSED_PORT = 9;

    public final int port;
    public final String username;
    public final String password;

    public static final XrayEndpoint FAIL_CLOSED = new XrayEndpoint(FAIL_CLOSED_PORT, "", "");

    public XrayEndpoint(int port, String username, String password) {
        this.port = port;
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
    }

    public boolean isReady() {
        return port > 0 && port != FAIL_CLOSED_PORT;
    }

    @Override
    public String toString() {
        // Never render credentials: this object ends up in logs on failure paths.
        return "XrayEndpoint{port=" + port + '}';
    }
}
