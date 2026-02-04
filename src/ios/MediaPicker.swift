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
    private var mediaTypeOpt : String = "all" // ✅ Ajouté

    private weak var overlayView: UIView?
    private weak var overlaySpinner: UIActivityIndicatorView?

    @objc(getMedias:)
    func getMedias(command: CDVInvokedUrlCommand) {
        self.commandCallbackId = command.callbackId

        if let opts = command.argument(at: 0) as? [String: Any] {
            if let limit = opts["selectionLimit"] as? Int { selectionLimitOpt = max(1, limit) }
            if let show = opts["showLoader"] as? Bool { showLoaderOpt = show }
            if let imageOnly = opts["imageOnly"] as? Bool { imageOnlyOpt = imageOnly }
            if let mediaType = opts["mediaType"] as? String { mediaTypeOpt = mediaType }
            else {
                // compatibility fallback for older versions
                mediaTypeOpt = imageOnlyOpt ? "images" : "all";
            }
        }

        guard let presentingVC = self.viewController else {
            let result = CDVPluginResult(status: .error, messageAs: "No presenting view controller")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        // ✅ Prevent multiple picker presentations
        if presentingVC.presentedViewController is PHPickerViewController {
            let result = CDVPluginResult(status: .error, messageAs: "Picker is already presented")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }

        if #available(iOS 14, *) {
            var config = PHPickerConfiguration()
            switch mediaTypeOpt {
            case "images": config.filter = .images
            break;

            case "videos" : config.filter = .videos
            break;

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

        // iOS 16+: use modern ContentTypes first
        if #available(iOS 16.0, *) {
            candidates.append(contentsOf: provider.registeredContentTypes.compactMap { $0.preferredMIMEType })
        }

        // Always fallback to legacy identifiers
        candidates.append(contentsOf: provider.registeredTypeIdentifiers.compactMap { UTType($0)?.preferredMIMEType })

        // Add dest file UTI if available
        if let uti = try? dest.resourceValues(forKeys: [.typeIdentifierKey]).typeIdentifier,
           let ut = UTType(uti),
           let mime = ut.preferredMIMEType {
            candidates.append(mime)
        }

        // Preferences
        if candidates.contains("image/jpeg") {
            return "image/jpeg"
        }
        if candidates.contains("video/mp4") {
            return "video/mp4"
        }

        return candidates.first ?? "application/octet-stream"
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
            if url.scheme?.lowercased() == "file" {
                return url.absoluteString
            } else {
                return "file://\(url.path)"
            }
        }

        for (tapIndex, res) in results.enumerated() {
            let provider = res.itemProvider
            let isVideo = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            let isImage = provider.hasItemConformingToTypeIdentifier(UTType.image.identifier)

            let typeIdentifier: String
            if isVideo {
                typeIdentifier = UTType.movie.identifier
            } else if isImage {
                typeIdentifier = UTType.image.identifier
            } else {
                typeIdentifier = provider.registeredTypeIdentifiers.first ?? UTType.data.identifier
            }

            group.enter()
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { (sourceURL, error) in
                defer { group.leave() }

                if let error = error {
                    addError("Item \(tapIndex) load error: \(error.localizedDescription)")
                    return
                }
                guard let sourceURL = sourceURL else {
                    addError("Item \(tapIndex) no sourceURL")
                    return
                }

                let fm = FileManager.default
                let ext = sourceURL.pathExtension.isEmpty
                    ? (isVideo ? "mov" : "jpeg")
                    : sourceURL.pathExtension

                let dest = fm.temporaryDirectory.appendingPathComponent("\(UUID().uuidString)_\(tapIndex).\(ext)")

                do {
                    if fm.fileExists(atPath: dest.path) {
                        try fm.removeItem(at: dest)
                    }
                    try fm.copyItem(at: sourceURL, to: dest)
                    let normalized = normalizeFileURL(dest)
                    var info: [String: Any] = [
                        "index": tapIndex,
                        "uri": normalized,
                        "fileName": dest.lastPathComponent,
                        "fileSize": (try? fm.attributesOfItem(atPath: dest.path)[.size] as? Int) ?? 0
                    ]
                    info["mimeType"] = self.resolveMime(provider: provider, dest: dest)
                    if isImage {
                        info["type"] = "image"
                        if let img = UIImage(contentsOfFile: dest.path) {
                            info["width"] = Int(img.size.width)
                            info["height"] = Int(img.size.height)
                        }
                    } else if isVideo {
                        info["type"] = "video"
                        let asset = AVAsset(url: dest)
                        let duration = CMTimeGetSeconds(asset.duration)
                        info["duration"] = duration

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
                    let result = CDVPluginResult(status: .error, messageAs: errorMessages.joined(separator: "\n"))
                    self.commandDelegate.send(result, callbackId: self.commandCallbackId)
                    return
                }

                let sorted = medias.sorted { ($0["index"] as? Int ?? 0) < ($1["index"] as? Int ?? 0) }

                let result = CDVPluginResult(status: .ok, messageAs: sorted)
                self.commandDelegate.send(result, callbackId: self.commandCallbackId)
            }
        }
    }

    @objc(getExifForKey:)
    func getExifForKey(command: CDVInvokedUrlCommand) {
        guard let path = command.argument(at: 0) as? String else {
            let result = CDVPluginResult(status: .error, messageAs: "Path is missing")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        // Si la clé est absente, vide ou NSNull, on renvoie un JSON vide directement
        guard let key = command.argument(at: 1) as? String, !key.isEmpty else {
            let result = CDVPluginResult(status: .ok, messageAs: "Exif key is required")
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        self.commandDelegate.run {
            let cleanPath = path.replacingOccurrences(of: "file://", with: "")
            let fileURL = URL(fileURLWithPath: cleanPath)
            let isVideo = ["mp4", "mov", "m4v", "3gp"].contains(fileURL.pathExtension.lowercased())
            
            var foundValue: Any? = nil
            
            if isVideo {
                // Recherche ciblée dans les métadonnées vidéo
                let asset = AVAsset(url: fileURL)
                foundValue = asset.commonMetadata.first(where: { $0.commonKey?.rawValue == key })?.value
            } else {
                // Recherche ciblée dans les métadonnées image
                if let imageSource = CGImageSourceCreateWithURL(fileURL as CFURL, nil),
                let props = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any] {
                    
                    if let val = props[key] {
                        foundValue = val
                    } else if let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any], let val = exif[key] {
                        foundValue = val
                    } else if let tiff = props[kCGImagePropertyTIFFDictionary as String] as? [String: Any], let val = tiff[key] {
                        foundValue = val
                    } else if let gps = props[kCGImagePropertyGPSDictionary as String] as? [String: Any], let val = gps[key] {
                        foundValue = val
                    }
                }
            }
            
            // Construction du résultat (Valeur seule ou {} si rien trouvé)
            let pluginResult: CDVPluginResult
            if let data = foundValue {
                // On renvoie la valeur dans son type natif (String, Int, etc.)
                pluginResult = CDVPluginResult(status: .ok, messageAs: "\(data)")
            } else {
                pluginResult = CDVPluginResult(status: .ok, messageAs: [:])
            }
            
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
        }
    }

    @objc(getLastMedias:)
    func getLastMedias(command: CDVInvokedUrlCommand) {
        self.commandDelegate.run {
            let opts = command.argument(at: 0) as? [String: Any] ?? [:]
            let limit = opts["limit"] as? Int ?? 20
            let offset = opts["offset"] as? Int ?? 0
            let mediaType = opts["mediaType"] as? String ?? "all" // "images", "videos", "all"

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
        
        // ✅ Filtrage selon le mediaType
        let allAssets: PHFetchResult<PHAsset>
        if mediaType == "images" {
            allAssets = PHAsset.fetchAssets(with: .image, options: fetchOptions)
        } else if mediaType == "videos" {
            allAssets = PHAsset.fetchAssets(with: .video, options: fetchOptions)
        } else {
            allAssets = PHAsset.fetchAssets(with: fetchOptions)
        }

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
            var mediaInfo: [String: Any] = [
                "index": asset.localIdentifier,
                "width": asset.pixelWidth,
                "height": asset.pixelHeight,
                "creationDate": asset.creationDate?.description ?? "",
                "type": asset.mediaType == .video ? "video" : "image"
            ]

            if asset.mediaType == .video {
                mediaInfo["duration"] = asset.duration
                let videoOptions = PHVideoRequestOptions()
                videoOptions.isNetworkAccessAllowed = true
                
                // --- Génération de la miniature ---
                let thumbOptions = PHImageRequestOptions()
                thumbOptions.isSynchronous = true // On peut rester synchrone ici car c'est rapide pour une miniature
                thumbOptions.deliveryMode = .highQualityFormat
                
                // On demande une taille raisonnable pour la grille (ex: 300x300)
                manager.requestImage(for: asset, targetSize: CGSize(width: 300, height: 300), contentMode: .aspectFill, options: thumbOptions) { (image, info) in
                    if let image = image, let data = image.jpegData(compressionQuality: 0.8) {
                        let thumbName = "thumb_\(UUID().uuidString).jpg"
                        let thumbURL = tempDir.appendingPathComponent(thumbName)
                        try? data.write(to: thumbURL)
                        mediaInfo["thumbnail"] = "file://\(thumbURL.path)"
                    }
                }
                
                manager.requestAVAsset(forVideo: asset, options: videoOptions) { (avAsset, audioMix, info) in
                    if let urlAsset = avAsset as? AVURLAsset {
                        let ext = urlAsset.url.pathExtension
                        let destURL = tempDir.appendingPathComponent("\(UUID().uuidString).\(ext)")
                        try? fm.copyItem(at: urlAsset.url, to: destURL)
                        mediaInfo["uri"] = "file://\(destURL.path)"
                    }
                    semaphore.signal()
                }
            } else {
                let imgOptions = PHImageRequestOptions()
                imgOptions.isSynchronous = false
                imgOptions.isNetworkAccessAllowed = true
                
                manager.requestImageDataAndOrientation(for: asset, options: imgOptions) { (data, uti, orientation, info) in
                    if let data = data {
                        let ext = UTType(uti ?? "")?.preferredFilenameExtension ?? "jpg"
                        let destURL = tempDir.appendingPathComponent("\(UUID().uuidString).\(ext)")
                        try? data.write(to: destURL)
                        mediaInfo["uri"] = "file://\(destURL.path)"
                    }
                    semaphore.signal()
                }
            }
            
            semaphore.wait()
            assetsList.append(mediaInfo)
        }

        let result = CDVPluginResult(status: .ok, messageAs: assetsList)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }


    async function loadLastMedia(type, count) {
    const medias = await MediaPicker.getLastMedias(type, count);
    console.log('medias1 : '+JSON.stringify(medias, null, 2));


/* 
    private func fetchPhotosFromLibrary(command: CDVInvokedUrlCommand) {
        var assetsList: [[String: Any]] = []
        
        // Options de tri : on prend les images, triées par date de création (plus récent en premier)
        let fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        
        let allPhotos = PHAsset.fetchAssets(with: .image, options: fetchOptions)
        
        let manager = PHImageManager.default()
        let requestOptions = PHImageRequestOptions()
        requestOptions.isSynchronous = true // On le garde synchrone pour la boucle simple, mais attention si la galerie est énorme
        requestOptions.deliveryMode = .fastFormat

        allPhotos.enumerateObjects { (asset, index, stop) in
            var photoInfo: [String: Any] = [
                "localIdentifier": asset.localIdentifier,
                "width": asset.pixelWidth,
                "height": asset.pixelHeight,
                "creationDate": asset.creationDate?.description ?? ""
            ]
            
            // Note : Pour obtenir l'URL du fichier réel ou l'image en base64, 
            // il faudra faire un appel supplémentaire à requestImageDataAndOrientation
            assetsList.append(photoInfo)
        }

        let result = CDVPluginResult(status: .ok, messageAs: assetsList)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    } */
}
