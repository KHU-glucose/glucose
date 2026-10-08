//
//  EditableFoodItem.swift
//  glucose
//

import Foundation

struct EditableFoodItem: Identifiable {
    let id = UUID()
    var name: String
    var count: Int
    var unit: String
    var categoryHint: String?
    var tags: [String]

    static func blank() -> EditableFoodItem {
        EditableFoodItem(name: "", count: 1, unit: "개", categoryHint: nil, tags: [])
    }

    func toRequest() -> IntakeItemRequest {
        IntakeItemRequest(name: name, count: count, unit: unit, categoryHint: categoryHint, tags: tags)
    }
}

extension RecognizedFoodItem {
    var asEditableItem: EditableFoodItem {
        EditableFoodItem(name: name, count: count ?? 1, unit: unit ?? "개", categoryHint: categoryHint, tags: tags)
    }
}
