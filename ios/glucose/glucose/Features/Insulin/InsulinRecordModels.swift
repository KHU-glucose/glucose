//
//  InsulinRecordModels.swift
//  glucose
//
//  docs/backend-api.md 12번(인슐린 기록 CRUD) 참고. 입력만 저장한다 - 용량 조언은 하지 않는다.
//

import Foundation

struct InsulinEventRequest: Encodable {
    let occurredAt: Date
    let units: Double
    let kind: String
}

struct InsulinEventResponse: Decodable {
    let id: UUID
    let occurredAt: Date
    let units: Double
    let kind: String
    let createdAt: Date
    let updatedAt: Date
}

extension InsulinEventResponse {
    func toRecord() -> InsulinRecord {
        InsulinRecord(id: id, occurredAt: occurredAt, units: units, kind: kind)
    }
}
