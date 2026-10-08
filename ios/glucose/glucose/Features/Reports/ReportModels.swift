//
//  ReportModels.swift
//  glucose
//
//  docs/backend-api.md 14번(혈당 시계열), 16번(리포트) 참고.
//  날짜 필드(date, week_start 등)는 "yyyy-MM-dd" 문자열이라 String으로 받는다(ReportDay 참고).
//

import Foundation

struct DailyReport: Decodable {
    let date: String
    /// nil이면 그날 분석된 그래프가 없다 = 판단 불가
    let glucose: GlucoseSummary?
    let episodes: [Episode]
    let insulinEventsCount: Int
    /// 서버가 아직 이 필드를 안 보내는 버전이면 nil (마커만 안 그려진다)
    let insulinEvents: [InsulinMarker]?
    let educationCards: [EducationCard]

    struct GlucoseSummary: Decodable {
        let coverageRatio: Double
        let average: Double?
        let min: Int?
        let max: Int?
        let readingsCount: Int
    }

    struct Episode: Decodable, Identifiable {
        let startAt: Date
        let originalContext: IntakeContext
        let effectiveContext: IntakeContext
        /// nil = 직전 혈당 데이터가 없어 판단 불가 (false와 다르다)
        let autoReclassified: Bool?
        let windowEndAt: Date
        /// nil = 분석 구간 안에 혈당 데이터가 없어 판단 불가
        let reboundDetected: Bool?
        let intakeCount: Int

        var id: Date { startAt }
    }

    struct InsulinMarker: Decodable {
        let occurredAt: Date
        let units: Double
        let kind: String
    }

    struct EducationCard: Decodable, Identifiable {
        let trigger: String
        let title: String
        let body: String

        var id: String { trigger }
    }
}

struct WeeklyReport: Decodable {
    let weekStart: String
    let weekEnd: String
    let daysWithData: Int
    let daysInsufficient: Int
    /// 그래프가 있는 날들의 일평균을 다시 평균낸 값. 그래프가 하나도 없으면 nil
    let averageGlucose: Double?
    let episodesCount: Int
    let reboundCount: Int
    let insulinEventsCount: Int
}

struct GlucoseReadings: Decodable {
    let date: String
    let coverageRatio: Double?
    let readings: [Reading]

    struct Reading: Decodable {
        /// "HH:mm", 한국 시간 기준
        let time: String
        /// nil = 끊긴 구간(보간하지 않는다)
        let value: Int?
        let flag: String
    }
}
