//
//  HomeView.swift
//  glucose
//

import SwiftUI

struct HomeView: View {
    @State private var showPhotoCapture = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Spacer()

                Button {
                    showPhotoCapture = true
                } label: {
                    Label("사진으로 기록하기", systemImage: "camera.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal)

                Spacer()
            }
            .navigationTitle("홈")
            .sheet(isPresented: $showPhotoCapture) {
                PhotoCaptureView()
            }
        }
    }
}

#Preview {
    HomeView()
}
