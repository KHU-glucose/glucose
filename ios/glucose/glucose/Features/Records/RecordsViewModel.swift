//
//  RecordsViewModel.swift
//  glucose
//
//  intake/insulin-event는 서버에서 각각 독립적으로 cursor 페이지네이션된다.
//  두 스트림을 시간순으로 올바르게 병합하기 위해, 각자 버퍼를 두고 둘 중 더 최신인
//  항목만 하나씩 꺼내 보여준다(한쪽 버퍼가 비어있는데 그 소스가 아직 안 끝났으면
//  먼저 채운 뒤에만 비교한다 - 그래야 페이지 경계에서 순서가 뒤섞이지 않는다).
//

import Foundation

@Observable
final class RecordsViewModel {

    enum LoadState: Equatable {
        case idle
        case loaded
        case error(String)
    }

    private(set) var items: [RecordItem] = []
    private(set) var state: LoadState = .idle
    private(set) var isLoadingMore = false
    private(set) var hasMore = true

    private let repository: RecordRepository
    private let pageSize = 20

    private var intakeBuffer: [RecordItem] = []
    private var insulinBuffer: [RecordItem] = []
    private var intakeCursor: String?
    private var insulinCursor: String?
    private var intakeExhausted = false
    private var insulinExhausted = false

    init(repository: RecordRepository = LiveRecordRepository()) {
        self.repository = repository
    }

    var isInitialLoading: Bool {
        items.isEmpty && isLoadingMore
    }

    func load() async {
        intakeBuffer = []
        insulinBuffer = []
        intakeCursor = nil
        insulinCursor = nil
        intakeExhausted = false
        insulinExhausted = false
        hasMore = true
        items = []
        await fetchMore()
    }

    func refresh() async {
        await load()
    }

    func loadMoreIfNeeded(currentItem: RecordItem) async {
        guard hasMore, !isLoadingMore else { return }
        guard let index = items.firstIndex(where: { $0.id == currentItem.id }) else { return }
        if index >= items.count - 5 {
            await fetchMore()
        }
    }

    private func fetchMore() async {
        isLoadingMore = true
        defer { isLoadingMore = false }

        var newlyEmitted: [RecordItem] = []

        while newlyEmitted.count < pageSize {
            do {
                if intakeBuffer.isEmpty && !intakeExhausted {
                    try await refillIntake()
                }
                if insulinBuffer.isEmpty && !insulinExhausted {
                    try await refillInsulin()
                }
            } catch {
                items.append(contentsOf: newlyEmitted)
                hasMore = hasRemainingData
                state = .error(Self.errorMessage(for: error))
                return
            }

            guard let next = popNextItem() else { break }
            newlyEmitted.append(next)
        }

        items.append(contentsOf: newlyEmitted)
        hasMore = hasRemainingData
        state = .loaded
    }

    private var hasRemainingData: Bool {
        !intakeBuffer.isEmpty || !insulinBuffer.isEmpty || !intakeExhausted || !insulinExhausted
    }

    private func popNextItem() -> RecordItem? {
        switch (intakeBuffer.first, insulinBuffer.first) {
        case (nil, nil):
            return nil
        case (let intake?, nil):
            intakeBuffer.removeFirst()
            return intake
        case (nil, let insulin?):
            insulinBuffer.removeFirst()
            return insulin
        case (let intake?, let insulin?):
            if intake.occurredAt >= insulin.occurredAt {
                intakeBuffer.removeFirst()
                return intake
            } else {
                insulinBuffer.removeFirst()
                return insulin
            }
        }
    }

    private func refillIntake() async throws {
        let page = try await repository.fetchIntakePage(cursor: intakeCursor, limit: pageSize)
        intakeBuffer.append(contentsOf: page.items)
        intakeCursor = page.nextCursor
        if page.nextCursor == nil { intakeExhausted = true }
    }

    private func refillInsulin() async throws {
        let page = try await repository.fetchInsulinPage(cursor: insulinCursor, limit: pageSize)
        insulinBuffer.append(contentsOf: page.items)
        insulinCursor = page.nextCursor
        if page.nextCursor == nil { insulinExhausted = true }
    }

    private static func errorMessage(for error: Error) -> String {
        guard let apiError = error as? APIError else { return "기록을 불러오지 못했습니다." }
        switch apiError {
        case .network:
            return "네트워크 연결을 확인해주세요."
        case .notAuthenticated:
            return "로그인이 필요합니다."
        default:
            return "기록을 불러오지 못했습니다."
        }
    }
}
