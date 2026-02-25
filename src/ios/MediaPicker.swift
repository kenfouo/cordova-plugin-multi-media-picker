import Foundation
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import AVFoundation

@objc(MediaPicker)
class MediaPicker: CDVPlugin, PHPickerViewControllerDelegate {

    private var commandCallbackId: String?

    private var selectionLimitOpt: Int = 3
    private var showLoaderOpt: Bool = true
    private var imageOnlyOpt: Bool = false
    private var mediaTypeOpt : String = "all"

    private weak var overlayView: UIView?
    private weak var overlaySpinner: UIActivityIndicatorView?

    // Helper pour nettoyer les IDs pour le système de fichiers
    private func getSafeId(_ identifier: String) -> String {
        return identifier.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
    }

    @objc(getMedias:)
    func getMedias(command: CDVInvokedUrlCommand) {
        self.commandCallbackId = command.callbackId

        if let opts = command.argument(at: 0) as? [String: Any] {
            if let limit = opts["selectionLimit"] as? Int { selectionLimitOpt = max(1, limit) }
            if let show = opts["showLoader"] as? Bool { showLoaderOpt = show }
            if let imageOnly = opts["imageOnly"] as? Bool { imageOnlyOpt = imageOnly }
            if let mediaType = opts["mediaType"] as? String { mediaTypeOpt = mediaType }
            else {
                mediaTypeOpt = imageOnlyOpt ? "images" : "all";
            }
        }

        guard let presentingVC = self.viewController else {
            let result = CDVPluginResult(status: .error, messageAs: "No presenting view controller")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        if presentingVC.presentedViewController is PHPickerViewController {
            let result = CDVPluginResult(status: .error, messageAs: "Picker is already presented")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        if #available(iOS 14, *) {
            // ✅ Ajout de photoLibrary: .shared() pour récupérer res.assetIdentifier
            var config = PHPickerConfiguration(photoLibrary: .shared())
            switch mediaTypeOpt {
            case "images": config.filter = .images
            case "videos" : config.filter = .videos
            default:  config.filter = .any(of: [.images, .videos])
            }

            config.selectionLimit = selectionLimitOpt

            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            presentingVC.present(picker, animated: true, completion: nil)
        } else {
            let result = CDVPluginResult(status: .error, messageAs: "iOS < 14 not supported")
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }

    private func showLoader(on view: UIView) {
        guard showLoaderOpt else { return }
        DispatchQueue.main.async {
            let overlay = UIView(frame: view.bounds)
            overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            overlay.backgroundColor = UIColor.black.withAlphaComponent(0.35)
            let spinner = UIActivityIndicatorView(style: .large)
            spinner.startAnimating()
            spinner.translatesAutoresizingMaskIntoConstraints = false
            overlay.addSubview(spinner)
            view.addSubview(overlay)
            NSLayoutConstraint.activate([
                spinner.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
                spinner.centerYAnchor.constraint(equalTo: overlay.centerYAnchor)
            ])
            self.overlayView = overlay
            self.overlaySpinner = spinner
        }
    }

    private func hideLoader() {
        DispatchQueue.main.async {
            self.overlaySpinner?.stopAnimating()
            self.overlayView?.removeFromSuperview()
        }
    }
    
    @available(iOS 14.0, *)
    private func resolveMime(provider: NSItemProvider, dest: URL) -> String {
        var candidates: [String] = []
        if #available(iOS 16.0, *) {
            candidates.append(contentsOf: provider.registeredContentTypes.compactMap { $0.preferredMIMEType })
        }
        candidates.append(contentsOf: provider.registeredTypeIdentifiers.compactMap { UTType($0)?.preferredMIMEType })
        do {
            let values = try dest.resourceValues(forKeys: [.typeIdentifierKey])
            if let uti = values.typeIdentifier,
            let ut = UTType(uti),
            let mime = ut.preferredMIMEType {
                candidates.append(mime)
            }
        } catch {
            // Si erreur, tu peux ignorer ou logger
        }

        if candidates.contains("image/jpeg") { return "image/jpeg" }
        if candidates.contains("video/mp4") { return "video/mp4" }
        return candidates.first ?? "application/octet-stream"
    }

    private func convertHEICToJPG(sourceURL: URL, index: String) throws -> URL {
        guard let image = UIImage(contentsOfFile: sourceURL.path) else {
            throw NSError(domain: "MediaPicker", code: -1, userInfo: [
                NSLocalizedDescriptionKey: "Impossible de charger l'image HEIC"
            ])
        }

        guard let jpegData = image.jpegData(compressionQuality: 0.9) else {
            throw NSError(domain: "MediaPicker", code: -2, userInfo: [
                NSLocalizedDescriptionKey: "Impossible de convertir en JPEG"
            ])
        }

        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(index).jpg")

        try jpegData.write(to: dest)

        return dest
    }

    @available(iOS 14, *)
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        if results.isEmpty {
            picker.dismiss(animated: true) {
                let result = CDVPluginResult(status: .ok, messageAs: [])
                self.commandDelegate.send(result, callbackId: self.commandCallbackId)
            }
            return
        }

        self.showLoader(on: picker.view)
        let group = DispatchGroup()
        let lock = NSLock()
        var medias: [[String: Any]] = []
        var errorMessages: [String] = []

        func addError(_ message: String) {
            lock.lock()
            errorMessages.append(message)
            lock.unlock()
        }

        func normalizeFileURL(_ url: URL) -> String {
            return url.scheme?.lowercased() == "file" ? url.absoluteString : "file://\(url.path)"
        }

        for (tapIndex, res) in results.enumerated() {
            let provider = res.itemProvider
            let isVideo = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            let isImage = provider.hasItemConformingToTypeIdentifier(UTType.image.identifier)
            
            // ✅ Récupération de l'ID
            let assetId = res.assetIdentifier ?? UUID().uuidString
            let safeId = getSafeId(assetId)

            let typeIdentifier = isVideo ? UTType.movie.identifier : (isImage ? UTType.image.identifier : (provider.registeredTypeIdentifiers.first ?? UTType.data.identifier))

            group.enter()
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { (sourceURL, error) in
                defer { group.leave() }

                if let error = error {
                    addError("Item \(tapIndex) load error: \(error.localizedDescription)")
                    return
                }
                guard let sourceURL = sourceURL else { return }
                var ext = sourceURL.pathExtension.lowercased()
                let isHEIC =
    provider.hasItemConformingToTypeIdentifier(UTType.heic.identifier) ||
    provider.hasItemConformingToTypeIdentifier(UTType.heif.identifier) ||
    ext == "heic" ||
    ext == "heif"

                let fm = FileManager.default
                var finalURL: URL

                do {
                    // HEIC → JPG
                    if isImage && isHEIC {
                        finalURL = try self.convertHEICToJPG(sourceURL: sourceURL, index: safeId)
                    } else {
                        ext = sourceURL.pathExtension.isEmpty ? (isVideo ? "mov" : "jpg") : sourceURL.pathExtension
                        let dest = fm.temporaryDirectory.appendingPathComponent("\(safeId).\(ext)")
                        if !fm.fileExists(atPath: dest.path) {
                            try fm.copyItem(at: sourceURL, to: dest)
                        }
                        finalURL = dest
                    }

                    // Ensuite ton code de construction du dictionnaire info
                    let normalized = normalizeFileURL(finalURL)
                    var info: [String: Any] = [
                        "id": assetId,
                        "index": tapIndex,
                        "uri": normalized,
                        "fileName": finalURL.lastPathComponent,
                        "fileSize": (try? fm.attributesOfItem(atPath: finalURL.path)[.size] as? Int) ?? 0
                    ]
                    if isImage && isHEIC { info["mimeType"] = "image/jpeg" }
                    else { info["mimeType"] = self.resolveMime(provider: provider, dest: finalURL) }

                    // Image / vidéo
                    if isImage, let img = UIImage(contentsOfFile: finalURL.path) {
                        info["type"] = "image"
                        info["width"] = Int(img.size.width)
                        info["height"] = Int(img.size.height)
                    } else if isVideo {
                        info["type"] = "video"
                        let asset = AVAsset(url: finalURL)
                        info["duration"] = CMTimeGetSeconds(asset.duration)
                        if let track = asset.tracks(withMediaType: .video).first {
                            let size = track.naturalSize.applying(track.preferredTransform)
                            info["width"] = Int(abs(size.width))
                            info["height"] = Int(abs(size.height))
                        }
                    } else {
                        info["type"] = "other"
                    }

                    lock.lock()
                    medias.append(info)
                    lock.unlock()
                } catch {
                    addError("Item \(tapIndex) copy error: \(error.localizedDescription)")
                }
            }
        }

        group.notify(queue: .main) {
            self.hideLoader()
            picker.dismiss(animated: true) {
                if !errorMessages.isEmpty {
                    self.commandDelegate.send(CDVPluginResult(status: .error, messageAs: errorMessages.joined(separator: "\n")), callbackId: self.commandCallbackId)
                    return
                }
                let sorted = medias.sorted { ($0["index"] as? Int ?? 0) < ($1["index"] as? Int ?? 0) }
                self.commandDelegate.send(CDVPluginResult(status: .ok, messageAs: sorted), callbackId: self.commandCallbackId)
            }
        }
    }

