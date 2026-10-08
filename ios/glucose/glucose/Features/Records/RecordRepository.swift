//
//  RecordRepository.swift
//  glucose
//
//  intake와 insulin-event는 서버에서 각각 독립적으로 cursor 페이지네이션된다.
//  화면에는 하나의 시간순 타임라인으로 합쳐서 보여준다(RecordsViewModel에서 병합).
//

struct RecordPage {
    let items: [RecordItem]
    let nextCursor: String?
}

protocol RecordRepository {
    func fetchIntakePage(cursor: String?, limit: Int) async throws -> RecordPage
    func fetchInsulinPage(cursor: String?, limit: Int) async throws -> RecordPage
}
