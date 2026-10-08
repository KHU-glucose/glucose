package com.glucoselog.photo;

import java.net.URL;
import java.time.Duration;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** R2(S3 호환) 버킷에 대한 presigned PUT 발급, 업로드 완료 확인(HEAD), job 워커용 다운로드(GET)를 담당한다. */
@Service
public class PhotoStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final R2Properties properties;

    public PhotoStorageService(S3Client s3Client, S3Presigner s3Presigner, R2Properties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    public URL presignPut(String objectKey, String contentType) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(properties.photoBucket())
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.presignTtlSeconds()))
                .putObjectRequest(putRequest)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return presigned.url();
    }

    public boolean exists(String objectKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.photoBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** prefix 아래 객체를 모두 지우고 지운 개수를 돌려준다. 계정 삭제 시 사용자 경로 전체 정리용. */
    public int deleteByPrefix(String prefix) {
        int deleted = 0;
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(properties.photoBucket())
                .prefix(prefix)
                .build();
        for (S3Object object : s3Client.listObjectsV2Paginator(request).contents()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.photoBucket())
                    .key(object.key())
                    .build());
            deleted++;
        }
        return deleted;
    }

    public byte[] download(String objectKey) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(properties.photoBucket())
                        .key(objectKey)
                        .build())
                .asByteArray();
    }
}
