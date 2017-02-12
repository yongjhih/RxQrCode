/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Piasy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.piasy.rxqrcode;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.IdRes;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import com.github.piasy.cameracompat.CameraCompat;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

import static io.reactivex.BackpressureStrategy.BUFFER;
import static io.reactivex.BackpressureStrategy.DROP;

/**
 * Created by Piasy{github.com/Piasy} on 19/10/2016.
 */

public final class RxQrCode {
    private static final int QR_CODE_LENGTH = 200;
    private static final int QR_CODE_IN_SAMPLE_LENGTH = 512;
    private static final int MAX_RETRY_TIMES = 4;

    private static final Map<DecodeHintType, ?> TRY_HARDER = Collections
            .singletonMap(DecodeHintType.TRY_HARDER, true);

    private RxQrCode() {
        // no instance
    }

    public static Flowable<Result> scanFromCamera(@Nullable Bundle savedInstanceState,
                                                    FragmentManager fragmentManager, @IdRes final int container,
                                                    CameraCompat.ErrorHandler handler) {
        return create(savedInstanceState, fragmentManager, container, handler)
                .observeOn(Schedulers.computation())
                .map(ImageFrame::deepCopy)
                .map(RxQrCode::frame2source)
                .flatMap(source -> resolve(source, false));
    }

    public static Observable<Result> scanFromPicture(String path) {
        return Observable.fromCallable(() -> {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, QR_CODE_IN_SAMPLE_LENGTH,
                    QR_CODE_IN_SAMPLE_LENGTH);

            options.inJustDecodeBounds = false;
            int[] pixels = null;

            QRCodeReader reader = new QRCodeReader();
            int retryTimes = 0;
            Result result = null;
            while (result == null && retryTimes < MAX_RETRY_TIMES) {
                Bitmap picture = BitmapFactory.decodeFile(path, options);
                int width = picture.getWidth();
                int height = picture.getHeight();
                if (pixels == null) {
                    pixels = new int[picture.getWidth() * picture.getHeight()];
                }
                picture.getPixels(pixels, 0, width, 0, 0, width, height);
                picture.recycle();
                LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                try {
                    result = reader.decode(bitmap, TRY_HARDER);
                } catch (NotFoundException | ChecksumException | FormatException ignored) {
                    retryTimes++;
                    options.inSampleSize *= 2;
                }
            }
            reader.reset();

            return result;
        }).flatMap(result -> {
            if (result == null) {
                return Observable.error(NotFoundException.getNotFoundInstance());
            }
            return Observable.just(result);
        });
    }

    /**
     * @Deprecated use {@link #generateQrCodeFile(Context, String, int, int)} to avoid bitmap
     * management.
     */
    @Deprecated
    public static Flowable<Bitmap> generateQrCode(String content, int width, int height) {
        return Flowable.create(emitter -> {
            MultiFormatWriter writer = new MultiFormatWriter();
            try {
                BitMatrix bm = writer.encode(content, BarcodeFormat.QR_CODE, QR_CODE_LENGTH,
                        QR_CODE_LENGTH, Collections.singletonMap(EncodeHintType.MARGIN, 0));
                Bitmap bitmap = Bitmap.createBitmap(QR_CODE_LENGTH, QR_CODE_LENGTH,
                        Bitmap.Config.ARGB_8888);

                for (int i = 0; i < QR_CODE_LENGTH; i++) {
                    for (int j = 0; j < QR_CODE_LENGTH; j++) {
                        bitmap.setPixel(i, j, bm.get(i, j) ? Color.BLACK : Color.WHITE);
                    }
                }
                emitter.onNext(Bitmap.createScaledBitmap(bitmap, width, height, true));
                emitter.onComplete();
            } catch (WriterException e) {
                emitter.onError(e);
            }
        }, BUFFER);
    }

    public static Flowable<File> generateQrCodeFile(Context context, String content, int width,
            int height) {
        return Flowable.create(emitter -> {
            MultiFormatWriter writer = new MultiFormatWriter();
            Bitmap origin = null;
            Bitmap scaled = null;
            try {
                BitMatrix bm = writer.encode(content, BarcodeFormat.QR_CODE, QR_CODE_LENGTH,
                        QR_CODE_LENGTH, Collections.singletonMap(EncodeHintType.MARGIN, 0));
                origin = Bitmap.createBitmap(QR_CODE_LENGTH, QR_CODE_LENGTH,
                        Bitmap.Config.ARGB_8888);

                for (int i = 0; i < QR_CODE_LENGTH; i++) {
                    for (int j = 0; j < QR_CODE_LENGTH; j++) {
                        origin.setPixel(i, j, bm.get(i, j) ? Color.BLACK : Color.WHITE);
                    }
                }
                scaled = Bitmap.createScaledBitmap(origin, width, height, true);
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (dir == null) {
                    emitter.onError(new IllegalStateException("external file system unavailable!"));
                    return;
                }
                String fileName = "rx_qr_" + System.currentTimeMillis() + ".png";
                File localFile = new File(dir, fileName);

                FileOutputStream outputStream = new FileOutputStream(localFile);
                scaled.compress(Bitmap.CompressFormat.PNG, 85, outputStream);
                outputStream.flush();
                outputStream.close();

                emitter.onNext(localFile);
                emitter.onComplete();
            } catch (WriterException | IOException e) {
                emitter.onError(e);
            } finally {
                if (origin != null) {
                    origin.recycle();
                }
                if (scaled != null) {
                    scaled.recycle();
                }
            }
        }, BUFFER);
    }

    private static Flowable<ImageFrame> create(@Nullable Bundle savedInstanceState,
            FragmentManager fragmentManager, @IdRes final int container,
            CameraCompat.ErrorHandler handler) {
        return Flowable.create(emitter -> {
            new CameraCompat.Builder(
                    new CameraCompat.VideoCaptureCallback() {
                        @Override
                        public void onVideoSizeChanged(int width, int height) {
                        }

                        @Override
                        public void onFrameData(byte[] data, int width, int height) {
                            emitter.onNext(new ImageFrame(data, width, height, false));
                        }
                    }, handler)
                    .frontCamera(false)
                    .build()
                    .startPreview(savedInstanceState, fragmentManager, container);
        }, DROP);
    }

    private static LuminanceSource frame2source(ImageFrame frame) {
        return new PlanarYUVLuminanceSource(frame.getData(),
                frame.getWidth(), frame.getHeight(), 0, 0, frame.getWidth(),
                frame.getHeight(), false);
    }

    private static Flowable<Result> resolve(LuminanceSource source, boolean failWhenNotFound) {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        QRCodeReader reader = new QRCodeReader();
        try {
            return Flowable.just(reader.decode(bitmap, TRY_HARDER));
        } catch (NotFoundException | ChecksumException | FormatException e) {
            if (failWhenNotFound) {
                return Flowable.error(e);
            }
        } finally {
            reader.reset();
        }
        return Flowable.empty();
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth,
            int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        while (height / inSampleSize > reqHeight && width / inSampleSize > reqWidth) {
            inSampleSize *= 2;
        }

        return inSampleSize;
    }
}
