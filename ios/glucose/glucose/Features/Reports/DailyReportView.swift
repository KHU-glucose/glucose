//
//  DailyReportView.swift
//  glucose
//
//  사실만 보여준다: 혈당이 어땠는지, 무엇을 언제 기록했는지. 인슐린 용량·식사량 조언은 하지 않는다.
//  판단에 필요한 데이터가 없으면 "아니다"가 아니라 "판단할 수 없다"고 표시한다.
//

import SwiftUI

struct DailyReportView: View {
    let viewModel: DailyReportViewModel
    @State private var showGraphUpload = false

    var body: some View {
        List {
            dayNavigator

            switch viewModel.state {
            case .loading:
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            case .failed(let message):
                Section {
                    Text(message).foregroundStyle(.secondary)
                    Button("다시 시도") { Task { await viewModel.load() } }
                }
            case .loaded(let report, let points):
                glucoseSection(report: report, points: points)
                episodesSection(report.episodes)
                insulinSection(report: report)
                educationSection(report.educationCards)
            }
        }
        .refreshable { await viewModel.load() }
        .task { await viewModel.load() }
        .sheet(isPresented: $showGraphUpload) {
            GraphUploadView { uploadedDate in
                guard let date = ReportDay.date(fromAPIString: uploadedDate) else { return }
                Task { await viewModel.show(day: date) }
            }
        }
    }

    // MARK: - 날짜 이동

    private var dayNavigator: some View {
        HStack {
            Button {
                Task { await viewModel.move(byDays: -1) }
            } label: {
                Image(systemName: "chevron.left")
            }
            .accessibilityLabel("이전 날")

            Spacer()
            Text(ReportDay.displayString(from: viewModel.day))
                .font(.headline)
            Spacer()

            Button {
                Task { await viewModel.move(byDays: 1) }
            } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(!viewModel.canGoForward)
            .accessibilityLabel("다음 날")
        }
        .buttonStyle(.borderless)
    }

    // MARK: - 혈당

    @ViewBuilder
    private func glucoseSection(report: DailyReport, points: [GlucosePoint]) -> some View {
        Section("혈당") {
            if let glucose = report.glucose {
                if points.isEmpty {
                    Text("곡선을 불러오지 못했어요. 아래로 당겨 새로고침해주세요.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    GlucoseDayChart(
                        day: viewModel.day,
                        points: points,
                        episodes: report.episodes,
                        insulinEvents: report.insulinEvents ?? []
                    )
                    .padding(.vertical, 4)
                }

                HStack {
                    stat("평균", glucose.average.map { String(format: "%.0f", $0) })
                    stat("최저", glucose.min.map(String.init))
                    stat("최고", glucose.max.map(String.init))
                }

                Text("근거: 15분 간격 기록 \(glucose.readingsCount)칸 · 하루의 \(Int((glucose.coverageRatio * 100).rounded()))% · 끊긴 구간은 비워둠")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Label("판단할 수 없어요", systemImage: "questionmark.circle")
                        .font(.headline)
                    Text("이 날의 혈당 그래프가 없어서 혈당 변화와 반동 여부를 판단할 수 없어요.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button {
                        showGraphUpload = true
                    } label: {
                        Label("혈당 그래프 올리기", systemImage: "chart.xyaxis.line")
                    }
                    .buttonStyle(.borderless)
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func stat(_ title: String, _ value: String?) -> some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value ?? "–")
                .font(.title3.monospacedDigit())
            Text("mg/dL")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    // MARK: - 기록(에피소드)

    @ViewBuilder
    private func episodesSection(_ episodes: [DailyReport.Episode]) -> some View {
        Section("먹은 기록 \(episodes.count)회") {
            if episodes.isEmpty {
                Text("이 날 기록이 없어요.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(episodes) { episode in
                    EpisodeRow(episode: episode)
                }
            }
        }
    }

    // MARK: - 인슐린

    @ViewBuilder
    private func insulinSection(report: DailyReport) -> some View {
        Section("인슐린 기록 \(report.insulinEventsCount)건") {
            if let events = report.insulinEvents, !events.isEmpty {
                ForEach(Array(events.enumerated()), id: \.offset) { _, event in
                    HStack {
                        Image(systemName: "syringe")
                            .foregroundStyle(.purple)
                            .accessibilityHidden(true)
                        Text(ReportDay.timeString(from: event.occurredAt))
                            .monospacedDigit()
                        Text("\(event.units.formatted())단위 · \(event.kind)")
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            } else if report.insulinEventsCount == 0 {
                Text("이 날 인슐린 기록이 없어요.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 교육 카드

    @ViewBuilder
    private func educationSection(_ cards: [DailyReport.EducationCard]) -> some View {
        if !cards.isEmpty {
            Section("알아두면 좋아요") {
                ForEach(cards) { card in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(card.title).font(.subheadline.bold())
                        Text(card.body).font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }
}

private struct EpisodeRow: View {
    let episode: DailyReport.Episode

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: episode.effectiveContext.systemImage)
                    .foregroundStyle(.orange)
                    .frame(width: 24)
                    .accessibilityHidden(true)
                Text(ReportDay.timeString(from: episode.startAt))
                    .monospacedDigit()
                Text(episode.effectiveContext.displayName)
                Spacer()
                Text("기록 \(episode.intakeCount)건")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if episode.autoReclassified == true {
                note("직전 혈당이 낮아서 저혈당 처치로 분류했어요 (기록할 때: \(episode.originalContext.displayName))",
                     systemImage: "arrow.triangle.2.circlepath")
            }

            if episode.effectiveContext == .hypoTreatment {
                switch episode.reboundDetected {
                case .some(true):
                    note("처치 후 \(ReportDay.timeString(from: episode.windowEndAt))까지 혈당이 목표범위보다 높아진 구간이 있어요",
                         systemImage: "arrow.up.right")
                case .some(false):
                    note("처치 후 \(ReportDay.timeString(from: episode.windowEndAt))까지 목표범위 위로 오르지 않았어요",
                         systemImage: "checkmark")
                case .none:
                    note("처치 후 혈당 데이터가 없어 반동 여부를 판단할 수 없어요", systemImage: "questionmark.circle")
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func note(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.leading, 32)
    }
}

#Preview {
    NavigationStack {
        DailyReportView(viewModel: DailyReportViewModel())
    }
}
