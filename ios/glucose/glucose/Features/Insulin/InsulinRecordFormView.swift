//
//  InsulinRecordFormView.swift
//  glucose
//

import SwiftUI

struct InsulinRecordFormView: View {
    @State private var viewModel: InsulinRecordFormViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showDeleteConfirm = false
    @State private var showTimeEditor = false

    private let onSaved: () -> Void
    private let onDeleted: () -> Void

    init(
        mode: InsulinRecordFormViewModel.Mode = .create,
        occurredAt: Date = .now,
        units: Double? = nil,
        kind: String = "",
        onSaved: @escaping () -> Void,
        onDeleted: @escaping () -> Void = {}
    ) {
        _viewModel = State(initialValue: InsulinRecordFormViewModel(mode: mode, occurredAt: occurredAt, units: units, kind: kind))
        self.onSaved = onSaved
        self.onDeleted = onDeleted
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("시각") {
                    if viewModel.mode == .create && !showTimeEditor {
                        HStack {
                            Text("지금")
                            Spacer()
                            Button("시간 변경") { showTimeEditor = true }
                                .font(.footnote)
                        }
                    } else {
                        DatePicker("시각", selection: $viewModel.occurredAt)
                    }
                }

                Section("단위") {
                    TextField("단위 (예: 6)", value: $viewModel.units, format: .number)
                        .keyboardType(.decimalPad)
                }

                Section("종류") {
                    TextField("예: 식사, 기저", text: $viewModel.kind)
                }

                if case .edit = viewModel.mode {
                    Section {
                        Button("삭제", role: .destructive) {
                            showDeleteConfirm = true
                        }
                    }
                }

                if let errorMessage = viewModel.errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }
            }
            .navigationTitle(viewModel.mode == .create ? "인슐린 기록" : "인슐린 기록 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        if viewModel.mode == .create && !showTimeEditor {
                            viewModel.occurredAt = .now
                        }
                        Task {
                            if await viewModel.save() {
                                onSaved()
                                dismiss()
                            }
                        }
                    }
                    .disabled(!viewModel.isSaveEnabled || viewModel.isSaving || viewModel.isDeleting)
                }
            }
            .confirmationDialog("이 기록을 삭제할까요?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("삭제", role: .destructive) {
                    Task {
                        if await viewModel.delete() {
                            onDeleted()
                            dismiss()
                        }
                    }
                }
                Button("취소", role: .cancel) {}
            }
            .disabled(viewModel.isSaving || viewModel.isDeleting)
        }
    }
}

#Preview {
    InsulinRecordFormView(onSaved: {})
}
