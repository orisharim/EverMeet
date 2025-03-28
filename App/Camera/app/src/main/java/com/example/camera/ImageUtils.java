package com.example.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;

public class ImageUtils {

    /**
     * Converts NV21 byte array to Bitmap.
     *
     * @param nv21Data The NV21 byte array.
     * @param width The width of the image.
     * @param height The height of the image.
     * @return The resulting Bitmap.
     */
    public static Bitmap convertNV21ToBitmap(byte[] nv21Data, int width, int height) {
        YuvImage yuvImage = new YuvImage(nv21Data, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, outputStream);
        byte[] jpegData = outputStream.toByteArray();
        return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
    }

    /**
     * Converts byte array in NV21 format to Bitmap.
     *
     * @param imageData The NV21 byte array.
     * @param width The width of the image.
     * @param height The height of the image.
     * @return The resulting Bitmap.
     */
    public static Bitmap convertBytesToImage(byte[] imageData, int width, int height) {
        return convertNV21ToBitmap(imageData, width, height);
    }

    /**
     * Converts Bitmap to NV21 byte array.
     *
     * @param bitmap The Bitmap to convert.
     * @param width The width of the image.
     * @param height The height of the image.
     * @return The resulting NV21 byte array.
     */
    public static byte[] convertBitmapToNV21(Bitmap bitmap, int width, int height) {
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        byte[] nv21Data = new byte[width * height * 3 / 2];
        encodeYUV420SP(nv21Data, pixels, width, height);
        return nv21Data;
    }

    // Helper function to encode RGBA to NV21
    private static void encodeYUV420SP(byte[] out, int[] pixels, int width, int height) {
        final int frameSize = width * height;
        int uvIndex = frameSize;
        int yIndex = 0;

        int r, g, b, y, u, v;
        int pixel;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                pixel = pixels[j * width + i];
                r = (pixel >> 16) & 0xFF;
                g = (pixel >> 8) & 0xFF;
                b = pixel & 0xFF;

                // YUV 4:2:0 Planar
                y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                y = Math.max(0, Math.min(y, 255));
                u = Math.max(0, Math.min(u, 255));
                v = Math.max(0, Math.min(v, 255));

                out[yIndex++] = (byte) y;

                if (j % 2 == 0 && i % 2 == 0) {
                    out[uvIndex++] = (byte) u;
                    out[uvIndex++] = (byte) v;
                }
            }
        }
    }
}
