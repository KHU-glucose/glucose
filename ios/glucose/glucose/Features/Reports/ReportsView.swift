//
//  ReportsView.swift
//  glucose
//

import SwiftUI

struct ReportsView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "준비 중",
                systemImage: "chart.line.uptrend.xyaxis",
                description: Text("일일·주간 리포트 화면은 다음 PR에서 채워집니다.")
            )
            .navigationTitle("리포트")
        }
    }
}

#Preview {
    ReportsView()
}
