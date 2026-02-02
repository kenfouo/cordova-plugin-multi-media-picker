function MediaPicker() { }

/**
 * Get medias with options
 * @param {Object} opts
 * @param {number} opts.selectionLimit - max number of medias
 * @param {boolean} opts.showLoader - show overlay loader
 */
MediaPicker.prototype.getMedias = function (opts = {}, successCallback, errorCallback) {

    if (typeof successCallback == 'function' && typeof successCallback == 'function') {

        return cordova.exec(successCallback, errorCallback, 'MediaPicker', 'getMedias', [opts]);
    }
    else {
        return new Promise(function (resolve, reject) {
            cordova.exec(resolve, reject, 'MediaPicker', 'getMedias', [opts]);
        });
    }
};

/**
 * Get Exif data
 * @param {string} fileUri - L'URI du fichier
 * @param {string} key - La clé spécifique (ou null)
 * @param {function} successCallback - Fonction en cas de succès
 * @param {function} errorCallback - Fonction en cas d'erreur
*/
MediaPicker.prototype.getExifForKey = function (fileUri, key = null, successCallback, errorCallback) {

    if(typeof successCallback == 'function' && typeof successCallback == 'function'){

        return cordova.exec(successCallback, errorCallback, 'MediaPicker', 'getExifForKey', [fileUri, key]);
    }
    else{
        return new Promise(function (resolve, reject) {
            // On passe fileUri et key (qui peut être null)
            cordova.exec(resolve, reject, 'MediaPicker', 'getExifForKey', [fileUri, key]);
        });
    }
};


module.exports = new MediaPicker();
module.exports.MediaPicker = module.exports;

// For ES module import support
if (typeof window !== 'undefined' && window.cordova && window.cordova.plugins) {
    window.cordova.plugins.MediaPicker = module.exports;
}
