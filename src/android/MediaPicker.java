package com.okanbeydanol.mediaPicker;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.util.Size;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.BiConsumer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.media.ExifInterface;

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

    private static final int PERMISSION_REQUEST_CODE = 2026;
    private static final String READ_EXTERNAL_STORAGE = android.Manifest.permission.READ_EXTERNAL_STORAGE;
    // Pour Android 13+ (API 33)
    private static final String READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES";
    private static final String READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO";

    private JSONArray lastArgs; // Pour stocker les arguments en attente de permission

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
                //  Fallback for Android 10 and below
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

        if ("getLastMedias".equals(action)) {
            this.callbackContext = callbackContext;
            this.lastArgs = args; // Crucial pour la reprise après permission

            String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                };
            } else {
                permissions = new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE };
            }

            if (hasPermissions(permissions)) {
                processGetLastMedias(args);
            } else {
                // Demande de permission : Cordova mettra en pause l'exécution
                cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, permissions);
            }
            return true;
        }

        if ("getExifForKey".equals(action)) {
            String fileUri = args.optString(0);
            String key = args.optString(1, null); // null si non fourni

            if (fileUri == null || fileUri.isEmpty()) {
                callbackContext.error("File URI is required");
                return true;
            }

            if ("null".equals(key) || key == null || key.isEmpty()) {
                callbackContext.error("Exif key is required");
                return true;
            }

            cordova.getThreadPool().execute(() -> {
                try {
                    // Nettoyage de l'URI (enlever file:// si présent)
                    String path = fileUri.replace("file://", "");

                    // Traitement Image via ExifInterface
                    ExifInterface exif = new ExifInterface(path);

                    String exifKeyValue = exif.getAttribute(key);

                    callbackContext.success(exifKeyValue);
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

    // Safe helper to check Photo Picker availability
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

    // Récupère les derniers médias (images/vidéos), les met en cache si besoin et retourne un JSONArray avec leurs infos (id, uri, type, chemin cache, durée et miniature pour les vidéos)
    private JSONArray getLastMedias(String mediaType, int limit, int offset) throws JSONException {
        JSONArray result = new JSONArray();
        ArrayList<JSONObjectWithTimestamp> tempList = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();

        // Point d'arrêt : on récupère assez d'items pour couvrir la page actuelle
        int endRange = offset + limit;

        // 1. Fonction interne pour scanner le MediaStore
        BiConsumer<String, Integer> fetchByType = (type, currentRange) -> {
            try {
                Uri collection = "images".equals(type) ?
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

                // On ajoute DATA et IS_PENDING à la projection pour le filtrage
                String[] projection = {
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.DATA,
                        (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) ?
                                MediaStore.MediaColumns.IS_PENDING : MediaStore.MediaColumns._ID
                };

                // Filtre de base : ignorer ce qui est caché ou en attente
                String selection = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) ?
                        MediaStore.MediaColumns.IS_PENDING + " = 0" : null;

                Cursor cursor = cordova.getContext().getContentResolver().query(
                        collection, projection, selection, null,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC"
                );

                if (cursor == null) return;

                int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                int dateAddedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);

                int count = 0;
                while (cursor.moveToNext() && count < currentRange) {
                    String path = cursor.getString(dataCol);

                    // FILTRE CRITIQUE : Ignorer les dossiers cachés
                    if (path != null && (path.contains("/.") || path.toLowerCase().contains("vault"))) {
                        continue;
                    }

                    long id = cursor.getLong(idCol);
                    Uri mediaUri = Uri.withAppendedPath(collection, String.valueOf(id));
                    long ts = cursor.getLong(dateAddedCol) * 1000L;

                    tempList.add(new JSONObjectWithTimestamp(new JSONObject(), ts, mediaUri, type));
                    count++;
                }
                cursor.close();
            } catch (Exception e) {
                Log.e("MediaPicker", "Error fetching " + type + ": " + e.getMessage());
            }
        };

        // 2. Exécution du scan selon le type demandé
        if ("all".equals(mediaType)) {
            fetchByType.accept("images", endRange);
            fetchByType.accept("videos", endRange);
        } else {
            fetchByType.accept(mediaType.equals("images") ? "images" : "videos", endRange);
        }

        // 3. Tri global par date (du plus récent au plus ancien)
        tempList.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        // 4. Traitement final : pagination et copie physique des fichiers
        // On ne traite que la tranche [offset -> offset + limit]
        for (int i = offset; i < Math.min(offset + limit, tempList.size()); i++) {
            JSONObjectWithTimestamp item = tempList.get(i);

            // C'est ici qu'on fait le travail lourd (lecture fichier + copie cache)
            JSONObject mediaInfo = copyUriToCache(item.uri, i, errors);

            if (mediaInfo != null) {
                result.put(mediaInfo);
            }
        }

        return result;
    }

    // Internal wrapper to store timestamp without exposing it
    private static class JSONObjectWithTimestamp {
        JSONObject json;
        long timestamp;
        Uri uri;
        String type;

        JSONObjectWithTimestamp(JSONObject json, long timestamp, Uri uri, String type) {
            this.json = json;
            this.timestamp = timestamp;
            this.uri = uri;
            this.type = type;
        }
    }

    // Generates a video thumbnail, stores it in cache, and returns the file path.
    private static final String TAG = "VideoThumbGenerator";

    // For Android < Q, retrieves the actual file path of the video
    private String getRealPathFromUri(Uri contentUri) {
        String[] proj = { MediaStore.Video.Media.DATA };
        Cursor cursor = cordova.getContext().getContentResolver().query(contentUri, proj, null, null, null);
        if (cursor != null) {
            int colIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            if (cursor.moveToFirst()) {
                String path = cursor.getString(colIndex);
                cursor.close();
                return path;
            }
            cursor.close();
        }
        return null;
    }

    private JSONObject copyUriToCache(Uri uri, int index, ArrayList<String> errors) {
        try {
            String fileName = null;
            long fileSize = 0;
            String mime = null;

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
            if (ext == null || ext.isEmpty()) ext = "dat";

            String id = String.valueOf(uri.hashCode());

            // ✅ Nom unique basé sur URI (évite les doublons)
            String baseName = id + "_" + index;

            File dest = new File(
                    cordova.getContext().getCacheDir(),
                    baseName + "." + ext
            );

            // ✅ Copier uniquement si pas déjà présent
            if (!dest.exists()) {

                try (InputStream in = cordova.getContext()
                        .getContentResolver()
                        .openInputStream(uri);

                     FileOutputStream out = new FileOutputStream(dest)) {

                    byte[] buffer = new byte[8192];
                    int len;

                    while (in != null && (len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
            }

            if (fileName == null) fileName = dest.getName();
            if (fileSize == 0) fileSize = dest.length();

            mime = resolveMime(uri, dest, ext);
            // ==============================
            // HEIC → JPEG CONVERSION
            // ==============================
            String originalMime = cordova.getContext()
                    .getContentResolver()
                    .getType(uri);

            boolean isHeic = false;
            if (mime != null) {
                isHeic = mime.toLowerCase().contains("heic")
                        || mime.toLowerCase().contains("heif");
            }

            if (!isHeic && ext != null) {
                isHeic = ext.equalsIgnoreCase("heic")
                        || ext.equalsIgnoreCase("heif");
            }

            if (isHeic) {

                try {

                    Bitmap bitmap = BitmapFactory.decodeFile(dest.getAbsolutePath());

                    if (bitmap != null) {

                        File jpegFile = new File(
                                cordova.getContext().getCacheDir(),
                                baseName + ".jpg"
                        );

                        try (FileOutputStream out = new FileOutputStream(jpegFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                            out.flush();
                        }

                        // Copier orientation AVANT delete
                        try {
                            ExifInterface oldExif = new ExifInterface(dest.getAbsolutePath());
                            ExifInterface newExif = new ExifInterface(jpegFile.getAbsolutePath());

                            String orientation = oldExif.getAttribute(ExifInterface.TAG_ORIENTATION);
                            if (orientation != null) {
                                newExif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation);
                                newExif.saveAttributes();
                            }
                        } catch (Exception ignored) {}

                        bitmap.recycle();

                        dest.delete();

                        dest = jpegFile;
                        ext = "jpg";
                        mime = "image/jpeg";
                        fileName = jpegFile.getName();
                        fileSize = jpegFile.length();
                    }

                } catch (Exception e) {
                    errors.add("HEIC conversion error: " + e.getMessage());
                }
            }

            JSONObject obj = new JSONObject();

            String type = "other";

        /* ===============================
           IMAGE
         =============================== */
            if (mime != null && mime.startsWith("image/")){

                type = "image";

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;

                BitmapFactory.decodeFile(dest.getAbsolutePath(), options);

                obj.put("width", options.outWidth);
                obj.put("height", options.outHeight);
            }

        /* ===============================
           VIDEO + THUMB
         =============================== */
            //else if (mime.startsWith("video/")) {
            else if (mime != null && mime.startsWith("video/")) {

                type = "video";

                // Métadonnées vidéo
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {

                    retriever.setDataSource(dest.getAbsolutePath());

                    String w = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);

                    String h = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

                    String d = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

                    if (w != null) obj.put("width", Integer.parseInt(w));
                    if (h != null) obj.put("height", Integer.parseInt(h));
                    if (d != null) obj.put("duration", Long.parseLong(d) / 1000.0);
                }

                // ======================
                // THUMBNAIL VIDEO
                // ======================

                File thumbFile = new File(
                        cordova.getContext().getCacheDir(),
                        "thumb_" + baseName + ".jpg"
                );

                if (!thumbFile.exists()) {

                    Bitmap thumb = null;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                        try {
                            Size size = new Size(128, 128);

                            thumb = cordova.getContext()
                                    .getContentResolver()
                                    .loadThumbnail(uri, size, null);

                        } catch (Exception e) {

                            // fallback
                            thumb = ThumbnailUtils.createVideoThumbnail(
                                    dest,
                                    new Size(128, 128),
                                    null
                            );
                        }

                    } else {

                        thumb = ThumbnailUtils.createVideoThumbnail(
                                dest.getAbsolutePath(),
                                MediaStore.Video.Thumbnails.MINI_KIND
                        );
                    }

                    if (thumb != null) {

                        try (FileOutputStream fos =
                                     new FileOutputStream(thumbFile)) {

                            thumb.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    80,
                                    fos
                            );
                            fos.flush();
                        }

                        thumb.recycle();
                    }
                }

                if (thumbFile.exists()) {
                    obj.put("thumbnail", "file://" + thumbFile.getAbsolutePath());
                }
            }

        /* ===============================
           RESULT
         =============================== */

            obj.put("id", id);
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

        if (uri != null) {
            String mime = cordova.getContext().getContentResolver().getType(uri);
            if (mime != null) return mime;
        }

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
                case "heic":
                    return "image/heic";
            }
        }

        // Try via MediaMetadataRetriever for video
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(dest.getAbsolutePath());
            String mimeFromMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            if (mimeFromMeta != null) return mimeFromMeta;
        } catch (Exception ignored) {}

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

    private void processGetLastMedias(JSONArray args) {
        int limit = 20;
        int offset = 0; // Ajout de l'offset
        String lastMediaType = "images";

        if (args != null && args.length() > 0) {
            JSONObject opts = args.optJSONObject(0);
            if (opts != null) {
                lastMediaType = opts.optString("mediaType", "images");
                limit = opts.optInt("limit", 20);
                offset = opts.optInt("offset", 0); // Récupération de l'offset
            }
        }

        final String finalMediaType = lastMediaType;
        final int finalLimit = limit;
        final int finalOffset = offset;

        cordova.getThreadPool().execute(() -> {
            try {
                // On passe l'offset à la méthode de récupération
                JSONArray res = getLastMedias(finalMediaType, finalLimit, finalOffset);
                callbackContext.success(res);
            } catch (Exception e) {
                e.printStackTrace();
                callbackContext.error("Internal error: " + e.getMessage());
            }
        });
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            if (grantResults.length > 0) {
                for (int r : grantResults) {
                    if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            } else {
                allGranted = false;
            }

            if (allGranted) {
                // IMPORTANT : Utiliser le thread de Cordova pour ne pas bloquer l'UI
                // et s'assurer que le context est toujours valide.
                cordova.getThreadPool().execute(() -> {
                    // Si lastArgs a été perdu durant le switch (rare mais possible),
                    // on utilise des valeurs par défaut.
                    processGetLastMedias(this.lastArgs);
                });
            } else {
                if (this.callbackContext != null) {
                    this.callbackContext.error("Permission denied");
                }
            }
        }
    }

    private boolean hasPermissions(String[] permissions) {
        for (String p : permissions) {
            if (!cordova.hasPermission(p)) return false;
        }
        return true;
    }
}
