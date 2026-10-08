//
//  MockRecordRepository.swift
//  glucose
//

import Foundation

struct MockRecordRepository: RecordRepository {

    func fetchRecords() async throws -> [RecordItem] {
        let now = Date()
        let calendar = Calendar.current

        func at(_ hour: Int, _ minute: Int = 0, daysAgo: Int = 0) -> Date {
            let day = calendar.date(byAdding: .day, value: -daysAgo, to: now) ?? now
            return calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
        }

        return [
            .intake(IntakeRecord(
                id: UUID(),
                occurredAt: at(8, 10),
                context: .meal,
                items: [
                    FoodItem(id: UUID(), name: "현미밥", count: 1, unit: "공기"),
                    FoodItem(id: UUID(), name: "계란말이", count: 1, unit: "조각"),
                ]
            )),
            .insulin(InsulinRecord(id: UUID(), occurredAt: at(8, 5), units: 6, kind: "식사")),
            .intake(IntakeRecord(
                id: UUID(),
                occurredAt: at(10, 30),
                context: .hypoTreatment,
                items: [
                    FoodItem(id: UUID(), name: "포도당 캔디", count: 3, unit: "개"),
                ]
            )),
            .intake(IntakeRecord(
                id: UUID(),
                occurredAt: at(13, 0, daysAgo: 1),
                context: .meal,
                items: [
                    FoodItem(id: UUID(), name: "짜장면", count: 1, unit: "그릇"),
                ]
            )),
            .insulin(InsulinRecord(id: UUID(), occurredAt: at(12, 50, daysAgo: 1), units: 8, kind: "식사")),
            .intake(IntakeRecord(
                id: UUID(),
                occurredAt: at(16, 0, daysAgo: 1),
                context: .snack,
                items: [
                    FoodItem(id: UUID(), name: "바나나", count: 1, unit: "개"),
                ]
            )),
            .insulin(InsulinRecord(id: UUID(), occurredAt: at(22, 0, daysAgo: 1), units: 10, kind: "기저")),
        ]
    }
}
