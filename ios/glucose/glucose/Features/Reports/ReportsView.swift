//
//  ReportsView.swift
//  glucose
//

import SwiftUI

struct ReportsView: View {
    var body: some View {
        NavigationStack {
            DailyReportView()
                .navigationTitle("리포트")
        }
    }
}

#Preview {
    ReportsView()
}
