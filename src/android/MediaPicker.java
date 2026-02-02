package com.okanbeydanol.mediaPicker;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ExifInterface;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

public class MediaPicker extends CordovaPlugin {

    private static final int REQUEST_CODE = 2025;

    private CallbackContext callbackContext;
    private int selectionLimit = 3;
    private boolean showLoader = true;
    private boolean imageOnly = false;
    private String mediaType = "all"; // images | videos | all

    private FrameLayout overlayView;
    private ProgressBar overlaySpinner;
    private boolean isPickerOpen = false;

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        if ("getMedias".equals(action)) {
            if (isPickerOpen) {
                callbackContext.error("Picker is already open");
                return true;
            }
            this.callbackContext = callbackContext;

            if (args != null && args.length() > 0) {
                JSONObject opts = args.optJSONObject(0);
                if (opts != null) {
                    selectionLimit = Math.max(1, opts.optInt("selectionLimit", 3));
                    showLoader = opts.optBoolean("showLoader", true);
                    imageOnly = opts.optBoolean("imageOnly", false);
                    mediaType = opts.optString("mediaType", null);
                    // compatibility fallback for older versions
                    if (mediaType == null || mediaType.isEmpty()) {
                        mediaType = imageOnly ? "images" : "all";
                    }     
                }
            }

            Intent intent;

            if (isPhotoPickerAvailable() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2) {
                // ✅ Android 11+ with Photo Picker (native or backported via Play Services)
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);

                if (selectionLimit > 1) {
                    intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, selectionLimit);
                }

