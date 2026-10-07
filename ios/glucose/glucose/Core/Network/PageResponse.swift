//
//  PageResponse.swift
//  glucose
//
//  docs/backend-api.md의 cursor 페이지네이션 응답 모양: { items, next_cursor }
//

import Foundation

struct PageResponse<Item: Decodable>: Decodable {
    let items: [Item]
    let nextCursor: String?
}
