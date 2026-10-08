//
//  SettingsView.swift
//  glucose
//

import SwiftUI

struct SettingsView: View {
    @State private var viewModel = SettingsViewModel()
    @State private var showSignOutConfirm = false
    @State private var showDeleteConfirm = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button("로그아웃", role: .destructive) {
                        showSignOutConfirm = true
                    }
                }

                Section {
                    Button("계정 삭제", role: .destructive) {
                        showDeleteConfirm = true
                    }
                    .disabled(viewModel.isDeleting)
                } footer: {
                    Text("계정을 삭제하면 모든 기록이 함께 삭제되고 되돌릴 수 없습니다.")
                }

                if let errorMessage = viewModel.errorMessage {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }
            .navigationTitle("설정")
            .confirmationDialog("로그아웃하시겠어요?", isPresented: $showSignOutConfirm, titleVisibility: .visible) {
                Button("로그아웃", role: .destructive) {
                    viewModel.signOut()
                }
            }
            .confirmationDialog(
                "계정을 삭제하시겠어요?",
                isPresented: $showDeleteConfirm,
                titleVisibility: .visible
            ) {
                Button("삭제", role: .destructive) {
                    Task { await viewModel.deleteAccount() }
                }
            } message: {
                Text("이 작업은 되돌릴 수 없습니다.")
            }
        }
    }
}

#Preview {
    SettingsView()
}
