//
//  GraphUploadViewModel.swift
//  glucose
//
//  presigned URL 발급 -> R2에 PUT -> 완료 통보 -> 분석 결과 폴링(409면 지연 후 재시도).
//  날짜는 ml-service가 이미지에서 읽으므로 앱이 따로 보내지 않는다.
//

import Foundation

enum GraphUploadState: Equatable {
    case idle
    case uploading
    case analyzing
    case done(date: String, coverageRatio: Double)
    case failed(String)
}

@Observable
final class GraphUploadViewModel {

    private(set) var state: GraphUploadState = .idle
    private var task: Task<Void, Never>?

    var isBusy: Bool {
        state == .uploading || state == .analyzing
    }

    func start(imageData: Data, contentType: String) {
        task?.cancel()
        task = Task { [weak self] in
            await self?.run(imageData: imageData, contentType: contentType)
        }
    }

    func cancel() {
        task?.cancel()
    }

    func reset() {
        task?.cancel()
        state = .idle
    }

    private func run(imageData: Data, contentType: String) async {
        state = .uploading
        let uploadId: UUID
        do {
            let started: GraphUploadStartResponse = try await APIClient.shared.send(
                path: "/v1/glucose-graphs",
                method: .post,
                body: StartGraphUploadRequest(contentType: contentType)
            )
            try await putToR2(data: imageData, url: started.uploadUrl, contentType: contentType)
            try await APIClient.shared.sendVoid(
                path: "/v1/glucose-graphs/\(started.uploadId.uuidString)/complete",
                method: .post
            )
            uploadId = started.uploadId
        } catch {
            state = .failed("업로드에 실패했습니다. 다시 시도해주세요.")
            return
        }

        state = .analyzing
        await poll(uploadId: uploadId)
    }

    private func poll(uploadId: UUID) async {
        var delayNanoseconds: UInt64 = 1_000_000_000
        let maxDelayNanoseconds: UInt64 = 5_000_000_000

        while !Task.isCancelled {
            do {
                let result: GraphStatusResponse = try await APIClient.shared.send(
                    path: "/v1/glucose-graphs/\(uploadId.uuidString)"
                )
                state = .done(date: result.date, coverageRatio: result.coverageRatio)
                return
            } catch APIError.server(409, _) {
                try? await Task.sleep(nanoseconds: delayNanoseconds)
                delayNanoseconds = min(delayNanoseconds * 2, maxDelayNanoseconds)
            } catch APIError.server(_, let body) {
                state = .failed(Self.failureMessage(forCode: body.code))
                return
            } catch {
                state = .failed("분석 결과를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.")
                return
            }
        }
    }

    private func putToR2(data: Data, url: URL, contentType: String) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        let (_, response) = try await URLSession.shared.upload(for: request, from: data)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw APIError.invalidResponse
        }
    }

    /// 서버 오류 코드(docs/backend-api.md 13번)를 사용자가 다음에 뭘 해야 할지 알 수 있는 문장으로 바꾼다.
    static func failureMessage(forCode code: String) -> String {
        switch code {
        case "GRAPH_NOT_RECOGNIZED":
            "리브레 일일 그래프로 인식하지 못했어요. 리브레 앱에서 일일 그래프를 공유해 저장한 원본 이미지를 선택해주세요. (화면을 다시 찍은 사진이나 잘린 이미지는 지원하지 않아요)"
        case "GRAPH_TOO_LITTLE_DATA":
            "그래프에 혈당 기록이 너무 적어요(하루의 10% 미만). 센서 기록이 더 쌓인 날의 그래프를 올려주세요."
        default:
            "그래프를 분석하지 못했어요. 잠시 후 다시 시도해주세요."
        }
    }
}
