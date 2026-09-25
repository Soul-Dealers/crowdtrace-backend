package com.souldealers.crowdtracebackend.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolves the socket address for IP-layer keying without trusting forwarding headers. */
@Component
public class ClientAddressResolver {

    private static final String UNKNOWN = "unknown";

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();

        if (remoteAddress == null || remoteAddress.isBlank()) {
            return UNKNOWN;
        }

        return canonicalise(remoteAddress);
    }

    /** Canonicalises equivalent IPv6 forms so one client receives one bucket. */
    private String canonicalise(String address) {
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException exception) {
            return address;
        }
    }
}
