//
//  PhotoCaptureViewModel.swift
//  glucose
//
//  PhotosUI 타입은 일부러 안 쓴다 - 사진 선택/로드는 View(PhotoCaptureView)가 하고,
//  여기엔 UIImage만 넘어온다. (PhotosPickerItem을 여기서 직접 쓰면 "Cannot find type
//  'PhotosPickerItem' in scope" 빌드 에러가 나는 걸 실기기에서 확인함 — 원인 불명이라
//  PhotosUI 의존 자체를 View로 옮겨 피해감)
//

import Foundation
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

    func setError(_ message: String) {
        errorMessage = message
    }

    func reset() {
        previewImage = nil
        processedData = nil
        processedSizeDescription = nil
        errorMessage = nil
    }
}
