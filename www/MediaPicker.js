function MediaPicker() { }

/**
 * Get medias with options
 * @param {Object} opts
 * @param {number} opts.selectionLimit - max number of medias
 * @param {boolean} opts.showLoader - show overlay loader
 */
MediaPicker.prototype.getMedias = function (opts = {}) {
    return new Promise(function (resolve, reject) {
        cordova.exec(resolve, reject, 'MediaPicker', 'getMedias', [opts]);
    });
};

/**
 * Get Exif data for a specific file
 * @param {string} fileUri - The file URI (file://...)
 * @param {string} [key] - Optional specific Exif tag name
 */
MediaPicker.prototype.getExifForKey = function (fileUri, key = null) {
    return new Promise(function (resolve, reject) {
        // On passe fileUri et key (qui peut être null)
        cordova.exec(resolve, reject, 'MediaPicker', 'getExifForKey', [fileUri, key]);
    });
};

module.exports = new MediaPicker();
module.exports.MediaPicker = module.exports;

// For ES module import support
if (typeof window !== 'undefined' && window.cordova && window.cordova.plugins) {
    window.cordova.plugins.MediaPicker = module.exports;
}
