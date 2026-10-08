//
//  GraphUploadModels.swift
//  glucose
//
//  docs/backend-api.md 14번(혈당 그래프) 참고
//

import Foundation

struct StartGraphUploadRequest: Encodable {
    let contentType: String
}

struct GraphUploadStartResponse: Decodable {
    let uploadId: UUID
    let uploadUrl: URL
    let objectKey: String
    let expiresIn: Int
}

struct GraphStatusResponse: Decodable {
    let date: String
    let coverageRatio: Double
}
