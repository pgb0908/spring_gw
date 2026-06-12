package com.example.gw.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

@Slf4j
public class IpFilterGatewayFilter implements GatewayFilter {

    private final List<String> allowList;

    public IpFilterGatewayFilter(List<String> allowList) {
        this.allowList = allowList;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (allowList.isEmpty()) {
            return chain.filter(exchange);
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null) {
            String clientIp = remoteAddress.getAddress().getHostAddress();
            if (!isAllowed(clientIp)) {
                log.warn("IP {} is not in allowList — blocked", clientIp);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }

        return chain.filter(exchange);
    }

    private boolean isAllowed(String clientIp) {
        for (String cidr : allowList) {
            try {
                if (matchesCidr(clientIp, cidr)) return true;
            } catch (Exception e) {
                log.warn("Invalid CIDR '{}': {}", cidr, e.getMessage());
            }
        }
        return false;
    }

    private boolean matchesCidr(String clientIp, String cidr) throws Exception {
        if (!cidr.contains("/")) {
            return clientIp.equals(cidr);
        }
        String[] parts = cidr.split("/");
        byte[] network = InetAddress.getByName(parts[0]).getAddress();
        byte[] client = InetAddress.getByName(clientIp).getAddress();
        if (network.length != client.length) return false;
        int prefix = Integer.parseInt(parts[1]);
        int fullBytes = prefix / 8;
        int remainder = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != client[i]) return false;
        }
        if (remainder > 0 && fullBytes < network.length) {
            int mask = 0xFF << (8 - remainder);
            if ((network[fullBytes] & mask) != (client[fullBytes] & mask)) return false;
        }
        return true;
    }
}
