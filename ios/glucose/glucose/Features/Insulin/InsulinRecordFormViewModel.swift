//
//  InsulinRecordFormViewModel.swift
//  glucose
//
//  입력만 받는다. 단위·시각·종류를 기록할 뿐, 용량을 추천하거나 판단하지 않는다.
//

import Foundation

@Observable
final class InsulinRecordFormViewModel {

    enum Mode: Equatable {
        case create
        case edit(id: UUID)
    }

    let mode: Mode
    var occurredAt: Date
    var units: Double?
    var kind: String

    private(set) var isSaving = false
    private(set) var isDeleting = false
    private(set) var errorMessage: String?

    init(mode: Mode = .create, occurredAt: Date = .now, units: Double? = nil, kind: String = "") {
        self.mode = mode
        self.occurredAt = occurredAt
        self.units = units
        self.kind = kind
    }

    var isSaveEnabled: Bool {
        guard let units, units > 0 else { return false }
        return !kind.trimmingCharacters(in: .whitespaces).isEmpty
    }

    func save() async -> Bool {
        guard let units else { return false }
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }

        let request = InsulinEventRequest(occurredAt: occurredAt, units: units, kind: kind)
        do {
            switch mode {
            case .create:
                let _: InsulinEventResponse = try await APIClient.shared.send(
                    path: "/v1/insulin-events", method: .post, body: request
                )
            case .edit(let id):
                let _: InsulinEventResponse = try await APIClient.shared.send(
                    path: "/v1/insulin-events/\(id.uuidString)", method: .patch, body: request
                )
            }
            return true
        } catch {
            errorMessage = "저장에 실패했습니다. 다시 시도해주세요."
            return false
        }
    }

    func delete() async -> Bool {
        guard case .edit(let id) = mode else { return false }
        isDeleting = true
        errorMessage = nil
        defer { isDeleting = false }

        do {
            try await APIClient.shared.sendVoid(path: "/v1/insulin-events/\(id.uuidString)", method: .delete)
            return true
        } catch {
            errorMessage = "삭제에 실패했습니다. 다시 시도해주세요."
            return false
        }
    }
}
