package com.glucoselog.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.glucoselog.job.RetryableJobException;
import com.sun.net.httpserver.HttpServer;

class MlServiceClientTest {

    private HttpServer server;
    private volatile String lastInternalToken;
    private volatile String lastRequestId;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 성공하면_응답_원문을_그대로_돌려주고_헤더를_보낸다() throws IOException {
        MlServiceClient client = startStub(200, "{\"is_food_photo\":true}");

        String result = client.recognizeFood(new byte[]{1, 2, 3}, "image/jpeg", "MEAL");

        assertThat(result).contains("is_food_photo");
        assertThat(lastInternalToken).isEqualTo("test-token");
        assertThat(lastRequestId).isNotBlank();
    }

    @Test
    void 상태코드_400은_재시도하지_않는_예외를_던진다() throws IOException {
        MlServiceClient client = startStub(400, "{\"code\":\"INVALID_IMAGE\"}");

        assertThatThrownBy(() -> client.recognizeFood(new byte[]{1}, "image/jpeg", null))
                .isNotInstanceOf(RetryableJobException.class);
    }

    @Test
    void 상태코드_502는_재시도_대상_예외를_던진다() throws IOException {
        MlServiceClient client = startStub(502, "{\"code\":\"UPSTREAM_AI_ERROR\"}");

        assertThatThrownBy(() -> client.recognizeFood(new byte[]{1}, "image/jpeg", null))
                .isInstanceOf(RetryableJobException.class);
    }

    @Test
    void 연결할_수_없으면_재시도_대상_예외를_던진다() {
        MlServiceClient client = new MlServiceClient(new MlServiceProperties("http://127.0.0.1:1", "t", 300, 300));

        assertThatThrownBy(() -> client.recognizeFood(new byte[]{1}, "image/jpeg", null))
                .isInstanceOf(RetryableJobException.class);
    }

    private MlServiceClient startStub(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/food/recognize", exchange -> {
            lastInternalToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            lastRequestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new MlServiceClient(new MlServiceProperties(url, "test-token", 2000, 2000));
    }
}
