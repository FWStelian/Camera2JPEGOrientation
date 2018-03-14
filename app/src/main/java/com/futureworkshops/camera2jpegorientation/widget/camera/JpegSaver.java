package com.futureworkshops.camera2jpegorientation.widget.camera;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.support.annotation.NonNull;
import android.support.media.ExifInterface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Helper class that saves JPEG files with the correct EXIF information.
 * <p>
 * <p> The JPEGs must be saved in the internal memory -> doesn't require SD_WRITE permission!
 * <p>
 * The reason is that these files have a temporary nature and are intended only to increase processing performance.
 * <p/>Both {@link BitmapFactory#decodeFile(String)} and Open CV's <b>imread(String)</b>
 * use the EXIF tags to correctly determine image rotation.
 * </p>
 */

public class JpegSaver {
    
    private static final String TEMP_DIR = "tmp";
    
    private Context context;
    
    public JpegSaver(@NonNull Context context) {
        this.context = context;
    }
    
    /**
     * Save the {@code byte[]} data in a temporary folder with the name {@code imageName}.
     * <p/>
     * The method will update the <b>EXIF</b> data
     * @param imageData
     * @param imageName
     * @param rotation
     * @return
     */
    public String saveTempJpeg(byte[] imageData, @NonNull String imageName, int rotation) {
        final File tmpDir = getTmpDir();
        final File file = new File(tmpDir, imageName);
        FileOutputStream output = null;
        String path = null;
        
        try {
            // save file to temp path
            output = new FileOutputStream(file);
            output.write(imageData);
            output.close();
            
            //  read EXIF tags
            ExifInterface exifInterface = new ExifInterface(file.getAbsolutePath());
            
            int exifOrientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL);
            
            Log.d("JpegSaver", "previous exif orientation: " + String.valueOf(exifOrientation));
            
            // update EXIF orientation tag in case some devices don't add it
            int exifRotation = ExifInterface.ORIENTATION_NORMAL;
            switch (rotation) {
                case 90:
                    exifRotation = ExifInterface.ORIENTATION_ROTATE_90;
                    break;
                case 180:
                    exifRotation = ExifInterface.ORIENTATION_ROTATE_180;
                    break;
                case 270:
                    exifRotation = ExifInterface.ORIENTATION_ROTATE_270;
                    break;
            }
            
            Log.d("JpegSaver", "updated exif orientation: " + String.valueOf(exifRotation));
            
            exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifRotation));
            exifInterface.saveAttributes();
            final int degrees = exifInterface.getRotationDegrees();
            
            path = file.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
            if (null != output) {
                try {
                    output.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
        
        return path;
    }
    
    private File getTmpDir() {
        File tempDir = new File(this.context.getFilesDir(), TEMP_DIR);
        
        if (!tempDir.exists()) {
            tempDir.mkdir();
        }
        
        return tempDir;
    }
}
