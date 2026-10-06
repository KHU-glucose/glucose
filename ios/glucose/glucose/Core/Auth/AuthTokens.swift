//
//  AuthTokens.swift
//  glucose
//

struct AuthTokens: Decodable {
    let accessToken: String
    let refreshToken: String
    let tokenType: String
    let expiresIn: Int
}

struct AppleLoginRequest: Encodable {
    let identityToken: String
    let nonce: String?
}

struct RefreshRequest: Encodable {
    let refreshToken: String
}
