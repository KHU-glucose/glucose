//
//  RootView.swift
//  glucose
//

import SwiftUI

struct RootView: View {
    var body: some View {
        if AuthSession.shared.isAuthenticated {
            RootTabView()
        } else {
            LoginView()
        }
    }
}

#Preview {
    RootView()
}
