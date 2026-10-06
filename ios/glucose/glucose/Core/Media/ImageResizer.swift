//
//  ImageResizer.swift
//  glucose
//
//  업로드 전 해상도·용량을 줄인다. 계약상 이미지는 JPEG/PNG 8MB 이하여야 하고,
//  ml-service가 어차피 긴 변 768px로 다시 줄이므로 1600px 안팎이면 충분하다
//  (docs/ml-service-contract.md 참고).
//

import UIKit

enum ImageResizer {

    static let maxDimension: CGFloat = 1600
    static let maxBytes = 8 * 1024 * 1024

    /// 리사이즈 + JPEG 압축까지 끝낸 업로드용 데이터를 돌려준다.
    static func prepareForUpload(_ image: UIImage) -> Data? {
        let resized = resize(image, maxDimension: maxDimension)
        return compress(resized, maxBytes: maxBytes)
    }

    private static func resize(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let longerSide = max(image.size.width, image.size.height)
        guard longerSide > maxDimension else { return image }

        let scale = maxDimension / longerSide
        let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }

    private static func compress(_ image: UIImage, maxBytes: Int) -> Data? {
        var quality: CGFloat = 0.8
        var data = image.jpegData(compressionQuality: quality)

        while let currentData = data, currentData.count > maxBytes, quality > 0.1 {
            quality -= 0.1
            data = image.jpegData(compressionQuality: quality)
        }
        return data
    }
}
