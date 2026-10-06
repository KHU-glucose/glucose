//
//  APIError.swift
//  glucose
//
//  서버 오류 응답 규약: {code, message, request_id} (docs/backend-api.md)
//

import Foundation

struct APIErrorBody: Decodable {
    let code: String
    let message: String
    let requestId: String?
}

enum APIError: Error {
    case notAuthenticated
    case server(status: Int, body: APIErrorBody)
    case invalidResponse
    case network(Error)
    case decoding(Error)
}
