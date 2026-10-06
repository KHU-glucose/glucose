//
//  IntakeRecord.swift
//  glucose
//

import Foundation

struct IntakeRecord: Identifiable, Hashable {
    let id: UUID
    let occurredAt: Date
    let context: IntakeContext
    let items: [FoodItem]

    var summary: String {
        items.map(\.name).joined(separator: ", ")
    }
}
