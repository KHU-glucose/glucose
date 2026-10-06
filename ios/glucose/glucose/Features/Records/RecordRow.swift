//
//  RecordRow.swift
//  glucose
//

import SwiftUI

struct RecordRow: View {
    let item: RecordItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(.tint)
                .frame(width: 28)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var systemImage: String {
        switch item {
        case .intake(let record): record.context.systemImage
        case .insulin: "syringe"
        }
    }

    private var title: String {
        switch item {
        case .intake(let record): record.summary.isEmpty ? record.context.displayName : record.summary
        case .insulin(let record): "인슐린 \(record.units.formattedUnits)단위"
        }
    }

    private var subtitle: String {
        let time = item.occurredAt.formatted(date: .omitted, time: .shortened)
        switch item {
        case .intake(let record): "\(record.context.displayName) · \(time)"
        case .insulin(let record): "\(record.kind) · \(time)"
        }
    }
}

private extension Double {
    var formattedUnits: String {
        truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0f", self)
            : String(format: "%.1f", self)
    }
}

#Preview {
    List {
        RecordRow(item: .intake(IntakeRecord(
            id: UUID(), occurredAt: .now, context: .meal,
            items: [FoodItem(id: UUID(), name: "현미밥", count: 1, unit: "공기")]
        )))
        RecordRow(item: .insulin(InsulinRecord(id: UUID(), occurredAt: .now, units: 6, kind: "식사")))
    }
}
