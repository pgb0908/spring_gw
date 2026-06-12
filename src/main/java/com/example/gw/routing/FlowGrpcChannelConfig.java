package com.example.gw.routing;

import com.example.gw.model.FlowResource;
import com.example.gw.standalone.StandaloneConfigLoader;
import com.tmaxsoft.iip.common.grpc.gatewaycore.v1.CoreRuntimeServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "gateway.mode", havingValue = "standalone")
@RequiredArgsConstructor
public class FlowGrpcChannelConfig {

    private final StandaloneConfigLoader loader;

    @Bean
    public Map<String, CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub> flowStubs() {
        return loader.getConfig().getFlows().values().stream()
                .collect(Collectors.toUnmodifiableMap(
                        this::extractFlowId,
                        this::buildStub
                ));
    }

    private String extractFlowId(FlowResource flow) {
        var targets = flow.getSpec().getLoadBalancing().getTargets();
        if (targets == null || targets.isEmpty()) {
            throw new IllegalStateException("Flow '" + flow.getMetadata().getName() + "' has no targets");
        }
        String flowId = targets.get(0).getFlowId();
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalStateException("Flow '" + flow.getMetadata().getName() + "' target has no flowId");
        }
        return flowId;
    }

    private CoreRuntimeServiceGrpc.CoreRuntimeServiceBlockingStub buildStub(FlowResource flow) {
        var targets = flow.getSpec().getLoadBalancing().getTargets();
        var target = targets.get(0);
        if (targets.size() > 1) {
            log.warn("Flow '{}' has {} targets — only first target will be used",
                    flow.getMetadata().getName(), targets.size());
        }
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(target.getHost(), target.getPort())
                .usePlaintext()
                .build();
        log.debug("gRPC 채널 생성 — flow='{}' (flowId={}) → {}:{}",
                flow.getMetadata().getName(), target.getFlowId(), target.getHost(), target.getPort());
        return CoreRuntimeServiceGrpc.newBlockingStub(channel);
    }
}
