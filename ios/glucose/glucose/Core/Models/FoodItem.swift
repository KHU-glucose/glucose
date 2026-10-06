//
//  FoodItem.swift
//  glucose
//

import Foundation

struct FoodItem: Identifiable, Hashable {
    let id: UUID
    let name: String
    let count: Int?
    let unit: String?

    var displayQuantity: String? {
        guard let count, let unit else { return nil }
        return "\(count)\(unit)"
    }
}
