//
//  PhotoUploadViewModel.swift
//  glucose
//
//  presigned URL 발급 -> R2에 직접 PUT -> 완료 통보. 서버는 바이트를 중계하지 않는다
//  (docs/backend-api.md 8~9번). 실패하면 사용자가 "다시 시도"를 눌러야 한다(자동 재시도 없음).
//

import Foundation

enum PhotoUploadState: Equatable {
    case idle
    case starting
    case uploading(progress: Double)
    case completing
    case done
    case failed(String)
}

@Observable
final class PhotoUploadViewModel: NSObject {

    private(set) var state: PhotoUploadState = .idle

    func upload(imageData: Data, context: IntakeContext) async {
        state = .starting
        do {
            let started: PhotoUploadStartResponse = try await APIClient.shared.send(
                path: "/v1/photos",
                method: .post,
                body: StartPhotoUploadRequest(contentType: "image/jpeg", context: context.rawValue)
            )

            try await putToR2(data: imageData, url: started.uploadUrl, contentType: "image/jpeg")

            state = .completing
            try await APIClient.shared.sendVoid(
                path: "/v1/photos/\(started.photoId.uuidString)/complete",
                method: .post
            )
            state = .done
        } catch {
            state = .failed("업로드에 실패했습니다. 다시 시도해주세요.")
        }
    }

    private func putToR2(data: Data, url: URL, contentType: String) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let task = session.uploadTask(with: request, from: data) { _, response, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    continuation.resume(throwing: APIError.invalidResponse)
                    return
                }
                continuation.resume()
            }
            task.resume()
        }
    }
}

extension PhotoUploadViewModel: URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        guard totalBytesExpectedToSend > 0 else { return }
        let fraction = Double(totalBytesSent) / Double(totalBytesExpectedToSend)
        Task { @MainActor [weak self] in
            self?.state = .uploading(progress: fraction)
        }
    }
}
