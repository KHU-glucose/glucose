//
//  GraphUploadView.swift
//  glucose
//
//  리브레 앱에서 공유·저장한 일일 그래프 이미지 1장 = 하루치 혈당. 앨범에서만 고른다
//  (화면을 카메라로 다시 찍으면 격자·눈금 인식이 잘 안 된다).
//

import PhotosUI
import SwiftUI

struct GraphUploadView: View {
    @State private var viewModel = GraphUploadViewModel()
    @State private var pickerItem: PhotosPickerItem?
    @State private var previewImage: UIImage?
    @State private var prepared: PreparedGraphImage?
    @State private var prepareError: String?
    @Environment(\.dismiss) private var dismiss

    private let onUploaded: (String) -> Void

    init(onUploaded: @escaping (String) -> Void = { _ in }) {
        self.onUploaded = onUploaded
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("리브레 앱 → 일일 그래프 → 공유 → 이미지 저장으로 저장한 그래프를 골라주세요. 하루에 한 장이면 돼요.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("그래프 이미지") {
                    if let previewImage {
                        Image(uiImage: previewImage)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 280)
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel("선택한 그래프 이미지")
                    }

                    PhotosPicker(selection: $pickerItem, matching: .images) {
                        Label(previewImage == nil ? "앨범에서 선택" : "다른 이미지 선택", systemImage: "photo.on.rectangle")
                    }
                    .disabled(viewModel.isBusy)
                }

                if let prepareError {
                    Section {
                        Text(prepareError)
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }

                if prepared != nil {
                    Section {
                        statusSection
                    }
                }
            }
            .navigationTitle("혈당 그래프 올리기")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(isDone ? "닫기" : "취소") {
                        viewModel.cancel()
                        dismiss()
                    }
                }
            }
            .onChange(of: pickerItem) { _, newItem in
                Task { await loadPickedImage(newItem) }
            }
            .onChange(of: viewModel.state) { _, newState in
                if case .done(let date, _) = newState {
                    onUploaded(date)
                }
            }
            .interactiveDismissDisabled(viewModel.state == .uploading)
        }
    }

    private var isDone: Bool {
        if case .done = viewModel.state { return true }
        return false
    }

    @ViewBuilder
    private var statusSection: some View {
        switch viewModel.state {
        case .idle:
            Button {
                startUpload()
            } label: {
                Label("업로드", systemImage: "icloud.and.arrow.up")
            }
        case .uploading:
            HStack(spacing: 12) {
                ProgressView()
                Text("업로드 중...")
            }
        case .analyzing:
            HStack(spacing: 12) {
                ProgressView()
                Text("그래프를 분석하는 중...")
            }
        case .done(let date, let coverageRatio):
            VStack(alignment: .leading, spacing: 6) {
                Label("\(ReportDay.displayString(fromAPIString: date)) 혈당 기록이 반영됐어요", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Text("그래프에서 읽은 구간: 하루의 \(Int((coverageRatio * 100).rounded()))%")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("같은 날짜의 그래프를 다시 올리면 새 그래프로 바뀌어요.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        case .failed(let message):
            Text(message)
                .font(.footnote)
                .foregroundStyle(.red)
            Button {
                startUpload()
            } label: {
                Label("다시 시도", systemImage: "arrow.clockwise")
            }
        }
    }

    private func loadPickedImage(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        viewModel.reset()
        prepareError = nil
        prepared = nil
        previewImage = nil

        guard let data = try? await item.loadTransferable(type: Data.self),
              let result = ImageResizer.prepareGraphForUpload(originalData: data) else {
            prepareError = "이미지를 불러오지 못했습니다. 다른 이미지를 선택해주세요."
            return
        }
        previewImage = UIImage(data: data)
        prepared = PreparedGraphImage(data: result.data, contentType: result.contentType)
    }

    private func startUpload() {
        guard let prepared else { return }
        viewModel.start(imageData: prepared.data, contentType: prepared.contentType)
    }
}

private struct PreparedGraphImage {
    let data: Data
    let contentType: String
}

#Preview {
    GraphUploadView()
}
