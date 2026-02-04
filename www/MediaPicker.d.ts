declare namespace CordovaPlugins {

  interface MediaPicker {
    /**
     * Opens the media picker with the specified options.
     * Supports both Promise and Callback patterns.
     */
    getMedias(
      opts?: MediaPickerOptions,
      successCallback?: (results: MediaPickerResult[]) => void,
      errorCallback?: (error: any) => void
    ): Promise<MediaPickerResult[]>;

    /**
     * Retrieves the most recent media items (often used for quick previews).
     * Supports both Promise and Callback patterns.
     */
    getLastMedias(
      opts?: MediaPickerOptions,
      successCallback?: (results: MediaPickerResult[]) => void,
      errorCallback?: (error: any) => void
    ): Promise<MediaPickerResult[]>;

    /**
     * Retrieves EXIF data for a specific file.
     * @param fileUri The local file URI.
     * @param key Specific EXIF key (e.g., "DateTime"), or null to retrieve all data.
     * @param successCallback Function called on success.
     * @param errorCallback Function called on error.
     */
    getExifForKey(
      fileUri: string,
      key?: string | null,
      successCallback?: (data: any) => void,
      errorCallback?: (error: any) => void
    ): Promise<any>;
  }
}

/**
 * Result object returned by selection methods.
 */
export interface MediaPickerResult {
  /** Selection order starting at 0 */
  index: number;
  /** Local file URI (file://...) pointing to the media */
  uri: string;
  /** Original file name */
  fileName: string;
  /** File size in bytes */
  fileSize: number;
  /** MIME type (e.g., image/jpeg or video/mp4) */
  mimeType: string;
  /** Media classification */
  type: 'image' | 'video' | 'other';
  /** Pixel width (images/videos only) */
  width?: number;
  /** Pixel height (images/videos only) */
  height?: number;
  /** Duration in seconds (videos only) */
  duration?: number;
}

export interface MediaPickerOptions {
  /** Maximum number of items the user can select (default: 3) */
  selectionLimit?: number;
  /** Whether to show a loading overlay while processing files (default: true) */
  showLoader?: boolean;
  /** Restrict selection to images only (default: false) */
  imageOnly?: boolean;
  /** Filter the type of media displayed (default: 'all') */
  mediaType?: 'all' | 'images' | 'videos';
}

interface CordovaPlugins {
  MediaPicker: CordovaPlugins.MediaPicker;
}

interface Cordova {
  plugins: CordovaPlugins;
}

declare let cordova: Cordova;

/**
 * Exports for ES module and global support
 */
export const MediaPicker: CordovaPlugins.MediaPicker;
export as namespace MediaPicker;
declare const _default: CordovaPlugins.MediaPicker;
export default _default;
