//
//  LoginView.swift
//  glucose
//

import AuthenticationServices
import SwiftUI

struct LoginView: View {
    @State private var viewModel = AuthViewModel()
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 8) {
                Image(systemName: "drop.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(.tint)
                Text("글루코스")
                    .font(.largeTitle.bold())
                Text("혈당 기록을 시작하려면 로그인하세요")
                    .foregroundStyle(.secondary)
            }

            Spacer()

            VStack(spacing: 12) {
                SignInWithAppleButton(.signIn) { request in
                    request.nonce = viewModel.currentNonce
                } onCompletion: { result in
                    viewModel.handle(result)
                }
                .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
                .frame(height: 50)
                .disabled(viewModel.isLoading)

                if viewModel.isLoading {
                    ProgressView()
                }

                if let errorMessage = viewModel.errorMessage {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .padding(.horizontal, 32)

            Spacer()
        }
        .padding()
    }
}

#Preview {
    LoginView()
}
