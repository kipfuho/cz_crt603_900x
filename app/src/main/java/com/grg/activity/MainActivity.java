package com.grg.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.device.Crt900x;
import com.device.CrtPassportReader;
import com.device.CrtReaderUtil;
import com.grg.Utils;
import com.grg.crt.SimplePassportReader;
import com.grg.grglog.GrgLog;
import com.grg.grglog.LogUtils;
import com.grg.sdk.FaceCheckCallBack;
import com.grg.sdk.GrgSDK;
import com.grg.sdk.InitCallBack;
import com.grg.sdk.OcrParam;
import com.grg.sdk.ReadCardCallBack;
import com.grg.sdk.TakePicCallBack;
import com.grg.test.R;
import com.lib.common.utils.BitmapUtils;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import xcrash.XCrash;

public class MainActivity extends AppCompatActivity {

  private static final String TAG = "MainActivity";

  private GrgSDK mGrgSDK;
  private OcrParam mOcrParam;
  private SimplePassportReader mPassportReader;

  private ViewGroup mCameraView;
  private ImageView mCurrentIv;
  private TextView mStatusTv;
  private TextView mMrzTv;
  private Button mTakePictureLightBtn;
  private Button mTakePictureIrBtn;
  private Button mTakePictureUvBtn;
  private Button mScanBtn;
  private Button mLoopScanBtn;
  private Spinner spinnerCardType;

  int OcrType = 0;
  boolean isOCRinit = false;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    initLog();
    mCameraView = findViewById(R.id.cameraView);
    mCurrentIv = findViewById(R.id.current_iv);
    mStatusTv = findViewById(R.id.resultTv);
    mMrzTv = findViewById(R.id.result_lv);

    mTakePictureLightBtn = findViewById(R.id.takePictureLightBtn);
    mTakePictureIrBtn = findViewById(R.id.takePictureIrBtn);
    mTakePictureUvBtn = findViewById(R.id.takePictureUvBtn);
    mTakePictureLightBtn.setEnabled(false);
    mTakePictureIrBtn.setEnabled(false);
    mTakePictureUvBtn.setEnabled(false);
    mScanBtn = findViewById(R.id.singleReadBtn);
    mScanBtn.setEnabled(false);
    mLoopScanBtn = findViewById(R.id.loopReadBtn);
    mLoopScanBtn.setEnabled(false);

