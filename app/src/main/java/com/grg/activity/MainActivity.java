package com.grg.activity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.alibaba.fastjson.JSONObject;
import com.device.Crt900x;
import com.device.CrtPassportReader;
import com.device.CrtReaderUtil;
import com.grg.crt.SimplePassportReader;
import com.grg.sdk.DetectPicCallBack;
import com.grg.sdk.GrgSDK;
import com.grg.sdk.InitCallBack;
import com.grg.sdk.OcrParam;
import com.grg.sdk.TakePicCallBack;
import com.grg.test.R;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

  private static final String TAG = "MainActivity";

  private GrgSDK mGrgSDK;
  private OcrParam mOcrParam;
  private SimplePassportReader mPassportReader;

  private ViewGroup mCameraView;
  private ImageView mCurrentIv;
  private TextView mStatusTv;
  private TextView mMrzTv;
  private Button mScanBtn;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    mCameraView = findViewById(R.id.cameraView);
    mCurrentIv = findViewById(R.id.current_iv);
    mStatusTv = findViewById(R.id.resultTv);
    mMrzTv = findViewById(R.id.result_lv);
    mScanBtn = findViewById(R.id.singleReadBtn);
    mScanBtn.setEnabled(false);

    mOcrParam = new OcrParam();
    mGrgSDK = GrgSDK.getInstance(this);

    showStatus("Initialising SDK…");
    initGrg();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (mGrgSDK != null) {
      mGrgSDK.stopTask();
      mGrgSDK.closeLed();
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (mGrgSDK != null) {
      mGrgSDK.stopTask();
    }
  }

  private void initGrg() {
    mGrgSDK.init(new InitCallBack() {
      @Override
      public void initSuccess() {
        initPassportReader();
        mGrgSDK.openCamera(mCameraView, MainActivity.this, mOcrParam);
        runOnUiThread(() -> {
          showStatus("Ready — place document under camera");
          mScanBtn.setEnabled(true);
        });
      }

      @Override
      public void initFail(int code) {
        showStatus("Init failed: " + code);
      }

      @Override
      public void disconnect() {
        showStatus("Device disconnected");
        runOnUiThread(() -> mScanBtn.setEnabled(false));
      }

      @Override
      public void reConnect() {
        showStatus("Device reconnected");
        runOnUiThread(() -> mScanBtn.setEnabled(true));
      }
    });

    mScanBtn.setOnClickListener(v -> scanForMrz());
    findViewById(R.id.exitBtn).setOnClickListener(v -> {
      mGrgSDK.unInit();
      finish();
    });
  }

  private void initPassportReader() {
    try {
      CrtReaderUtil util = CrtReaderUtil.getInstance(this);

      Field readerField = CrtReaderUtil.class.getDeclaredField("passportReader");
      readerField.setAccessible(true);
      CrtPassportReader passportReader = (CrtPassportReader) readerField.get(util);

      Field nativeField = CrtPassportReader.class.getDeclaredField("crt900xNative");
      nativeField.setAccessible(true);
      Field fdField = CrtPassportReader.class.getDeclaredField("readerFd");
      fdField.setAccessible(true);
      Crt900x crt900x = (Crt900x) nativeField.get(passportReader);
      String readerFd = (String) fdField.get(passportReader);

      mPassportReader = new SimplePassportReader(crt900x, readerFd);
      Log.d(TAG, "SimplePassportReader wired up successfully fd=" + readerFd);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      Log.e(TAG, "Failed to wire SimplePassportReader: " + e.getMessage());
      showStatus("Reader init failed — " + e.getMessage());
    }
  }

  private void scanForMrz() {
    mScanBtn.setEnabled(false);
    mMrzTv.setText("");
    showStatus("Capturing…");
    mGrgSDK.startTask();

    mGrgSDK.takeRedPic(300, false, false, new TakePicCallBack() {
      @Override
      public void takePicResult(int code, TakePicResult result) {
        if (code != 0 || result == null || result.bitmap == null) {
          showStatus("Capture failed (code " + code + ")");
          resetButton();
          return;
        }
        Bitmap previewCopy = result.bitmap.copy(result.bitmap.getConfig(), false);
        runOnUiThread(() -> mCurrentIv.setImageBitmap(previewCopy));
        detectMrz(result.bitmap);
      }
    });
  }

  private void detectMrz(Bitmap bitmap) {
    showStatus("Running OCR…");

    mGrgSDK.detectRedPic(bitmap, new DetectPicCallBack() {
      @Override
      public void detectPicResult(int code, DetectPicResult result) {
        mGrgSDK.stopTask();
        mGrgSDK.closeLed();

        if (code == 0 && result != null) {
          Log.d(TAG, "MRZ: " + result.mrz);
          runOnUiThread(() -> mMrzTv.setText(result.mrz));
          readChip(result.mrz);
        } else {
          showStatus("OCR failed — no MRZ found");
          resetButton();
        }
      }
    });
  }

  private void readChip(String mrz) {
    if (mPassportReader == null) {
      showStatus("Reader not available");
      resetButton();
      return;
    }
    showStatus("Reading chip…");

    mPassportReader.getAllData(mrz, this, new SimplePassportReader.OnGetAllDataResult() {
      @Override
      public void onSuccess(String dgData) {
        showStatus("Chip read OK");
        runOnUiThread(() -> mMrzTv.append("\n\n--- Chip Data ---\n" + dgData));
        resetButton();
      }

      @Override
      public void onError(int code, String message) {
        showStatus("Chip read failed: " + message);
        if (message == "CrtSendAPDU failed, ret=-2") {
          initGrg();
        }
        resetButton();
      }
    });
  }

  private void showStatus(String msg) {
    Log.d(TAG, msg);
    runOnUiThread(() -> mStatusTv.setText(msg));
  }

  private void resetButton() {
    runOnUiThread(() -> mScanBtn.setEnabled(true));
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    mGrgSDK.unInit();
  }
}
