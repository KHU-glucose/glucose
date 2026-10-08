//
//  ReportDay.swift
//  glucose
//
//  서버의 날짜(그래프 날짜, 리포트 날짜)는 한국 시간 기준 "yyyy-MM-dd" 문자열이다.
//  APIClient 디코더는 ISO-8601 날짜시각만 읽을 수 있어서, 이런 날짜 필드는 String으로 받고 여기서 변환한다.
//

import Foundation

enum ReportDay {

    static let timeZone = TimeZone(identifier: "Asia/Seoul")!

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.firstWeekday = 2 // 월요일 시작 (서버 주간 리포트와 동일한 ISO 주)
        return calendar
    }

    private static let apiFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    private static let displayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.timeZone = timeZone
        formatter.dateFormat = "M월 d일 (E)"
        return formatter
    }()

    static func apiString(from date: Date) -> String {
        apiFormatter.string(from: date)
    }

    static func date(fromAPIString string: String) -> Date? {
        apiFormatter.date(from: string)
    }

    static func displayString(from date: Date) -> String {
        displayFormatter.string(from: date)
    }

    static func displayString(fromAPIString string: String) -> String {
        guard let date = date(fromAPIString: string) else { return string }
        return displayString(from: date)
    }

    static func today() -> Date {
        calendar.startOfDay(for: .now)
    }
}
