//
//  PhotoUploadModels.swift
//  glucose
//
//  docs/backend-api.md 8번(POST /v1/photos) 참고
//

import Foundation

struct StartPhotoUploadRequest: Encodable {
    let contentType: String
    let context: String
}

struct PhotoUploadStartResponse: Decodable {
    let photoId: UUID
    let uploadUrl: URL
    let objectKey: String
    let expiresIn: Int
}
