package com.example.gw;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.mode=standalone",
        "gateway.standalone.config-dir=./config"
})
class StandaloneIntegrationTest {

    @Autowired
    private RouteLocator routeLocator;

    // ── 동작 10: standalone 모드 부팅 시 config 파일 기반으로 라우트가 등록된다
    @Test
    void standalone_모드로_부팅하면_config_파일의_라우트가_등록된다() {
        StepVerifier.create(routeLocator.getRoutes())
                .expectNextMatches(route -> route.getId().equals("example-router"))
                .thenCancel()
                .verify();
    }
}
