//
//  AuthSession.swift
//  glucose
//
//  로그인 상태의 단일 소스. 토큰은 Keychain에만 저장하고, 앱 어디서든 이걸 통해서만 읽는다.
//

import Foundation

@Observable
final class AuthSession {

    static let shared = AuthSession()

    private let keychain = KeychainStore()
    private let accessTokenKey = "access_token"
    private let refreshTokenKey = "refresh_token"

    private(set) var accessToken: String?
    private(set) var isAuthenticated: Bool

    private init() {
        let token = keychain.get(accessTokenKey)
        accessToken = token
        isAuthenticated = token != nil
    }

    func signIn(with tokens: AuthTokens) {
        store(tokens)
    }

    func signOut() {
        keychain.delete(accessTokenKey)
        keychain.delete(refreshTokenKey)
        accessToken = nil
        isAuthenticated = false
    }

    /// access 토큰 만료(401) 시 1회 시도한다. 성공하면 true.
    func refreshAccessToken() async -> Bool {
        guard let refreshToken = keychain.get(refreshTokenKey) else { return false }
        do {
            let tokens: AuthTokens = try await APIClient.shared.send(
                path: "/v1/auth/refresh",
                method: .post,
                body: RefreshRequest(refreshToken: refreshToken),
                authenticated: false
            )
            store(tokens)
            return true
        } catch {
            return false
        }
    }

    private func store(_ tokens: AuthTokens) {
        keychain.set(tokens.accessToken, for: accessTokenKey)
        keychain.set(tokens.refreshToken, for: refreshTokenKey)
        accessToken = tokens.accessToken
        isAuthenticated = true
    }
}
