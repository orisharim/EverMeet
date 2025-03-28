package com.example.camera;


import android.media.Image;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.nio.ByteBuffer;
import java.nio.ByteBuffer;

public class ImageUtils {

    /**
     * Converts an ImageProxy (YUV_420_888) to a byte array.
     *
     * @param imageProxy The ImageProxy to convert.
     * @return A byte array containing the raw YUV image data.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    public static byte[] imageProxyToByteArray(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        int totalSize = 0;
        for (Image.Plane plane : image.getPlanes()) {
            totalSize += plane.getBuffer().remaining();
        }

        byte[] imageBytes = new byte[totalSize];
        int offset = 0;

        for (Image.Plane plane : image.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();
            int size = buffer.remaining();
            buffer.get(imageBytes, offset, size);
            offset += size;
        }

        return imageBytes;
    }

    /**
     * Converts a byte array to a Bitmap.
     *
     * @param imageData The byte array representing the image.
     * @return The Bitmap created from the byte array.
     */
    public static Bitmap byteArrayToBitmap(byte[] imageData) {
        if (imageData == null || imageData.length == 0) return null;

        return BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
    }

}
