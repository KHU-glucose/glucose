//
//  GlucoseDayChart.swift
//  glucose
//
//  하루 혈당 곡선 + 식사(에피소드)·인슐린 마커. 끊긴 구간은 선을 잇지 않는다:
//  값이 없는 칸이 있거나 두 값 사이가 15분보다 벌어지면 다른 선(series)으로 나눠 그린다.
//

import Charts
import SwiftUI

struct GlucosePoint: Identifiable {
    let time: Date
    let value: Int
    let segment: Int

    var id: Date { time }

    static let interval: TimeInterval = 15 * 60

    /// readings를 시각순 점으로 바꾸고, 끊긴 곳마다 segment 번호를 올린다.
    static func points(from readings: [GlucoseReadings.Reading], on day: Date, calendar: Calendar) -> [GlucosePoint] {
        var points: [GlucosePoint] = []
        var segment = 0
        var previousWasMissing = false

        let sorted = readings.compactMap { reading -> (Date, Int?)? in
            guard let time = time(of: reading.time, on: day, calendar: calendar) else { return nil }
            return (time, reading.value)
        }
        .sorted { $0.0 < $1.0 }

        for (time, value) in sorted {
            guard let value else {
                previousWasMissing = true
                continue
            }
            if let last = points.last, previousWasMissing || time.timeIntervalSince(last.time) > interval {
                segment += 1
            }
            points.append(GlucosePoint(time: time, value: value, segment: segment))
            previousWasMissing = false
        }
        return points
    }

    private static func time(of hhmm: String, on day: Date, calendar: Calendar) -> Date? {
        let parts = hhmm.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        return calendar.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: day)
    }
}

struct GlucoseDayChart: View {
    let day: Date
    let points: [GlucosePoint]
    let episodes: [DailyReport.Episode]
    let insulinEvents: [DailyReport.InsulinMarker]

    private var dayRange: ClosedRange<Date> {
        let start = ReportDay.calendar.startOfDay(for: day)
        let end = ReportDay.calendar.date(byAdding: .day, value: 1, to: start) ?? start
        return start...end
    }

    private var isolatedPoints: [GlucosePoint] {
        let counts = Dictionary(grouping: points, by: \.segment).mapValues(\.count)
        return points.filter { counts[$0.segment] == 1 }
    }

    var body: some View {
        Chart {
            ForEach(points) { point in
                LineMark(
                    x: .value("시각", point.time),
                    y: .value("혈당", point.value),
                    series: .value("구간", point.segment)
                )
                .interpolationMethod(.linear)
                .foregroundStyle(Color.accentColor)
            }

            // 앞뒤가 다 끊긴 단독 값은 선이 안 그려지므로 점으로 표시한다.
            ForEach(isolatedPoints) { point in
                PointMark(x: .value("시각", point.time), y: .value("혈당", point.value))
                    .symbolSize(16)
                    .foregroundStyle(Color.accentColor)
            }

            ForEach(episodes) { episode in
                RuleMark(x: .value("기록", episode.startAt))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .foregroundStyle(.orange)
                    .annotation(position: .top, spacing: 2) {
                        Image(systemName: episode.effectiveContext.systemImage)
                            .font(.caption2)
                            .foregroundStyle(.orange)
                    }
            }

            ForEach(Array(insulinEvents.enumerated()), id: \.offset) { _, event in
                RuleMark(x: .value("인슐린", event.occurredAt))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [1, 2]))
                    .foregroundStyle(.purple)
                    .annotation(position: .bottom, spacing: 2) {
                        Image(systemName: "syringe")
                            .font(.caption2)
                            .foregroundStyle(.purple)
                    }
            }
        }
        .chartXScale(domain: dayRange)
        // 리브레 일일 그래프의 세로축이 50~350 mg/dL라서(ml-service 계약) 날마다 같은 축으로 고정해 비교하기 쉽게 한다.
        .chartYScale(domain: 40...360)
        .chartXAxis {
            AxisMarks(values: .stride(by: .hour, count: 6)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let date = value.as(Date.self) {
                        Text("\(ReportDay.calendar.component(.hour, from: date))시")
                    }
                }
            }
        }
        .chartYAxisLabel("mg/dL")
        .frame(height: 220)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("하루 혈당 곡선")
        .accessibilityValue(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        guard let minValue = points.map(\.value).min(), let maxValue = points.map(\.value).max() else {
            return "값 없음"
        }
        return "최저 \(minValue), 최고 \(maxValue) mg/dL, 식사 기록 \(episodes.count)개, 인슐린 기록 \(insulinEvents.count)개"
    }
}
