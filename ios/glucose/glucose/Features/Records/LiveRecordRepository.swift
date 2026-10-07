//
//  LiveRecordRepository.swift
//  glucose
//

import Foundation

struct LiveRecordRepository: RecordRepository {

    func fetchIntakePage(cursor: String?, limit: Int) async throws -> RecordPage {
        let response: PageResponse<IntakeResponse> = try await APIClient.shared.send(
            path: "/v1/intakes",
            queryItems: queryItems(cursor: cursor, limit: limit)
        )
        return RecordPage(items: response.items.map { .intake($0.toRecord()) }, nextCursor: response.nextCursor)
    }

    func fetchInsulinPage(cursor: String?, limit: Int) async throws -> RecordPage {
        let response: PageResponse<InsulinEventResponse> = try await APIClient.shared.send(
            path: "/v1/insulin-events",
            queryItems: queryItems(cursor: cursor, limit: limit)
        )
        return RecordPage(items: response.items.map { .insulin($0.toRecord()) }, nextCursor: response.nextCursor)
    }

    private func queryItems(cursor: String?, limit: Int) -> [URLQueryItem] {
        var items = [URLQueryItem(name: "limit", value: String(limit))]
        if let cursor {
            items.append(URLQueryItem(name: "cursor", value: cursor))
        }
        return items
    }
}

extension IntakeResponse {
    func toRecord() -> IntakeRecord {
        IntakeRecord(
            id: id,
            occurredAt: occurredAt,
            context: IntakeContext(rawValue: context) ?? .meal,
            items: items.map { FoodItem(id: $0.id, name: $0.name, count: $0.count, unit: $0.unit) }
        )
    }
}
