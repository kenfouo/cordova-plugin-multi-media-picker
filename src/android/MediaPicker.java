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

            this.pendingAction = action;
            this.pendingArgs = args;
            this.pendingCallback = callbackContext;
            this.callbackContext = callbackContext;
        
            String[] permissions;
        
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO
                };
            } else {
                permissions = new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                };
            }
        
            if (hasPermissions(permissions)) {
        
                processGetLastMedias(args);
        
            } else {
        
                cordova.requestPermissions(
                    this,
                    PERMISSION_REQUEST_CODE,
                    permissions
                );
            }
        
            return true;
        }

/*         if ("getLastMedias".equals(action)) {
            this.callbackContext = callbackContext;
            this.lastArgs = args;
        
            String[] permissions;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions = new String[]{ 
                    android.Manifest.permission.READ_MEDIA_IMAGES, 
                    android.Manifest.permission.READ_MEDIA_VIDEO 
                };
            } else {
                permissions = new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE };
            }
        
            // Si les permissions sont déjà là (accordées par un autre plugin par ex)
            // on lance directement.
            if (hasPermissions(permissions)) {
                processGetLastMedias(args);
            } else {
                // Sinon on demande
                cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, permissions);
            }
            return true;
        } */

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
    private JSONArray getLastMedias(String mediaType, int limit) throws JSONException {
        JSONArray result = new JSONArray();
        ArrayList<JSONObjectWithTimestamp> tempList = new ArrayList<>();

        // Internal function to retrieve media by type
        BiConsumer<String, Integer> fetchByType = (type, lim) -> {
            try {
                Uri collection;
                String[] projection;

                if ("images".equals(type)) {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    projection = new String[]{
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DATE_TAKEN,
                            MediaStore.Images.Media.WIDTH,
                            MediaStore.Images.Media.HEIGHT
                    };
                } else { // video
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    projection = new String[]{
                            MediaStore.Video.Media._ID,
                            MediaStore.Video.Media.DATE_TAKEN,
                            MediaStore.Video.Media.DURATION,
                            MediaStore.Video.Media.WIDTH,
                            MediaStore.Video.Media.HEIGHT
                    };
                }

                Cursor cursor = cordova.getContext().getContentResolver().query(
                        collection,
                        projection,
                        null,
                        null,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC"
                );

                if (cursor == null) return;

                int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                int durCol = "videos".equals(type) ? cursor.getColumnIndex(MediaStore.Video.Media.DURATION) : -1;
                int widthCol = cursor.getColumnIndex(type.equals("images") ?
                        MediaStore.Images.Media.WIDTH : MediaStore.Video.Media.WIDTH);
                int heightCol = cursor.getColumnIndex(type.equals("images") ?
                        MediaStore.Images.Media.HEIGHT : MediaStore.Video.Media.HEIGHT);
                int dateTakenCol = cursor.getColumnIndex(type.equals("images") ?
                        MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN);
                int dateAddedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);

                int count = 0;
                while (cursor.moveToNext() && count < lim) {
                    long id = cursor.getLong(idCol);
                    Uri mediaUri = Uri.withAppendedPath(collection, String.valueOf(id));

                    JSONObject obj = new JSONObject();
                    obj.put("index", id);
                    obj.put("type", "images".equals(type) ? "image" : "video");

                    // Copy to cache
                    try {
                        String ext = "images".equals(type) ? "jpg" : "mp4";
                        File cacheFile = new File(cordova.getContext().getCacheDir(),
                                "media_" + id + "." + ext);

                        if (!cacheFile.exists()) {
                            InputStream in = cordova.getContext().getContentResolver().openInputStream(mediaUri);
                            FileOutputStream out = new FileOutputStream(cacheFile);
                            byte[] buffer = new byte[8192];
                            int len;
                            while (in != null && (len = in.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                            if (in != null) in.close();
                            out.close();
                        }

                        obj.put("uri", "file://" + cacheFile.getAbsolutePath());
                    } catch (Exception e) {
                        obj.put("uri", mediaUri.toString());
                    }

                    obj.put("width", widthCol != -1 ? cursor.getInt(widthCol) : 0);
                    obj.put("height", heightCol != -1 ? cursor.getInt(heightCol) : 0);

                    long ts = 0L;
                    if (dateTakenCol != -1) ts = cursor.getLong(dateTakenCol);
                    if (ts == 0 && dateAddedCol != -1) ts = cursor.getLong(dateAddedCol) * 1000L;

                    if (ts == 0) {
                        String realPath = getRealPathFromUri(mediaUri);
                        if (realPath != null) {
                            File f = new File(realPath);
                            if (f.exists()) ts = f.lastModified();
                        }
                    }

                    String creationDateStr = "01/01/1970";
                    if (ts != 0) {
                        Date date = new Date(ts);
                        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
                        creationDateStr = sdf.format(date);
                    }
                    obj.put("creationDate", creationDateStr);

                    if ("videos".equals(type)) {
                        obj.put("duration", durCol != -1 ? cursor.getLong(durCol) / 1000.0 : 0.0);
                        obj.put("thumbnail", generateVideoThumb(mediaUri));
                    }

                    tempList.add(new JSONObjectWithTimestamp(obj, ts));

                    count++;
                }
                cursor.close();
            } catch (Exception ignored) {}
        };

        if ("all".equals(mediaType)) {
            fetchByType.accept("images", limit);
            fetchByType.accept("videos", limit);
        } else {
            fetchByType.accept(mediaType, limit);
        }

        tempList.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        for (int i = 0; i < Math.min(limit, tempList.size()); i++) {
            result.put(tempList.get(i).json);
        }

        return result;
    }

    // Internal wrapper to store timestamp without exposing it
    private static class JSONObjectWithTimestamp {
        JSONObject json;
        long timestamp;

        JSONObjectWithTimestamp(JSONObject json, long timestamp) {
            this.json = json;
            this.timestamp = timestamp;
        }
    }

    // Generates a video thumbnail, stores it in cache, and returns the file path.
    private String generateVideoThumb(Uri videoUri) {
        try {
            File thumbFile = new File(
                cordova.getContext().getCacheDir(),
                "thumb_" + videoUri.hashCode() + ".jpg"
            );

            if (thumbFile.exists()) {
                return "file://" + thumbFile.getAbsolutePath();
            }

            Bitmap thumb = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ : Use loadThumbnail (MICRO_KIND ≈ 96x96)
                thumb = cordova.getContext().getContentResolver().loadThumbnail(
                    videoUri,
                    new Size(96, 96),
                    null
                );
            } else {
                // Android < 10 : ThumbnailUtils, requires a real file path
                String path = getRealPathFromUri(videoUri);
                if (path != null) {
                    thumb = ThumbnailUtils.createVideoThumbnail(
                        path,
                        MediaStore.Images.Thumbnails.MICRO_KIND
                    );
                }
            }

            if (thumb == null) return null;

            FileOutputStream fos = new FileOutputStream(thumbFile);
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.close();

            return "file://" + thumbFile.getAbsolutePath();

        } catch (Exception e) {
            return null;
        }
    }

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
    
    private void processGetLastMedias(JSONArray args) {
        // Initialisation par défaut (Utilise tes réglages 2026 : limit 20, type image)
        int limit = 20;
        String lastMediaType = "images"; 
    
        if (args != null && args.length() > 0) {
            JSONObject opts = args.optJSONObject(0);
            if (opts != null) {
                lastMediaType = opts.optString("mediaType", "images");
                limit = opts.optInt("limit", 20);
            }
        }
    
        final String finalMediaType = lastMediaType;
        final int finalLimit = limit;
    
        cordova.getThreadPool().execute(() -> {
            try {
                JSONArray res = getLastMedias(finalMediaType, finalLimit);
                callbackContext.success(res);
            } catch (Exception e) {
                e.printStackTrace();
                callbackContext.error("Internal error: " + e.getMessage());
            }
        });
    }

    @Override
    public void onRequestPermissionResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
    
        if (requestCode != PERMISSION_REQUEST_CODE) return;
    
        boolean granted = true;
    
        if (grantResults.length == 0) granted = false;
    
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                granted = false;
                break;
            }
        }
    
        if (granted) {
    
            final JSONArray args =
                pendingArgs != null ? pendingArgs : new JSONArray();
    
            final CallbackContext cb =
                pendingCallback != null ? pendingCallback : callbackContext;
    
            cordova.getActivity().runOnUiThread(() -> {
    
                cordova.getThreadPool().execute(() -> {
    
                    try {
                        processGetLastMedias(args);
                    } catch (Exception e) {
                        if (cb != null) {
                            cb.error(e.getMessage());
                        }
                    }
                });
            });
    
        } else {
    
            if (pendingCallback != null) {
                pendingCallback.error("Permission denied");
            }
        }
    
        pendingAction = null;
        pendingArgs = null;
        pendingCallback = null;
    }

    /* @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            if (grantResults.length > 0) {
                for (int r : grantResults) {
                    if (r == android.content.pm.PackageManager.PERMISSION_DENIED) {
                        allGranted = false;
                        break;
                    }
                }
            } else {
                allGranted = false;
            }
    
            if (allGranted) {
                // Utiliser un Runnable pour s'assurer que l'UI et le ThreadPool sont prêts
                final JSONArray argsToUse = (this.lastArgs != null) ? this.lastArgs : new JSONArray();
                
                // On relance la procédure immédiatement
                cordova.getThreadPool().execute(() -> {
                    processGetLastMedias(argsToUse);
                });
            } else {
                if (this.callbackContext != null) {
                    this.callbackContext.error("Permission denied");
                }
            }
            this.lastArgs = null; // Nettoyage
        }
    }     */

    private boolean hasPermissions(String[] permissions) {
        for (String p : permissions) {
            if (!cordova.hasPermission(p)) return false;
        }
        return true;
    }
}
