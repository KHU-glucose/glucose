//
//  RecognitionModels.swift
//  glucose
//
//  docs/backend-api.md 10~11번(GET /v1/photos/{id}/recognition, POST /v1/intakes) 참고
//

import Foundation

struct RecognitionResponse: Decodable {
    let isFoodPhoto: Bool
    let items: [RecognizedFoodItem]
    let likelyConsumedAll: Bool
}

struct RecognizedFoodItem: Decodable {
    let name: String
    let count: Int?
    let unit: String?
    let categoryHint: String?
    let tags: [String]
    let confidence: String
}

struct CreateIntakeRequest: Encodable {
    let context: String
    let occurredAt: Date
    let photoId: UUID?
    let items: [IntakeItemRequest]
}

struct IntakeItemRequest: Encodable {
    let name: String
    let count: Int
    let unit: String
    let categoryHint: String?
    let tags: [String]
}

struct IntakeResponse: Decodable {
    let id: UUID
    let context: String
    let occurredAt: Date
    let photoId: UUID?
    let items: [IntakeItemResponse]
    let createdAt: Date
    let updatedAt: Date
}

struct IntakeItemResponse: Decodable {
    let id: UUID
    let name: String
    let count: Int?
    let unit: String?
    let categoryHint: String?
    let tags: [String]
    let sugarGrams: Double?
}
