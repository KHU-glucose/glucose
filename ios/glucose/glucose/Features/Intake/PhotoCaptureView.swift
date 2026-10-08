//
//  PhotoCaptureView.swift
//  glucose
//

import PhotosUI
import SwiftUI

struct PhotoCaptureView: View {
    @State private var viewModel = PhotoCaptureViewModel()
    @State private var uploadViewModel = PhotoUploadViewModel()
    @State private var showCamera = false
    @State private var photosPickerItem: PhotosPickerItem?
    @State private var showRecognitionResult = false
    @Environment(\.dismiss) private var dismiss

    private var isCameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("상황") {
                    Picker("상황", selection: $viewModel.context) {
                        ForEach(IntakeContext.allCases, id: \.self) { context in
                            Label(context.displayName, systemImage: context.systemImage).tag(context)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("사진") {
                    if let previewImage = viewModel.previewImage {
                        Image(uiImage: previewImage)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 240)
                            .frame(maxWidth: .infinity)
                    }

                    if isCameraAvailable {
                        Button {
                            showCamera = true
                        } label: {
                            Label("카메라로 촬영", systemImage: "camera")
                        }
                    }

                    PhotosPicker(selection: $photosPickerItem, matching: .images) {
                        Label("앨범에서 선택", systemImage: "photo.on.rectangle")
                    }
                }

                if let sizeDescription = viewModel.processedSizeDescription {
                    Section {
                        Label("업로드 준비 완료 · \(sizeDescription)", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                }

                if let errorMessage = viewModel.errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }

                if viewModel.isReadyForUpload {
                    Section {
                        uploadSection
                    }
                }
            }
            .navigationTitle("사진 기록")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraCaptureView { image in
                    showCamera = false
                    viewModel.process(image)
                }
                .ignoresSafeArea()
            }
            .onChange(of: photosPickerItem) { _, newItem in
                Task { await loadPickedImage(newItem) }
            }
            .onChange(of: uploadViewModel.state) { _, newState in
                if newState == .done {
                    showRecognitionResult = true
                }
            }
            .navigationDestination(isPresented: $showRecognitionResult) {
                if let photoId = uploadViewModel.photoId {
                    RecognitionResultView(photoId: photoId, context: viewModel.context) {
                        dismiss()
                    }
                }
            }
            .interactiveDismissDisabled(isUploadInFlight)
        }
    }

    private var isUploadInFlight: Bool {
        switch uploadViewModel.state {
        case .starting, .uploading, .completing: true
        default: false
        }
    }

    @ViewBuilder
    private var uploadSection: some View {
        switch uploadViewModel.state {
        case .idle, .failed:
            if case .failed(let message) = uploadViewModel.state {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
            Button {
                startUpload()
            } label: {
                Label(uploadRetryLabel, systemImage: "icloud.and.arrow.up")
            }
        case .starting:
            Label("업로드 준비 중...", systemImage: "icloud.and.arrow.up")
        case .uploading(let progress):
            VStack(alignment: .leading, spacing: 6) {
                Label("업로드 중...", systemImage: "icloud.and.arrow.up")
                ProgressView(value: progress)
            }
        case .completing:
            Label("마무리하는 중...", systemImage: "checkmark.icloud")
        case .done:
            Label("업로드 완료 · 인식 결과로 이동", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        }
    }

    private var uploadRetryLabel: String {
        if case .failed = uploadViewModel.state {
            return "다시 시도"
        }
        return "업로드"
    }

    private func loadPickedImage(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        do {
            guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                viewModel.setError("사진을 불러오지 못했습니다.")
                return
            }
            viewModel.process(image)
        } catch {
            viewModel.setError("사진을 불러오지 못했습니다.")
        }
    }

    private func startUpload() {
        guard let data = viewModel.processedData else { return }
        let context = viewModel.context
        Task { await uploadViewModel.upload(imageData: data, context: context) }
    }
}

#Preview {
    PhotoCaptureView()
}
