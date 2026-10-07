//
//  APIClient.swift
//  glucose
//
//  서버와의 모든 통신을 여기로 모은다. 화면·ViewModel 코드는 URLSession을 직접 쓰지 않는다.
//  base URL은 환경별로 Config/*.xcconfig -> Info.plist의 API_BASE_URL에서 읽는다.
//

import Foundation

final class APIClient {

    static let shared = APIClient()

    private let baseURL: URL
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        let urlString = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String ?? ""
        guard let url = URL(string: urlString) else {
            fatalError("API_BASE_URL을 읽을 수 없습니다. Config/Debug.xcconfig를 만들었는지 확인하세요.")
        }
        baseURL = url

        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        session = URLSession(configuration: configuration)

        encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        encoder.dateEncodingStrategy = .iso8601

        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
    }

    /// JSON 응답을 디코딩해서 돌려준다.
    @discardableResult
    func send<Response: Decodable>(
        path: String,
        method: HTTPMethod = .get,
        queryItems: [URLQueryItem] = [],
        body: Encodable? = nil,
        authenticated: Bool = true
    ) async throws -> Response {
        let (data, _) = try await performWithRetry(path: path, method: method, queryItems: queryItems, body: body, authenticated: authenticated, allowRetry: true)
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    /// 응답 본문이 없는 요청(204 No Content 등).
    func sendVoid(
        path: String,
        method: HTTPMethod,
        queryItems: [URLQueryItem] = [],
        body: Encodable? = nil,
        authenticated: Bool = true
    ) async throws {
        _ = try await performWithRetry(path: path, method: method, queryItems: queryItems, body: body, authenticated: authenticated, allowRetry: true)
    }

    private func performWithRetry(
        path: String,
        method: HTTPMethod,
        queryItems: [URLQueryItem],
        body: Encodable?,
        authenticated: Bool,
        allowRetry: Bool
    ) async throws -> (Data, HTTPURLResponse) {
        let request = try makeRequest(path: path, method: method, queryItems: queryItems, body: body, authenticated: authenticated)
        let (data, response) = try await execute(request)

        if response.statusCode == 401, authenticated, allowRetry {
            let refreshed = await AuthSession.shared.refreshAccessToken()
            if refreshed {
                return try await performWithRetry(path: path, method: method, queryItems: queryItems, body: body, authenticated: authenticated, allowRetry: false)
            }
            await AuthSession.shared.signOut()
            throw APIError.notAuthenticated
        }

        guard (200..<300).contains(response.statusCode) else {
            if let errorBody = try? decoder.decode(APIErrorBody.self, from: data) {
                throw APIError.server(status: response.statusCode, body: errorBody)
            }
            throw APIError.invalidResponse
        }

        return (data, response)
    }

    private func makeRequest(path: String, method: HTTPMethod, queryItems: [URLQueryItem], body: Encodable?, authenticated: Bool) throws -> URLRequest {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        if !queryItems.isEmpty {
            components?.queryItems = queryItems
        }
        guard let url = components?.url else {
            throw APIError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if authenticated {
            guard let token = AuthSession.shared.accessToken else {
                throw APIError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            do {
                request.httpBody = try encoder.encode(body)
            } catch {
                throw APIError.decoding(error)
            }
        }

        return request
    }

    private func execute(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }
            return (data, httpResponse)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.network(error)
        }
    }
}
