//
//  RootTabView.swift
//  glucose
//

import SwiftUI

struct RootTabView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("홈", systemImage: "house") }

            RecordsView()
                .tabItem { Label("기록", systemImage: "list.bullet") }

            ReportsView()
                .tabItem { Label("리포트", systemImage: "chart.line.uptrend.xyaxis") }

            SettingsView()
                .tabItem { Label("설정", systemImage: "gearshape") }
        }
    }
}

#Preview {
    RootTabView()
}
