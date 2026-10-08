//
//  RecordItem.swift
//  glucose
//
//  기록 목록에서 식사/저혈당 처치/인슐린을 하나의 타임라인으로 보여주기 위한 표시용 타입.
//

import Foundation

enum RecordItem: Identifiable, Hashable {
    case intake(IntakeRecord)
    case insulin(InsulinRecord)

    var id: UUID {
        switch self {
        case .intake(let record): record.id
        case .insulin(let record): record.id
        }
    }

    var occurredAt: Date {
        switch self {
        case .intake(let record): record.occurredAt
        case .insulin(let record): record.occurredAt
        }
    }
}
