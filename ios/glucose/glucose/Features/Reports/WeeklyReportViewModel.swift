//
//  WeeklyReportViewModel.swift
//  glucose
//
//  서버와 같은 ISO 주(월~일, 한국 시간) 단위로 이동한다.
//

import Foundation

@Observable
final class WeeklyReportViewModel {

    enum State {
        case loading
        case loaded(WeeklyReport)
        case failed(String)
    }

    private(set) var weekStart: Date
    private(set) var state: State = .loading

    init(containing day: Date = ReportDay.today()) {
        self.weekStart = Self.startOfWeek(containing: day)
    }

    var weekEnd: Date {
        ReportDay.calendar.date(byAdding: .day, value: 6, to: weekStart) ?? weekStart
    }

    var canGoForward: Bool {
        weekStart < Self.startOfWeek(containing: ReportDay.today())
    }

    func move(byWeeks weeks: Int) async {
        guard let newStart = ReportDay.calendar.date(byAdding: .weekOfYear, value: weeks, to: weekStart) else { return }
        weekStart = min(newStart, Self.startOfWeek(containing: ReportDay.today()))
        await load()
    }

    func load() async {
        let requestedWeek = weekStart
        state = .loading
        do {
            let report: WeeklyReport = try await APIClient.shared.send(
                path: "/v1/reports/weekly/\(ReportDay.apiString(from: requestedWeek))"
            )
            guard requestedWeek == weekStart else { return }
            state = .loaded(report)
        } catch {
            guard requestedWeek == weekStart else { return }
            if case APIError.network = error {
                state = .failed("네트워크 연결을 확인해주세요.")
            } else {
                state = .failed("주간 리포트를 불러오지 못했어요.")
            }
        }
    }

    private static func startOfWeek(containing day: Date) -> Date {
        ReportDay.calendar.dateInterval(of: .weekOfYear, for: day)?.start ?? ReportDay.calendar.startOfDay(for: day)
    }
}
