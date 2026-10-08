//
//  IntakeContext.swift
//  glucose
//
//  서버/ml-service 계약과 같은 값 (docs/ml-service-contract.md 참고)
//

import Foundation

enum IntakeContext: String, Hashable, CaseIterable, Codable {
    case meal = "MEAL"
    case snack = "SNACK"
    case hypoTreatment = "HYPO_TREATMENT"
    case alcohol = "ALCOHOL"

    var displayName: String {
        switch self {
        case .meal: "식사"
        case .snack: "간식"
        case .hypoTreatment: "저혈당 처치"
        case .alcohol: "음주"
        }
    }

    var systemImage: String {
        switch self {
        case .meal: "fork.knife"
        case .snack: "takeoutbag.and.cup.and.straw"
        case .hypoTreatment: "cross.case"
        case .alcohol: "wineglass"
        }
    }
}
