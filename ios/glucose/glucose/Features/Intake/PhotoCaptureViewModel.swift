//
//  PhotoCaptureViewModel.swift
//  glucose
//

import Foundation
import PhotosUI
import UIKit

@Observable
final class PhotoCaptureViewModel {

    var context: IntakeContext = .meal

    private(set) var previewImage: UIImage?
    private(set) var processedData: Data?
    private(set) var processedSizeDescription: String?
    private(set) var errorMessage: String?

    var isReadyForUpload: Bool { processedData != nil }

    func process(_ image: UIImage) {
        errorMessage = nil
        previewImage = image

        guard let data = ImageResizer.prepareForUpload(image) else {
            errorMessage = "이미지를 처리하지 못했습니다. 다른 사진으로 시도해주세요."
            processedData = nil
            processedSizeDescription = nil
            return
        }

        processedData = data
        processedSizeDescription = ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file)
    }

    func loadFromPicker(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        do {
            guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                errorMessage = "사진을 불러오지 못했습니다."
                return
            }
            process(image)
        } catch {
            errorMessage = "사진을 불러오지 못했습니다."
        }
    }

    func reset() {
        previewImage = nil
        processedData = nil
        processedSizeDescription = nil
        errorMessage = nil
    }
}
