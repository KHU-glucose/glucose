//
//  InsulinRecord.swift
//  glucose
//
//  입력만 받는다. 용량을 추천·판단하지 않는다.
//

import Foundation

struct InsulinRecord: Identifiable, Hashable {
    let id: UUID
    let occurredAt: Date
    let units: Double
    let kind: String
}