                switch (mediaType) {
                    case "images":
                        intent.setType("image/*");
                        break;

                    case "videos":
                        intent.setType("video/*");
                        break;

                    case "all":
                    default:
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"image/*", "video/*"});
                        break;
                }


            } else {
                // ✅ Fallback for Android 10 and below
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);

               switch (mediaType) {
                    case "images":
                        intent.setType("image/*");
                        break;

                    case "videos":
                        intent.setType("video/*");
                        break;

                    case "all":
                    default:
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"image/*", "video/*"});
                        break;
                }

                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, selectionLimit > 1);
            }

            isPickerOpen = true;
            cordova.startActivityForResult(this, intent, REQUEST_CODE);
            return true;
        }

        if ("getExifForKey".equals(action)) {
            String fileUri = args.optString(0);
            String key = args.optString(1, null); // null si non fourni
            
            if (fileUri == null || fileUri.isEmpty()) {
                callbackContext.error("File URI is required");
                return true;
            }

            cordova.getThreadPool().execute(() -> {
                try {
                    // Nettoyage de l'URI (enlever file:// si présent)
                    String path = fileUri.replace("file://", "");
                    String exifData = getExifData(path, key);
                    callbackContext.success(exifData);
                } catch (Exception e) {
                    callbackContext.error("Exif error: " + e.getMessage());
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE)
            return;

        isPickerOpen = false;

        if (resultCode != Activity.RESULT_OK || data == null) {
            callbackContext.success(new JSONArray()); // empty array
            return;
        }

        if (showLoader)
            showLoaderOverlay();

        cordova.getThreadPool().execute(() -> {
            ArrayList<JSONObject> results = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();

            try {
                if (data.getClipData() != null) {
                    int count = Math.min(data.getClipData().getItemCount(), selectionLimit);
                    for (int i = 0; i < count; i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        JSONObject obj = copyUriToCache(uri, i, errors);
                        if (obj != null)
                            results.add(obj);
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    JSONObject obj = copyUriToCache(uri, 0, errors);
                    if (obj != null)
                        results.add(obj);
                }
            } catch (Exception e) {
                errors.add("Unexpected error: " + e.getMessage());
            }

            JSONArray array = new JSONArray();
            for (JSONObject o : results)
                array.put(o);

            cordova.getActivity().runOnUiThread(() -> {
                if (showLoader)
                    hideLoaderOverlay();
                if (!errors.isEmpty()) {
                    callbackContext.error(String.join("\n", errors));
                } else {
                    callbackContext.success(array);
                }
            });
        });
    }

    // ✅ Safe helper to check Photo Picker availability
    private boolean isPhotoPickerAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false; // Android 10 and below → no Photo Picker
        }

        try {
            int extension = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R);
            return extension >= 2; // API available via Play Services
        } catch (NoClassDefFoundError e) {
            return false; // SdkExtensions missing on some OEM ROMs
        } catch (Exception e) {
            return false; // Defensive fallback
        }
    }

    private JSONObject copyUriToCache(Uri uri, int index, ArrayList<String> errors) {
        try {
            String fileName = null;
            long fileSize = 0;

            Cursor cursor = cordova.getContext().getContentResolver()
                .query(uri, null, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                }
                cursor.close();
            }

            String ext = getExtension(uri);
            if (ext == null || ext.isEmpty()) {
                ext = "dat";
            }

            File dest = new File(cordova.getContext().getCacheDir(),
                UUID.randomUUID().toString() + "_" + index + "." + ext);

            try (InputStream in = cordova.getContext().getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {

                byte[] buffer = new byte[8192];
                int len;
                while (in != null && (len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }

            if (fileName == null) {
                fileName = dest.getName();
            }
            if (fileSize == 0) {
                fileSize = dest.length();
            }

            String mime = resolveMime(uri, dest, ext);
            String type = "other";
            JSONObject obj = new JSONObject();
            if (mime.startsWith("image/")) {
                type = "image";
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(dest.getAbsolutePath(), options);
                obj.put("width", options.outWidth);
                obj.put("height", options.outHeight);
            } else if (mime.startsWith("video/")) {
                type = "video";
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    retriever.setDataSource(dest.getAbsolutePath());
                    String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (w != null) obj.put("width", Integer.parseInt(w));
                    if (h != null) obj.put("height", Integer.parseInt(h));
                    if (d != null) obj.put("duration", Long.parseLong(d) / 1000.0);
                }
            }

            obj.put("index", index);
            obj.put("uri", "file://" + dest.getAbsolutePath());
            obj.put("fileName", fileName);
            obj.put("fileSize", fileSize);
            obj.put("mimeType", mime);
            obj.put("type", type);

            return obj;

        } catch (Exception e) {
            errors.add("Item " + index + " copy error: " + e.getMessage());
            return null;
        }
    }

    private String resolveMime(Uri uri, File dest, String ext) {
        String mime = cordova.getContext().getContentResolver().getType(uri);

        // Try system type
        if (mime != null) return mime;

        // Try by extension
        if (ext != null) {
            String lower = ext.toLowerCase();
            switch (lower) {
                case "jpg":
                case "jpeg":
                    return "image/jpeg";
                case "png":
                    return "image/png";
                case "gif":
                    return "image/gif";
                case "mp4":
                    return "video/mp4";
                case "mov":
                    return "video/quicktime";
            }
        }

        // Try via MediaMetadataRetriever for video
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(dest.getAbsolutePath());
            String mimeFromMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            if (mimeFromMeta != null) return mimeFromMeta;
        } catch (Exception ignored) {}

        // Fallback
        return "application/octet-stream";
    }

    private String getExtension(Uri uri) {
        String ext = null;
        Cursor cursor = cordova.getContext().getContentResolver()
            .query(uri, null, null, null, null);
        if (cursor != null) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex != -1 && cursor.moveToFirst()) {
                String name = cursor.getString(nameIndex);
                int dot = name.lastIndexOf('.');
                if (dot > 0)
                    ext = name.substring(dot + 1);
            }
            cursor.close();
        }
        return ext;
    }

    private void showLoaderOverlay() {
        Activity activity = cordova.getActivity();
        activity.runOnUiThread(() -> {
            overlayView = new FrameLayout(activity);
            overlayView.setBackgroundColor(0x80000000);
            overlaySpinner = new ProgressBar(activity, null, android.R.attr.progressBarStyleLarge);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.CENTER;
            overlayView.addView(overlaySpinner, lp);
            activity.addContentView(overlayView,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        });
    }

    private void hideLoaderOverlay() {
        Activity activity = cordova.getActivity();
        activity.runOnUiThread(() -> {
            if (overlayView != null) {
                ((FrameLayout) overlayView.getParent()).removeView(overlayView);
                overlayView = null;
                overlaySpinner = null;
            }
        });
    }

    private String getExifData(String filePath, String targetKey) throws Exception {
        JSONObject results = new JSONObject();
        File file = new File(filePath);
        if (!file.exists()) throw new Exception("File not found");

        // Détection sommaire du type
        boolean isVideo = filePath.toLowerCase().endsWith(".mp4") || filePath.toLowerCase().endsWith(".mov");

        if (isVideo) {
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(filePath);
                // Liste des clés communes pour les vidéos
                int[] keys = {
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                    MediaMetadataRetriever.METADATA_KEY_BITRATE,
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE,
                    MediaMetadataRetriever.METADATA_KEY_DATE,
                    MediaMetadataRetriever.METADATA_KEY_LOCATION
                };

                if (targetKey != null && !targetKey.isEmpty()) {
                    // Si on cherche une clé spécifique (il faut passer l'ID entier de la constante)
                    String val = retriever.extractMetadata(Integer.parseInt(targetKey));
                    return val;
                } else {
                    for (int k : keys) {
                        String val = retriever.extractMetadata(k);
                        if (val != null) results.put(String.valueOf(k), val);
                    }
                }
            }
        } else {
            // Traitement Image via ExifInterface
            ExifInterface exif = new ExifInterface(filePath);
            
            // Liste des tags standards à retourner si aucune clé n'est spécifiée
            String[] tags = {
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS
            };

            if (targetKey != null && !"null".equals(targetKey) && !targetKey.isEmpty()) {

                return exif.getAttribute(targetKey);
            } else {
                for (String tag : tags) {
                    String val = exif.getAttribute(tag);

                    results.put(tag, val);
                }
            }
        }
        return results.toString();
    }
}
