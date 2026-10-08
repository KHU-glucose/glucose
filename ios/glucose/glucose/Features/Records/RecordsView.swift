//
//  RecordsView.swift
//  glucose
//

import SwiftUI

struct RecordsView: View {
    @State private var viewModel = RecordsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.items.isEmpty {
                    ContentUnavailableView(
                        "기록이 없어요",
                        systemImage: "list.bullet",
                        description: Text("사진을 올리거나 인슐린을 기록하면 여기에 쌓여요.")
                    )
                } else {
                    List(viewModel.sortedByRecent) { item in
                        NavigationLink(value: item) {
                            RecordRow(item: item)
                        }
                    }
                }
            }
            .navigationTitle("기록")
            .navigationDestination(for: RecordItem.self) { item in
                RecordDetailView(item: item)
            }
            .task { await viewModel.load() }
        }
    }
}

#Preview {
    RecordsView()
}
