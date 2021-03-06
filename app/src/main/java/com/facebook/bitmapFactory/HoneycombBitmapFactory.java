package com.facebook.bitmapFactory;

/**
 * Created by heshixiyang on 2017/3/16.
 */

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;

import com.facebook.image.EncodedImage;
import com.facebook.image.imageFormat.DefaultImageFormats;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.platformDecoder.PlatformDecoder;
import com.facebook.pool.poolUtil.TooManyBitmapsException;
import com.facebook.references.CloseableReference;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Factory implementation for Honeycomb through Kitkat
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
@ThreadSafe
public class HoneycombBitmapFactory extends PlatformBitmapFactory {

    private final EmptyJpegGenerator mJpegGenerator;
    private final PlatformDecoder mPurgeableDecoder;

    public HoneycombBitmapFactory(EmptyJpegGenerator jpegGenerator,
                                  PlatformDecoder purgeableDecoder) {
        mJpegGenerator = jpegGenerator;
        mPurgeableDecoder = purgeableDecoder;
    }

    /**
     * Creates a bitmap of the specified width and height.
     *
     * @param width the width of the bitmap
     * @param height the height of the bitmap
     * @param bitmapConfig the {@link Bitmap.Config}
     * used to create the decoded Bitmap
     * @return a reference to the bitmap
     * @throws TooManyBitmapsException if the pool is full
     * @throws OutOfMemoryError if the Bitmap cannot be allocated
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    @Override
    public CloseableReference<Bitmap> createBitmapInternal(
            int width,
            int height,
            Bitmap.Config bitmapConfig) {
        CloseableReference<PooledByteBuffer> jpgRef = mJpegGenerator.generate(
                (short) width,
                (short) height);
        try {
            EncodedImage encodedImage = new EncodedImage(jpgRef);
            encodedImage.setImageFormat(DefaultImageFormats.JPEG);
            try {
                CloseableReference<Bitmap> bitmapRef = mPurgeableDecoder.decodeJPEGFromEncodedImage(
                        encodedImage, bitmapConfig, jpgRef.get().size());
                bitmapRef.get().setHasAlpha(true);
                bitmapRef.get().eraseColor(Color.TRANSPARENT);
                return bitmapRef;
            } finally {
                EncodedImage.closeSafely(encodedImage);
            }
        } finally {
            jpgRef.close();
        }
    }
}
