//
//  SettingsViewModel.swift
//  glucose
//

import Foundation

@Observable
final class SettingsViewModel {

    private(set) var isDeleting = false
    private(set) var errorMessage: String?

    func signOut() {
        AuthSession.shared.signOut()
    }

    func deleteAccount() async -> Bool {
        isDeleting = true
        errorMessage = nil
        defer { isDeleting = false }

        do {
            try await APIClient.shared.sendVoid(path: "/v1/me", method: .delete)
            AuthSession.shared.signOut()
            return true
        } catch {
            errorMessage = "계정 삭제에 실패했습니다. 다시 시도해주세요."
            return false
        }
    }
}
