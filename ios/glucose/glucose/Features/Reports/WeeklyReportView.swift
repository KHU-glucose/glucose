//
//  WeeklyReportView.swift
//  glucose
//
//  주간 합계만 보여준다(숫자는 서버 계산). 그래프가 없는 날은 평균·반동 판단에서 빠지므로 그 날 수를 같이 보여준다.
//

import SwiftUI

struct WeeklyReportView: View {
    let viewModel: WeeklyReportViewModel

    var body: some View {
        List {
            weekNavigator

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
            case .loaded(let report):
                glucoseSection(report)
                recordsSection(report)
            }
        }
        .refreshable { await viewModel.load() }
        .task { await viewModel.load() }
    }

    private var weekNavigator: some View {
        HStack {
            Button {
                Task { await viewModel.move(byWeeks: -1) }
            } label: {
                Image(systemName: "chevron.left")
            }
            .accessibilityLabel("이전 주")

            Spacer()
            Text("\(ReportDay.displayString(from: viewModel.weekStart)) ~ \(ReportDay.displayString(from: viewModel.weekEnd))")
                .font(.headline)
            Spacer()

            Button {
                Task { await viewModel.move(byWeeks: 1) }
            } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(!viewModel.canGoForward)
            .accessibilityLabel("다음 주")
        }
        .buttonStyle(.borderless)
    }

    @ViewBuilder
    private func glucoseSection(_ report: WeeklyReport) -> some View {
        Section("혈당") {
            if let average = report.averageGlucose {
                LabeledContent("평균 혈당") {
                    Text("\(String(format: "%.0f", average)) mg/dL").monospacedDigit()
                }
            } else {
                Label("이번 주는 혈당 그래프가 없어서 판단할 수 없어요", systemImage: "questionmark.circle")
                    .foregroundStyle(.secondary)
            }

            Text("근거: 그래프가 있는 날 \(report.daysWithData)일 / 7일")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func recordsSection(_ report: WeeklyReport) -> some View {
        Section("기록") {
            LabeledContent("먹은 기록", value: "\(report.episodesCount)회")
            LabeledContent("저혈당 처치 후 반동", value: "\(report.reboundCount)회")
            LabeledContent("인슐린 기록", value: "\(report.insulinEventsCount)건")

            if report.daysInsufficient > 0 {
                Text("그래프가 없는 \(report.daysInsufficient)일은 반동 여부를 판단하지 못해 횟수에 들어가지 않아요.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

#Preview {
    NavigationStack {
        WeeklyReportView(viewModel: WeeklyReportViewModel())
    }
}
