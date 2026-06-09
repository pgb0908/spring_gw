package com.example.gw.filter

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class LoggingFilter : GlobalFilter, Ordered {

    private val log = LoggerFactory.getLogger(LoggingFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        val startTime = System.currentTimeMillis()

        log.info("→ {} {}", request.method, request.uri)

        return chain.filter(exchange).then(
            Mono.fromRunnable {
                val duration = System.currentTimeMillis() - startTime
                log.info("← {} {} ({}ms)", exchange.response.statusCode, request.uri, duration)
            }
        )
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}