    @objc(getExifForKey:)
    func getExifForKey(command: CDVInvokedUrlCommand) {
        guard let path = command.argument(at: 0) as? String else {
            self.commandDelegate.send(CDVPluginResult(status: .error, messageAs: "Path is missing"), callbackId: command.callbackId)
            return
        }
        guard let key = command.argument(at: 1) as? String, !key.isEmpty else {
            self.commandDelegate.send(CDVPluginResult(status: .ok, messageAs: "Exif key is required"), callbackId: command.callbackId)
            return
        }
        self.commandDelegate.run {
            let cleanPath = path.replacingOccurrences(of: "file://", with: "")
            let fileURL = URL(fileURLWithPath: cleanPath)
            let isVideo = ["mp4", "mov", "m4v", "3gp"].contains(fileURL.pathExtension.lowercased())
            var foundValue: Any? = nil
            if isVideo {
                let asset = AVAsset(url: fileURL)
                foundValue = asset.commonMetadata.first(where: { $0.commonKey?.rawValue == key })?.value
            } else {
                if let imageSource = CGImageSourceCreateWithURL(fileURL as CFURL, nil),
                let props = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any] {
                    if let val = props[key] { foundValue = val }
                    else if let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any], let val = exif[key] { foundValue = val }
                    else if let tiff = props[kCGImagePropertyTIFFDictionary as String] as? [String: Any], let val = tiff[key] { foundValue = val }
                    else if let gps = props[kCGImagePropertyGPSDictionary as String] as? [String: Any], let val = gps[key] { foundValue = val }
                }
            }
            let res = foundValue != nil ? CDVPluginResult(status: .ok, messageAs: "\(foundValue!)") : CDVPluginResult(status: .ok, messageAs: [:])
            self.commandDelegate.send(res, callbackId: command.callbackId)
        }
    }

    @objc(getLastMedias:)
    func getLastMedias(command: CDVInvokedUrlCommand) {
        self.commandDelegate.run {
            let opts = command.argument(at: 0) as? [String: Any] ?? [:]
            let limit = opts["limit"] as? Int ?? 20
            let offset = opts["offset"] as? Int ?? 0
            let mediaType = opts["mediaType"] as? String ?? "all"
            let status = PHPhotoLibrary.authorizationStatus()
            if status == .authorized || status == .limited {
                self.fetchMediasWithPagination(command: command, limit: limit, offset: offset, mediaType: mediaType)
            } else {
                PHPhotoLibrary.requestAuthorization { newStatus in
                    if newStatus == .authorized || newStatus == .limited {
                        self.fetchMediasWithPagination(command: command, limit: limit, offset: offset, mediaType: mediaType)
                    } else {
                        self.commandDelegate.send(CDVPluginResult(status: .error, messageAs: "Permission denied"), callbackId: command.callbackId)
                    }
                }
            }
        }
    }

    private func fetchMediasWithPagination(command: CDVInvokedUrlCommand, limit: Int, offset: Int, mediaType: String) {
        var assetsList: [[String: Any]] = []
        let fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let allAssets: PHFetchResult<PHAsset>
        if mediaType == "images" { allAssets = PHAsset.fetchAssets(with: .image, options: fetchOptions) }
        else if mediaType == "videos" { allAssets = PHAsset.fetchAssets(with: .video, options: fetchOptions) }
        else { allAssets = PHAsset.fetchAssets(with: fetchOptions) }

        let totalCount = allAssets.count
        let endIndex = min(offset + limit, totalCount)
        if offset >= totalCount {
            self.commandDelegate.send(CDVPluginResult(status: .ok, messageAs: []), callbackId: command.callbackId)
            return
        }

        let manager = PHImageManager.default()
        let fm = FileManager.default
        let tempDir = fm.temporaryDirectory
        let semaphore = DispatchSemaphore(value: 0)

        for i in offset..<endIndex {
            let asset = allAssets.object(at: i)
            let assetId = asset.localIdentifier
            let safeId = getSafeId(assetId)

            var mediaInfo: [String: Any] = [
                "id": assetId, // ✅ Ajouté
                "index": assetId,
                "width": asset.pixelWidth,
                "height": asset.pixelHeight,
                "creationDate": asset.creationDate?.description ?? "",
                "type": asset.mediaType == .video ? "video" : "image"
            ]

            if asset.mediaType == .video {
                mediaInfo["duration"] = asset.duration
                let thumbName = "thumb_\(safeId).jpg"
                let thumbURL = tempDir.appendingPathComponent(thumbName)
                
                if !fm.fileExists(atPath: thumbURL.path) {
                    let thumbOptions = PHImageRequestOptions(); thumbOptions.isSynchronous = true
                    manager.requestImage(for: asset, targetSize: CGSize(width: 300, height: 300), contentMode: .aspectFill, options: thumbOptions) { (image, _) in
                        if let data = image?.jpegData(compressionQuality: 0.8) { try? data.write(to: thumbURL) }
                    }
                }
                mediaInfo["thumbnail"] = "file://\(thumbURL.path)"

                let destURL = tempDir.appendingPathComponent("\(safeId).mov")
                if fm.fileExists(atPath: destURL.path) {
                    mediaInfo["uri"] = "file://\(destURL.path)"
                    semaphore.signal()
                } else {
                    let vOpts = PHVideoRequestOptions(); vOpts.isNetworkAccessAllowed = true
                    manager.requestAVAsset(forVideo: asset, options: vOpts) { (avAsset, _, _) in
                        if let urlAsset = avAsset as? AVURLAsset { try? fm.copyItem(at: urlAsset.url, to: destURL) }
                        mediaInfo["uri"] = "file://\(destURL.path)"
                        semaphore.signal()
                    }
                }
            } else {
                let destURL = tempDir.appendingPathComponent("\(safeId).jpg")
                if fm.fileExists(atPath: destURL.path) {
                    mediaInfo["uri"] = "file://\(destURL.path)"
                    semaphore.signal()
                } else {
                    let iOpts = PHImageRequestOptions(); iOpts.isNetworkAccessAllowed = true
                    manager.requestImageDataAndOrientation(for: asset, options: iOpts) { (data, _, _, _) in
                        if let data = data { try? data.write(to: destURL) }
                        mediaInfo["uri"] = "file://\(destURL.path)"
                        semaphore.signal()
                    }
                }
            }
            semaphore.wait()
            assetsList.append(mediaInfo)
        }
        self.commandDelegate.send(CDVPluginResult(status: .ok, messageAs: assetsList), callbackId: command.callbackId)
    }
}