    // Card type spinner
    spinnerCardType = findViewById(R.id.spinnerCardType);
    String[] cardTypes = {"id", "passport"};
    spinnerCardType.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, cardTypes));
    String intentCardType = String.valueOf(cardTypes);
    for (int i = 0; i < cardTypes.length; i++) {
      if (cardTypes[i].equals(intentCardType)) {
        spinnerCardType.setSelection(i);
        break;
      }
    }

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

  private void initLog() {
    String LogPath = getExternalFilesDir(null).getPath();
    GrgLog.init(LogPath + "/log/" + new SimpleDateFormat(GrgLog.DATE_FORMAT).format(new Date()));
    XCrash.init(this, new XCrash.InitParameters().setLogDir(
        LogPath + "/crash/" + new SimpleDateFormat("yyyy-MM-dd").format(new Date())));
    LogUtils.openLog(true);
  }

  private void initGrg() {
    mGrgSDK.init(new InitCallBack() {
      @Override
      public void initSuccess() {
        initPassportReader();
        if (OcrType != 0 && !isOCRinit) {
          showStatus(getString(R.string.init_fail) + ":" + getString(R.string.init_ocr_fail));
        } else {
          mGrgSDK.openCamera(mCameraView, MainActivity.this, mOcrParam);
          showStatus("Ready — place document under camera");
          resetButton();
        }
      }

      @Override
      public void initFail(int code) {
        showStatus("Init failed: " + code);
      }

      @Override
      public void disconnect() {
        showStatus("Device disconnected");
        disableButton();
      }

      @Override
      public void reConnect() {
        showStatus("Device reconnected");
        resetButton();
      }
    });

    mTakePictureLightBtn.setOnClickListener(v -> takePicture(1));
    mTakePictureIrBtn.setOnClickListener(v -> takePicture(2));
    mTakePictureUvBtn.setOnClickListener(v -> takePicture(3));
    mScanBtn.setOnClickListener(v -> scanForMrz());
    mLoopScanBtn.setOnClickListener(v -> loopScanForMrz());

    findViewById(R.id.btSimKiosk).setOnClickListener(v -> runOnUiThread(new Runnable() {
      @Override
      public void run() {
        btn_SimKiosk();
      }
    }));
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

  public void btn_SimKiosk() {
    Intent intent = new Intent(MainActivity.this, SimKioskActivity.class);
    startActivity(intent);
  }

  private void takePicture(int lightMode) {
    disableButton();
    showStatus("Capturing…");
    mGrgSDK.startTask();

    // light mode
    // 1 - white
    // 2 - ir
    // 3 - uv
    mGrgSDK.takePic(lightMode, 300, (code, result) -> {
      mGrgSDK.stopTask();
      mGrgSDK.closeLed();
      if (code != 0 || result == null || result.bitmap == null) {
        showStatus("Capture failed (code " + code + ")");
        resetButton();
        return;
      }

      runOnUiThread(() -> mCurrentIv.setImageBitmap(safeCopyBitmap(result.bitmap)));
      showStatus("Capture success!");
      resetButton();
    });
  }

  private void scanForMrz() {
    disableButton();
    mMrzTv.setText("");
    showStatus("Capturing…");
    mGrgSDK.startTask();

    String cardType = getCardType();
    mGrgSDK.takeRedPic(300, false, false, (code, result) -> {
      if (code != 0 || result == null || result.bitmap == null) {
        showStatus("Capture failed (code " + code + ")");
        resetButton();
        return;
      }

      detectMrz(safeCopyBitmap(result.bitmap), cardType, false); // false = not yet flipped
    });
  }

  private void loopScanForMrz() {
    mScanBtn.setEnabled(false);
    mMrzTv.setText("");
    showStatus("Capturing…");
    mGrgSDK.startTask();

    String cardType = getCardType();
    mGrgSDK.takeRedPic(300, true, true, (code, result) -> {
      if (code != 0 || result == null || result.bitmap == null) {
        showStatus("Capture failed (code " + code + ")");
        resetButton();
        return;
      }

      detectMrz(safeCopyBitmap(result.bitmap), cardType, false);
    });
  }

  private void detectMrz(Bitmap bitmap, String cardType, boolean alreadyFlipped) {
    showStatus("Running OCR…");

    mGrgSDK.detectRedPic(bitmap, (code, result) -> {
      if (code == 0 && result != null) {
        mGrgSDK.stopTask();
        mGrgSDK.closeLed();
        Log.d(TAG, "MRZ: " + result.mrz);
        runOnUiThread(() -> mMrzTv.setText(result.mrz));
        if (Objects.equals(cardType, "id")) {
          readChip(result.mrz);
        } else {
          readPassport(result.mrz);
        }
      } else if (!alreadyFlipped) {
        // MRZ failed — flip and retry once
        showStatus("OCR failed — retrying flipped…");
        Bitmap flipped = BitmapUtils.getFlipBitmap(bitmap);
        detectMrz(flipped, cardType, true);
      } else {
        // Already tried both orientations, give up
        mGrgSDK.stopTask();
        mGrgSDK.closeLed();
        showStatus("OCR failed — no MRZ found");
        resetButton();
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
        if (Objects.equals(message, "CrtSendAPDU failed, ret=-2")) {
          initGrg();
        }
        resetButton();
      }
    });
  }

  private void readPassport(String mrz) {
    if (mPassportReader == null) {
      showStatus("Reader not available");
      resetButton();
      return;
    }
    showStatus("Reading passport…");

    Log.d(TAG, "readPassport mrz: " + mrz);
    mGrgSDK.readCard(mrz, new ReadCardCallBack() {
      @Override
      public void readCardResult(int code, ReadCardResult result) {
        if (code == 0 && result != null) {
          Log.d(TAG, "readPassport result: " + result.resultList);
          runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            if (result.resultList != null) {
              for (Object item : result.resultList) {
                sb.append(item.toString()).append("\n");
              }
            }
            mMrzTv.append("\n\n--- Passport Data ---\n" + sb);
            if (result.face != null) {
              mCurrentIv.setImageBitmap(result.face);
            }
          });
          showStatus("Passport read OK");
        } else {
          showStatus("Passport read failed (code " + code + ")");
        }
        resetButton();
      }
    });
  }

  private String getCardType() {
    String cardType = spinnerCardType.getSelectedItem().toString();
    return cardType;
  }

  private void showStatus(String msg) {
    Log.d(TAG, msg);
    runOnUiThread(() -> mStatusTv.setText(msg));
  }

  private Bitmap safeCopyBitmap(Bitmap bitmap) {
    Bitmap safeCopy = Utils.toSoftwareBitmap(bitmap);
    runOnUiThread(() -> mCurrentIv.setImageBitmap(safeCopy));
    return safeCopy;
  }

  private void disableButton() {
    runOnUiThread(() -> {
      mTakePictureLightBtn.setEnabled(false);
      mTakePictureIrBtn.setEnabled(false);
      mTakePictureUvBtn.setEnabled(false);
      mScanBtn.setEnabled(false);
      mLoopScanBtn.setEnabled(false);
    });
  }

  private void resetButton() {
    runOnUiThread(() -> {
      mTakePictureLightBtn.setEnabled(true);
      mTakePictureIrBtn.setEnabled(true);
      mTakePictureUvBtn.setEnabled(true);
      mScanBtn.setEnabled(true);
      mLoopScanBtn.setEnabled(true);
    });
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    mGrgSDK.unInit();
  }
}
