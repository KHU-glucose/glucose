//
//  SettingsView.swift
//  glucose
//

import SwiftUI

struct SettingsView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "준비 중",
                systemImage: "gearshape",
                description: Text("로그인·계정 설정은 다음 PR에서 채워집니다.")
            )
            .navigationTitle("설정")
        }
    }
}

#Preview {
    SettingsView()
}
