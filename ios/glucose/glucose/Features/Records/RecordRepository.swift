//
//  RecordRepository.swift
//  glucose
//
//  서버가 준비되기 전엔 Mock 구현으로 화면을 만든다. 서버가 되면 Live 구현으로 교체한다(화면 코드는 그대로).
//

protocol RecordRepository {
    func fetchRecords() async throws -> [RecordItem]
}
