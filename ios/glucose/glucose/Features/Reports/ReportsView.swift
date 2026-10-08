//
//  ReportsView.swift
//  glucose
//
//  일일/주간 전환. 뷰모델을 여기서 들고 있어서 전환해도 보던 날짜·주가 유지된다.
//

import SwiftUI

struct ReportsView: View {
    private enum Mode: String, CaseIterable {
        case daily = "일일"
        case weekly = "주간"
    }

    @State private var mode: Mode = .daily
    @State private var dailyViewModel = DailyReportViewModel()
    @State private var weeklyViewModel = WeeklyReportViewModel()

    var body: some View {
        NavigationStack {
            Group {
                switch mode {
                case .daily:
                    DailyReportView(viewModel: dailyViewModel)
                case .weekly:
                    WeeklyReportView(viewModel: weeklyViewModel)
                }
            }
            .navigationTitle("리포트")
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Picker("보기", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 160)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

#Preview {
    ReportsView()
}
