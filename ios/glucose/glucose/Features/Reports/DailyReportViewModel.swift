//
//  DailyReportViewModel.swift
//  glucose
//
//  숫자는 전부 서버가 계산한다. 앱은 받은 값을 그대로 보여주기만 한다(같은 데이터면 같은 화면).
//

import Foundation

@Observable
final class DailyReportViewModel {

    enum State {
        case loading
        case loaded(DailyReport, [GlucosePoint])
        case failed(String)
    }

    private(set) var day: Date
    private(set) var state: State = .loading

    init(day: Date = ReportDay.today()) {
        self.day = day
    }

    var canGoForward: Bool {
        day < ReportDay.today()
    }

    func move(byDays days: Int) async {
        guard let newDay = ReportDay.calendar.date(byAdding: .day, value: days, to: day) else { return }
        day = min(newDay, ReportDay.today())
        await load()
    }

    func show(day newDay: Date) async {
        day = ReportDay.calendar.startOfDay(for: newDay)
        await load()
    }

    func load() async {
        let requestedDay = day
        let dayString = ReportDay.apiString(from: requestedDay)
        state = .loading

        do {
            let report: DailyReport = try await APIClient.shared.send(path: "/v1/reports/daily/\(dayString)")
            let points = report.glucose == nil ? [] : await fetchPoints(dayString: dayString, day: requestedDay)
            guard requestedDay == day else { return } // 그 사이 다른 날로 넘어갔으면 버린다
            state = .loaded(report, points)
        } catch {
            guard requestedDay == day else { return }
            state = .failed(Self.message(for: error))
        }
    }

    private func fetchPoints(dayString: String, day: Date) async -> [GlucosePoint] {
        do {
            let readings: GlucoseReadings = try await APIClient.shared.send(path: "/v1/glucose-readings/\(dayString)")
            return GlucosePoint.points(from: readings.readings, on: day, calendar: ReportDay.calendar)
        } catch {
            return [] // 곡선만 못 그리고, 리포트 숫자는 그대로 보여준다
        }
    }

    private static func message(for error: Error) -> String {
        if case APIError.network = error {
            return "네트워크 연결을 확인해주세요."
        }
        return "리포트를 불러오지 못했어요."
    }
}
