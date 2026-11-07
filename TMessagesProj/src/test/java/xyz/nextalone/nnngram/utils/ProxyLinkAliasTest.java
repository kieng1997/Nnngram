package xyz.nextalone.nnngram.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Plain-JVM behaviour test for {@link ProxyLinkAlias}. No JUnit dependency:
 *
 * <pre>
 * javac -cp "&lt;gson&gt;/gson-2.13.2.jar" -d out \
 *   TMessagesProj/src/main/java/xyz/nextalone/nnngram/utils/ProxyLinkAlias.java \
 *   TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/ProxyLinkAliasTest.java
 * java -cp "out;&lt;gson&gt;/gson-2.13.2.jar" xyz.nextalone.nnngram.utils.ProxyLinkAliasTest
 * </pre>
 *
 * &lt;gson&gt; is the path to a real gson jar on disk (already on the project
 * classpath; the android.jar stub of org.json cannot be used at runtime).
 *
 * Expected values are independent literals; no real credentials are used.
 */
public final class ProxyLinkAliasTest {

    private static int failures;

    public static void main(String[] args) {
        // Slice 1: VLESS fragment.
        assertEquals("decoded remark",
                "No.493_[JP]⛔🧪Oracle_x1.0_50M_大阪/双栈",
                ProxyLinkAlias.from("vless://00000000-0000-0000-0000-000000000000@example.com:443"
                        + "?security=tls&type=tcp"
                        + "#No.493_%5BJP%5D%E2%9B%94%F0%9F%A7%AAOracle_x1.0_50M_%E5%A4%A7%E9%98%AA%2F%E5%8F%8C%E6%A0%88"));
        assertEquals("mixed literal unicode and escapes",
                "日本 JP+1",
                ProxyLinkAlias.from("vless://00000000-0000-0000-0000-000000000000@example.com:443#日本%20JP+1"));
        assertEquals("literal plus preserved", "a+b", ProxyLinkAlias.from(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443#a%2Bb"));
        assertEquals("raw plus preserved", "a+b", ProxyLinkAlias.from(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443#a+b"));
        assertNull("no fragment", ProxyLinkAlias.from(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443"));
        assertNull("blank fragment", ProxyLinkAlias.from(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443#%20%20"));
        assertNull("bad encoding", ProxyLinkAlias.from(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443#node%G1"));
        assertNull("truncated escape", ProxyLinkAlias.from(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443#node%2"));
        assertNull("null link", ProxyLinkAlias.from(null));
        assertNull("unrelated link", ProxyLinkAlias.from("https://example.com/#x"));

        // Slice 2: Shadowsocks outer fragment and legacy base64 payload fragment.
        assertEquals("ss outer fragment", "Name/JP", ProxyLinkAlias.from(
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:8388#Name%2FJP"));
        assertEquals("ss raw plus literal", "N+1", ProxyLinkAlias.from(
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:8388#N%2B1"));
        assertNull("ss no fragment", ProxyLinkAlias.from(
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:8388"));
        assertNull("ss bad fragment", ProxyLinkAlias.from(
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:8388#bad%G1"));
        // legacy: whole body is base64 of "method:password@host:port#alias"
        assertEquals("ss legacy inner fragment", "Inner/JP", ProxyLinkAlias.from(
                "ss://" + Base64.getEncoder().encodeToString(
                        "aes-256-gcm:password@example.com:8388#Inner/JP".getBytes(StandardCharsets.UTF_8))));
        assertNull("ss legacy without fragment", ProxyLinkAlias.from(
                "ss://" + Base64.getEncoder().encodeToString(
                        "aes-256-gcm:password@example.com:8388".getBytes(StandardCharsets.UTF_8))));
        // legacy inner fragment must be percent-decoded too
        assertEquals("ss legacy inner fragment decoded", "Inner/JP", ProxyLinkAlias.from(
                "ss://" + Base64.getEncoder().encodeToString(
                        "aes-256-gcm:password@example.com:8388#Inner%2FJP".getBytes(StandardCharsets.UTF_8))));
        // a query on the link is not part of the legacy payload (Go parseSS splits query first)
        assertEquals("ss legacy with query", "Inner/JP", ProxyLinkAlias.from(
                "ss://" + Base64.getEncoder().encodeToString(
                        "aes-256-gcm:password@example.com:8388#Inner%2FJP".getBytes(StandardCharsets.UTF_8))
                        + "?uot=0"));
        assertNull("ss undecodable body no fragment", ProxyLinkAlias.from(
                "ss://!!!not-base64!!!@example.com:8388"));

        // Slice 3: VMess base64 JSON "ps" field.
        final String vmessJson = "{\"v\":\"2\",\"ps\":\"Vmess/JP\",\"add\":\"example.com\","
                + "\"port\":\"443\",\"id\":\"00000000-0000-0000-0000-000000000000\","
                + "\"aid\":\"0\",\"net\":\"tcp\",\"type\":\"none\",\"tls\":\"\"}";
        assertEquals("vmess ps", "Vmess/JP", ProxyLinkAlias.from(
                "vmess://" + Base64.getEncoder().encodeToString(vmessJson.getBytes(StandardCharsets.UTF_8))));
        assertEquals("vmess ps url-safe raw", "Vmess/JP", ProxyLinkAlias.from(
                "vmess://" + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(vmessJson.getBytes(StandardCharsets.UTF_8))));
        assertNull("vmess no ps", ProxyLinkAlias.from("vmess://" + Base64.getEncoder().encodeToString(
                ("{\"v\":\"2\",\"add\":\"example.com\",\"port\":443,"
                        + "\"id\":\"00000000-0000-0000-0000-000000000000\"}").getBytes(StandardCharsets.UTF_8))));
        assertNull("vmess blank ps", ProxyLinkAlias.from("vmess://" + Base64.getEncoder().encodeToString(
                ("{\"ps\":\"   \",\"add\":\"example.com\",\"port\":443,"
                        + "\"id\":\"00000000-0000-0000-0000-000000000000\"}").getBytes(StandardCharsets.UTF_8))));
        assertNull("vmess bad base64", ProxyLinkAlias.from("vmess://!!!not-base64!!!"));
        assertNull("vmess not json", ProxyLinkAlias.from("vmess://" + Base64.getEncoder()
                .encodeToString("not json".getBytes(StandardCharsets.UTF_8))));
        assertNull("vmess ps not string", ProxyLinkAlias.from("vmess://" + Base64.getEncoder().encodeToString(
                ("{\"ps\":123,\"add\":\"example.com\",\"port\":443,"
                        + "\"id\":\"00000000-0000-0000-0000-000000000000\"}").getBytes(StandardCharsets.UTF_8))));

        if (failures > 0) {
            System.out.println(failures + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("ProxyLinkAliasTest OK");
    }

    private static void assertEquals(String what, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.println("FAIL: " + what + " expected <" + expected + "> got <" + actual + ">");
        }
    }

    private static void assertNull(String what, String actual) {
        assertEquals(what, null, actual);
    }
}
