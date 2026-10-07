//
//  RecognitionResultView.swift
//  glucose
//
//  사진 -> 인식 결과 -> 수정 -> 저장을 한 흐름으로 잇는 화면.
//

import SwiftUI

struct RecognitionResultView: View {
    @State private var viewModel: RecognitionResultViewModel
    @Environment(\.dismiss) private var dismiss
    let onSaved: () -> Void

    init(photoId: UUID, context: IntakeContext, onSaved: @escaping () -> Void) {
        _viewModel = State(initialValue: RecognitionResultViewModel(photoId: photoId, context: context))
        self.onSaved = onSaved
    }

    var body: some View {
        Form {
            switch viewModel.state {
            case .polling:
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("음식을 인식하는 중...")
                    }
                }
            case .notFoodPhoto:
                Section {
                    Text("음식 사진이 아닌 것 같아요. 직접 항목을 추가해주세요.")
                        .foregroundStyle(.secondary)
                }
                itemsSection
            case .failed(let message):
                Section {
                    Text(message).foregroundStyle(.red)
                }
                itemsSection
            case .ready, .saving, .saved:
                itemsSection
            }

            if let saveErrorMessage = viewModel.saveErrorMessage {
                Section {
                    Text(saveErrorMessage).foregroundStyle(.red).font(.footnote)
                }
            }
        }
        .navigationTitle("인식 결과")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(isBusy)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("취소") {
                    viewModel.cancelPolling()
                    dismiss()
                }
                .disabled(isBusy)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("저장") {
                    Task {
                        if await viewModel.confirm() {
                            onSaved()
                        }
                    }
                }
                .disabled(!canSave)
            }
        }
        .onAppear { viewModel.startPolling() }
    }

    private var isBusy: Bool {
        viewModel.state == .saving
    }

    private var canSave: Bool {
        switch viewModel.state {
        case .ready, .notFoodPhoto, .failed:
            return viewModel.isSaveEnabled
        default:
            return false
        }
    }

    @ViewBuilder
    private var itemsSection: some View {
        Section("항목") {
            ForEach(viewModel.items) { item in
                itemRow(item)
            }
            .onDelete { offsets in
                for offset in offsets {
                    viewModel.removeItem(id: viewModel.items[offset].id)
                }
            }

            Button {
                viewModel.addBlankItem()
            } label: {
                Label("항목 추가", systemImage: "plus")
            }
        }
    }

    private func itemRow(_ item: EditableFoodItem) -> some View {
        HStack {
            TextField("음식 이름", text: Binding(
                get: { item.name },
                set: { viewModel.updateItem(id: item.id, name: $0) }
            ))

            Stepper(value: Binding(
                get: { item.count },
                set: { viewModel.updateItem(id: item.id, count: $0) }
            ), in: 1...99) {
                Text("\(item.count)\(item.unit)")
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .fixedSize()
        }
    }
}

#Preview {
    NavigationStack {
        RecognitionResultView(photoId: UUID(), context: .meal) {}
    }
}
