package com.guido.styletransfer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.TimingLogger;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final String MODEL_FILE = "file:///android_asset/frozen_la_muse.pb";
    private static final String INPUT_NODE = "input";
    private static final String OUTPUT_NODE = "output";

    private int[] intValues;
    private float[] floatValues;

    private ImageView ivPhoto;

    File photoFile;
    private FileInputStream is = null;
    private static final int CODE = 1;
    private boolean workingRemotely = false;

    private TensorFlowInferenceInterface inferenceInterface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainActivity onCreate");
        TimingLogger timing = new TimingLogger(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        ivPhoto = (ImageView) findViewById(R.id.ivPhoto);

        Button onCamera = (Button) findViewById(R.id.onCamera);
        onCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    try {
                        photoFile = createImageFile();  //创建临时图片文件，方法在下面
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (photoFile != null) {
                        //FileProvider 是一个特殊的 ContentProvider 的子类，它使用 content:// Uri 代替了 file:///
                        // Uri. ，更便利而且安全的为另一个app分享文件
                        Uri photoURI = FileProvider.getUriForFile(MainActivity.this,
                                BuildConfig.APPLICATION_ID + ".fileprovider",
                                photoFile);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                        startActivityForResult(intent, 1);
                    }
                }
            }
        });
        timing.addSplit("UI rendered");

        initTensorFlowAndLoadModel();
        timing.addSplit("TensorFlow initialized");
        timing.dumpToLog();
    }

    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        // getExternalFilesDir()方法可以获取到 SDCard/Android/data/你的应用的包名/files/ 目录，一般放一些长时间保存的数据
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        Log.d("TAH", storageDir.toString());
        //创建临时文件,文件前缀不能少于三个字符,后缀如果为空默认未".tmp"
        File image = File.createTempFile(
                imageFileName,  /* 前缀 */
                ".jpg",         /* 后缀 */
                storageDir      /* 文件夹 */
        );
        return image;
    }


    private void initTensorFlowAndLoadModel() {
        intValues = new int[640 * 480];
        floatValues = new float[640 * 480 * 3];
        inferenceInterface = new TensorFlowInferenceInterface(getAssets(), MODEL_FILE);
    }

    private Bitmap scaleBitmap(Bitmap origin, int newWidth, int newHeight) {
        TimingLogger timings = new TimingLogger(TAG, "scaleBitmap");
        if (origin == null) {
            return null;
        }
        int height = origin.getHeight();
        int width = origin.getWidth();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        Bitmap newBM = Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false);
        if (!origin.isRecycled()) {
            origin.recycle();
        }
        timings.addSplit("scaling");
        timings.dumpToLog();
        return newBM;
    }

    private Bitmap stylizeImage(Bitmap bitmap, boolean remote) {
        if (remote) {
            return stylizeImageRemote(bitmap);
        } else {
            return stylizeImageLocal(bitmap);
        }
    }

    private Bitmap stylizeImageRemote(Bitmap bitmap) {
        Bitmap scaledBitmap = scaleBitmap(bitmap, 480, 640); // desiredSize
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight());
        Log.d(TAG, String.valueOf(intValues.length));
        final String url = "http://192.168.43.196:7913/stylize/";
        final OkHttpClient client = new OkHttpClient();
        final MediaType IMAGE_JPEG = MediaType.parse("image/jpeg");
        RequestBody body = new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return IMAGE_JPEG;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                OutputStream outputStream = sink.outputStream();
                for (int val : intValues) {
                    Log.d(TAG, String.valueOf(val));
                    outputStream.write(val);
                }
            }
        };

        Request request = new Request.Builder().url(url).post(body).build();
        try {
            Response response = client.newCall(request).execute();
            byte[] bytes = response.body().bytes();
            floatValues = new float[bytes.length / 4];
            for (int i = 0; i < floatValues.length; i++) {
                float x = ByteBuffer.wrap(Arrays.copyOfRange(bytes, i*4, i*4+4)).getFloat();
                floatValues[i] = x;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        for (int i = 0; i < intValues.length; ++i) {
            intValues[i] =
                    0xFF000000
                            | (((int) (floatValues[i * 3])) << 16)
                            | (((int) (floatValues[i * 3 + 1])) << 8)
                            | ((int) (floatValues[i * 3 + 2]));
        }
        scaledBitmap.setPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight());
        return scaledBitmap;
    }

    private Bitmap stylizeImageLocal(Bitmap bitmap) {
        TimingLogger timings = new TimingLogger(TAG, "stylizeImage");
        Bitmap scaledBitmap = scaleBitmap(bitmap, 480, 640); // desiredSize
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight());
        for (int i = 0; i < intValues.length; ++i) {
            final int val = intValues[i];
            floatValues[i * 3] = ((val >> 16) & 0xFF) * 1.0f;
            floatValues[i * 3 + 1] = ((val >> 8) & 0xFF) * 1.0f;
            floatValues[i * 3 + 2] = (val & 0xFF) * 1.0f;
        }
        timings.addSplit("Rebuild input tensor");

        // Copy the input data into TensorFlow.
        inferenceInterface.feed(INPUT_NODE, floatValues, 640, 480, 3);
        // Run the inference call.
        inferenceInterface.run(new String[]{OUTPUT_NODE});
        // Copy the output Tensor back into the output array.
        inferenceInterface.fetch(OUTPUT_NODE, floatValues);
        timings.addSplit("Inference");

        for (int i = 0; i < intValues.length; ++i) {
            intValues[i] =
                    0xFF000000
                            | (((int) (floatValues[i * 3])) << 16)
                            | (((int) (floatValues[i * 3 + 1])) << 8)
                            | ((int) (floatValues[i * 3 + 2]));
        }
        scaledBitmap.setPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight());
        timings.addSplit("Rebuild output image");
        timings.dumpToLog();
        return scaledBitmap;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (requestCode == CODE) {
                try {
                    is = new FileInputStream(photoFile);
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    Bitmap bitmap2 = stylizeImage(bitmap, ((Switch)findViewById(R.id.switchRemote)).isChecked());
                    ivPhoto.setImageBitmap(bitmap2);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

}
