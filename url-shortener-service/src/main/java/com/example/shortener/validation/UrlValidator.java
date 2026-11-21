package com.example.shortener.validation;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates target URLs before they are shortened.
 *
 * <p>Threats addressed:
 * <ul>
 *   <li>Non-web schemes (javascript:, data:, file:) used for XSS / local file access.</li>
 *   <li>Internal targets (localhost, private/link-local IPs, *.internal), which turn the shortener
 *       into a pivot for SSRF-style abuse or for phishing internal users.</li>
 *   <li>Userinfo tricks such as https://trusted.com@evil.com.</li>
 *   <li>Redirect loops back to the shortener itself.</li>
 * </ul>
 */
public class UrlValidator {

    public static final int MAX_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(".localhost", ".local", ".internal", ".lan", ".home.arpa");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** Any host made only of numbers / hex numbers, i.e. something HTTP clients will treat as an IP address. */
    private static final Pattern NUMERIC_HOST = Pattern.compile("^(0x[0-9a-f]+|\\d+)(\\.(0x[0-9a-f]+|\\d+))*$");
    /** The only numeric form we accept: four decimal octets 0-255, no leading zeros. */
    private static final Pattern CANONICAL_IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private final String selfHost;
    private final boolean resolveDns;

    /**
     * @param selfHost   host of this shortener (to block loops); may be null
     * @param resolveDns if true, hostnames are resolved and rejected when they point to private ranges
     */
    public UrlValidator(String selfHost, boolean resolveDns) {
        this.selfHost = selfHost == null ? null : selfHost.toLowerCase(Locale.ROOT);
        this.resolveDns = resolveDns;
    }

    /** Returns the normalized URL or throws {@link InvalidUrlException}. */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("URL must not be blank");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new InvalidUrlException("URL exceeds " + MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is malformed");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidUrlException("Only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrlException("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("URL must have a host");
        }

        String normalizedHost = normalizeHost(host);
        checkHostName(normalizedHost);
        checkAddress(normalizedHost);

        if (selfHost != null && normalizedHost.equals(selfHost)) {
            throw new InvalidUrlException("URL must not point back to this shortener");
        }
        return trimmed;
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            return IDN.toASCII(h);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("Host name is invalid");
        }
    }

    private static void checkHostName(String host) {
        if (host.equals("localhost")) {
            throw new InvalidUrlException("Internal hosts are not allowed");
        }
        for (String suffix : BLOCKED_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw new InvalidUrlException("Internal hosts are not allowed");
            }
        }
    }

    private void checkAddress(String host) {
        // Decimal (2130706433), hex (0x7f000001) and octal/zero-padded (0177.0.0.1) forms are read by browsers and HTTP
        // clients as IP addresses (all three are 127.0.0.1) but slip past checks that only understand dotted decimal.
        if (NUMERIC_HOST.matcher(host).matches() && !CANONICAL_IPV4.matcher(host).matches()) {
            throw new InvalidUrlException("Non-canonical IP address notation is not allowed");
        }
        boolean literal = IPV4_LITERAL.matcher(host).matches() || host.contains(":");
        if (literal) {
            // getByName does not perform DNS lookups for IP literals.
            rejectIfInternal(parseLiteral(host));
            return;
        }
        if (resolveDns) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    rejectIfInternal(address);
                }
            } catch (UnknownHostException e) {
                throw new InvalidUrlException("Host does not resolve");
            }
        }
    }

    private static InetAddress parseLiteral(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidUrlException("IP address is invalid");
        }
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0                                   // "this" network
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT 100.64/10
                    || (first == 169 && second == 254)          // link-local / cloud metadata
                    || first >= 240;                            // reserved + broadcast
        }
        if (a instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            return (first & 0xFE) == 0xFC;                      // unique local fc00::/7
        }
        return false;
    }

    private static void rejectIfInternal(InetAddress address) {
        if (isInternal(address)) {
            throw new InvalidUrlException("Private, loopback and link-local addresses are not allowed");
        }
    }
}
