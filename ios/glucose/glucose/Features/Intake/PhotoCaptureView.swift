//
//  PhotoCaptureView.swift
//  glucose
//

import PhotosUI
import SwiftUI

struct PhotoCaptureView: View {
    @State private var viewModel = PhotoCaptureViewModel()
    @State private var showCamera = false
    @State private var photosPickerItem: PhotosPickerItem?
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
                Task { await viewModel.loadFromPicker(newItem) }
            }
        }
    }
}

#Preview {
    PhotoCaptureView()
}
