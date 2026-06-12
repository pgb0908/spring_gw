package com.example.gw.routing;

import com.example.gw.model.ListenerResource;
import com.example.gw.standalone.StandaloneConfigLoader;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class GatewayGrpcServerConfig implements DisposableBean {

    private final StandaloneConfigLoader loader;
    private final PendingResponseRegistry pendingResponseRegistry;

    private Server grpcServer;

    @Bean
    public GatewayRuntimeServiceImpl gatewayRuntimeService() {
        return new GatewayRuntimeServiceImpl(pendingResponseRegistry);
    }

    /**
     * protocol=GRPC 인 Listener를 찾아 해당 포트로 GatewayRuntimeService gRPC 서버를 기동한다.
     * GRPC Listener가 없으면 서버를 띄우지 않는다.
     */
    @Bean
    public GatewayGrpcServerConfig gatewayGrpcServer(GatewayRuntimeServiceImpl gatewayRuntimeService)
            throws IOException {
        var grpcListener = loader.getConfig().getListeners().stream()
                .filter(l -> l.getSpec().getProtocol() == ListenerResource.Protocol.GRPC)
                .findFirst();

        if (grpcListener.isEmpty()) {
            log.info("GRPC Listener 없음 — GatewayRuntimeService 서버 미기동");
            return this;
        }

        int port = grpcListener.get().getSpec().getPort();
        grpcServer = ServerBuilder.forPort(port)
                .addService(gatewayRuntimeService)
                .build()
                .start();

        log.info("GatewayRuntimeService gRPC 서버 기동 — port={}", port);
        return this;
    }

    @Override
    public void destroy() {
        if (grpcServer != null) {
            log.info("GatewayRuntimeService gRPC 서버 종료");
            grpcServer.shutdown();
        }
    }
}
