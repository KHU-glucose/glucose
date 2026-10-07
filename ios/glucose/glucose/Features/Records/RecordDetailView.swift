//
//  RecordDetailView.swift
//  glucose
//
//  사실만 보여준다: 무엇을 먹었는지, 인슐린을 얼마나 맞았는지. 용량 조언은 하지 않는다.
//

import SwiftUI

struct RecordDetailView: View {
    let item: RecordItem
    var onChanged: () async -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @State private var showEditInsulin = false

    var body: some View {
        List {
            Section("시각") {
                Text(item.occurredAt.formatted(date: .abbreviated, time: .shortened))
            }

            switch item {
            case .intake(let record):
                Section(record.context.displayName) {
                    ForEach(record.items) { food in
                        HStack {
                            Text(food.name)
                            Spacer()
                            if let quantity = food.displayQuantity {
                                Text(quantity)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            case .insulin(let record):
                Section("인슐린") {
                    LabeledContent("단위", value: record.units.formatted())
                    LabeledContent("종류", value: record.kind)
                }
            }
        }
        .navigationTitle(navigationTitle)
        .toolbar {
            if case .insulin = item {
                ToolbarItem(placement: .primaryAction) {
                    Button("수정") { showEditInsulin = true }
                }
            }
        }
        .sheet(isPresented: $showEditInsulin) {
            if case .insulin(let record) = item {
                InsulinRecordFormView(
                    mode: .edit(id: record.id),
                    occurredAt: record.occurredAt,
                    units: record.units,
                    kind: record.kind,
                    onSaved: { Task { await onChanged(); dismiss() } },
                    onDeleted: { Task { await onChanged(); dismiss() } }
                )
            }
        }
    }

    private var navigationTitle: String {
        switch item {
        case .intake(let record): record.context.displayName
        case .insulin: "인슐린"
        }
    }
}

#Preview {
    NavigationStack {
        RecordDetailView(item: .intake(IntakeRecord(
            id: UUID(), occurredAt: .now, context: .meal,
            items: [FoodItem(id: UUID(), name: "현미밥", count: 1, unit: "공기")]
        )))
    }
}
