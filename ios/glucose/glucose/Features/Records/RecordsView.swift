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
                if viewModel.isInitialLoading {
                    ProgressView()
                } else if viewModel.items.isEmpty {
                    ContentUnavailableView(
                        "기록이 없어요",
                        systemImage: "list.bullet",
                        description: Text("사진을 올리거나 인슐린을 기록하면 여기에 쌓여요.")
                    )
                } else {
                    List {
                        ForEach(viewModel.items) { item in
                            NavigationLink(value: item) {
                                RecordRow(item: item)
                            }
                            .onAppear {
                                Task { await viewModel.loadMoreIfNeeded(currentItem: item) }
                            }
                        }

                        if viewModel.isLoadingMore && !viewModel.items.isEmpty {
                            HStack {
                                Spacer()
                                ProgressView()
                                Spacer()
                            }
                        }
                    }
                    .refreshable { await viewModel.refresh() }
                }
            }
            .navigationTitle("기록")
            .navigationDestination(for: RecordItem.self) { item in
                RecordDetailView(item: item) { await viewModel.refresh() }
            }
            .safeAreaInset(edge: .bottom) {
                if case .error(let message) = viewModel.state {
                    errorBanner(message)
                }
            }
            .task { await viewModel.load() }
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack {
            Text(message).font(.footnote)
            Spacer()
            Button("재시도") { Task { await viewModel.refresh() } }
                .font(.footnote)
        }
        .padding()
        .background(.red.opacity(0.15))
    }
}

#Preview {
    RecordsView()
}
