package com.glucoselog.ml;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.glucoselog.job.RetryableJobException;

/**
 * ml-service 호출 전용 클라이언트 (docs/ml-service-contract.md).
 * 타임아웃/연결 실패와 502·504는 {@link RetryableJobException}으로 던져 job 재시도 대상이 되게 하고,
 * 400·401처럼 다시 불러도 소용없는 오류는 일반 예외로 던져 바로 FAILED 처리되게 한다.
 */
@Component
public class MlServiceClient {

    private final RestClient restClient;
    private final MlServiceProperties properties;

    public MlServiceClient(MlServiceProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.serviceUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    /** POST /v1/food/recognize. 성공 시 응답 JSON 원문을 그대로 돌려준다. */
    public String recognizeFood(byte[] image, String contentType, String context) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders imagePartHeaders = new HttpHeaders();
        imagePartHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("image", new HttpEntity<>(new NamedByteArrayResource(image, extensionOf(contentType)), imagePartHeaders));
        if (context != null) {
            body.add("context", context);
        }

        try {
            return restClient.post()
                    .uri("/v1/food/recognize")
                    .header("X-Internal-Token", properties.internalToken())
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException e) {
            throw mapStatusException(e);
        } catch (ResourceAccessException e) {
            throw new RetryableJobException("ml-service에 연결할 수 없습니다: " + e.getMessage(), e);
        }
    }

    private RuntimeException mapStatusException(HttpStatusCodeException e) {
        HttpStatusCode status = e.getStatusCode();
        String message = "ml-service 오류 (" + status.value() + "): " + e.getResponseBodyAsString();
        if (status.is5xxServerError()) {
            // 계약: 502/504는 1회 재시도 대상
            return new RetryableJobException(message, e);
        }
        // 400 INVALID_IMAGE, 401 UNAUTHORIZED 등 재시도해도 의미 없는 오류
        return new IllegalStateException(message, e);
    }

    private static String extensionOf(String contentType) {
        return "image/png".equals(contentType) ? "png" : "jpg";
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String extension) {
            super(bytes);
            this.filename = "photo." + extension;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
