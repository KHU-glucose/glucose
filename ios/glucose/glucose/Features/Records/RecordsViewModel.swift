//
//  RecordsViewModel.swift
//  glucose
//

import Foundation

@Observable
final class RecordsViewModel {

    private(set) var items: [RecordItem] = []
    private(set) var isLoading = false

    private let repository: RecordRepository

    init(repository: RecordRepository = MockRecordRepository()) {
        self.repository = repository
    }

    var sortedByRecent: [RecordItem] {
        items.sorted { $0.occurredAt > $1.occurredAt }
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        items = (try? await repository.fetchRecords()) ?? []
    }
}
