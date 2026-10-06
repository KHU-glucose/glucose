//
//  HomeView.swift
//  glucose
//

import SwiftUI

struct HomeView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "준비 중",
                systemImage: "house",
                description: Text("홈 화면은 다음 PR에서 채워집니다.")
            )
            .navigationTitle("홈")
        }
    }
}

#Preview {
    HomeView()
}
