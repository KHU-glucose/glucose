//
//  HomeView.swift
//  glucose
//

import SwiftUI

struct HomeView: View {
    @State private var showPhotoCapture = false
    @State private var showInsulinForm = false
    @State private var showGraphUpload = false

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

                Button {
                    showInsulinForm = true
                } label: {
                    Label("인슐린 기록하기", systemImage: "syringe.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.bordered)
                .padding(.horizontal)

                Button {
                    showGraphUpload = true
                } label: {
                    Label("혈당 그래프 올리기", systemImage: "chart.xyaxis.line")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.bordered)
                .padding(.horizontal)

                Spacer()
            }
            .navigationTitle("홈")
            .sheet(isPresented: $showPhotoCapture) {
                PhotoCaptureView()
            }
            .sheet(isPresented: $showInsulinForm) {
                InsulinRecordFormView(onSaved: {})
            }
            .sheet(isPresented: $showGraphUpload) {
                GraphUploadView()
            }
        }
    }
}

#Preview {
    HomeView()
}
